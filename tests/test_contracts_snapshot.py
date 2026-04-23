"""Stage-0 contract snapshot tests.

These tests freeze the externally visible surface that the Claude Code runtime
depends on. They MUST stay green across stage-1/2/3/4 internal refactors. Any
intentional change requires updating both this file and AGENTS.md "Contract
Surface" section.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict


ROOT = Path(__file__).resolve().parents[1]
TESTS = Path(__file__).resolve().parent
FIXTURES = TESTS / "fixtures"
SCRIPTS = ROOT / "scripts"
# Tests pin to a fixture feeds.json so they stay stable across user edits to
# the real feeds.json at the repo root.
FEEDS_PATH = FIXTURES / "feeds_fixture.json"

PIPELINE_SCRIPT = SCRIPTS / "rss_daily_report.py"
LLM_CONTEXT_SCRIPT = SCRIPTS / "build_llm_context.py"
VALIDATE_SCRIPT = SCRIPTS / "qc_validate.py"

EXPECTED_PIPELINE_KEYS = {
    "report_date",
    "run_dir",
    "raw_path",
    "validation_path",
    "llm_context_path",
    "report_path",
    "validation_passed",
    "validator_exit_code",
}

EXPECTED_LLM_CONTEXT_TOP_KEYS = {
    "meta",
    "validation",
    "candidate_articles",
    "all_articles",
    "source_groups",
}

EXPECTED_META_KEYS = {"date", "generated_at_utc", "run_id", "report_path"}

EXPECTED_VALIDATION_KEYS = {
    "passed",
    "blocking_reasons",
    "warnings",
    "counts",
    "policy",
}

EXPECTED_ARTICLE_KEYS = {
    "source",
    "title",
    "link",
    "pub_date_utc",
    "pub_date_iso",
    "summary_en",
    "article_text",
    "heuristic_score",
    "audit_flags",
    "amount_millions",
}

EXPECTED_SOURCE_GROUP_KEYS = {"source", "url", "status", "article_count", "articles"}

ALLOWED_AUDIT_FLAGS = {
    "major_company",
    "business_signal",
    "security_signal",
    "breakthrough_signal",
    "launch_signal",
    "speculation",
    "noise",
    "hard_noise",
    "funding_or_deal_ge_100m",
}


def _import_test_helpers():
    """Reuse materialize_raw / run_validator from the existing offline suite."""
    spec = importlib.util.spec_from_file_location(
        "test_qc_offline_helpers",
        TESTS / "test_qc_offline.py",
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("Failed to import test_qc_offline.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


_helpers = _import_test_helpers()
materialize_raw = _helpers.materialize_raw
run_validator = _helpers.run_validator
load_pipeline_config_fixture = _helpers.load_pipeline_config_fixture


def _run_build_llm_context(raw: Dict[str, Any], validation: Dict[str, Any],
                           date_str: str = "2026-04-10",
                           report_path_value: str = "/tmp/DailyNews/rss-report-2026-04-10.md") -> Dict[str, Any]:
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        raw_path = tmp / "raw.json"
        val_path = tmp / "validation.json"
        ctx_path = tmp / "llm_context.json"
        raw_path.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")
        val_path.write_text(json.dumps(validation, ensure_ascii=False, indent=2), encoding="utf-8")

        proc = subprocess.run(
            [
                sys.executable, str(LLM_CONTEXT_SCRIPT),
                "--input", str(raw_path),
                "--validation", str(val_path),
                "--output", str(ctx_path),
                "--date", date_str,
                "--report-path", report_path_value,
            ],
            capture_output=True, text=True, check=True,
        )
        return json.loads(ctx_path.read_text(encoding="utf-8"))


def _run_pipeline_with_stub_fetch(raw: Dict[str, Any]) -> Dict[str, Any]:
    """Run rss_daily_report.py end-to-end, but replace the fetch step.

    A stub fetch script writes the supplied raw.json verbatim to stdout, so the
    pipeline exercises validate -> build_llm_context -> render with deterministic
    input.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        # Stub fetch: print provided raw JSON
        raw_inline = json.dumps(raw, ensure_ascii=False)
        stub_fetch = tmp / "stub_fetch.py"
        stub_fetch.write_text(
            "import sys\n"
            "sys.stdout.write(" + repr(raw_inline) + ")\n"
            "sys.stdout.write('\\n')\n",
            encoding="utf-8",
        )
        # Patched pipeline: rewrite FETCH_SCRIPT constant by env override is not
        # supported, so we monkey-patch via a tiny launcher.
        launcher = tmp / "launch_pipeline.py"
        launcher.write_text(
            "import importlib.util, sys, pathlib\n"
            f"spec = importlib.util.spec_from_file_location('p', r'{PIPELINE_SCRIPT}')\n"
            "module = importlib.util.module_from_spec(spec)\n"
            "spec.loader.exec_module(module)\n"
            f"module.FETCH_SCRIPT = pathlib.Path(r'{stub_fetch}')\n"
            f"module.FEEDS_FILE = pathlib.Path(r'{FEEDS_PATH}')\n"
            "sys.argv = ['rss_daily_report.py', '--json-output', "
            f"'--runs-dir', r'{tmp}/runs', '--date', '2026-04-10']\n"
            "raise SystemExit(module.main())\n",
            encoding="utf-8",
        )
        proc = subprocess.run(
            [sys.executable, str(launcher)],
            capture_output=True, text=True, check=False,
        )
        # Parse stdout JSON; first line of stdout is the JSON payload (multi-line indent=2)
        # rss_daily_report prints json.dumps(payload, indent=2), so parse the whole stdout
        try:
            payload = json.loads(proc.stdout)
        except json.JSONDecodeError as exc:
            raise AssertionError(
                f"Pipeline did not emit valid JSON. exit={proc.returncode} "
                f"stdout={proc.stdout!r} stderr={proc.stderr!r}"
            ) from exc
        return {"exit_code": proc.returncode, "payload": payload, "stderr": proc.stderr}


class LlmContextContractTests(unittest.TestCase):
    """Freeze the structure of llm_context.json that the runtime consumes."""

    @classmethod
    def setUpClass(cls):
        raw = materialize_raw("golden_success.json")
        code, validation = run_validator(raw)
        assert code == 0
        cls.context = _run_build_llm_context(raw, validation)
        cls.golden = json.loads((FIXTURES / "llm_context_golden.json").read_text(encoding="utf-8"))

    def test_top_level_keys_match_contract(self):
        self.assertEqual(set(self.context.keys()), EXPECTED_LLM_CONTEXT_TOP_KEYS)

    def test_meta_keys_match_contract(self):
        self.assertEqual(set(self.context["meta"].keys()), EXPECTED_META_KEYS)

    def test_validation_keys_match_contract(self):
        self.assertEqual(set(self.context["validation"].keys()), EXPECTED_VALIDATION_KEYS)

    def test_candidate_article_fields_match_contract(self):
        self.assertGreater(len(self.context["candidate_articles"]), 0)
        for article in self.context["candidate_articles"]:
            self.assertEqual(set(article.keys()), EXPECTED_ARTICLE_KEYS,
                             msg=f"unexpected article keys: {article}")

    def test_all_articles_field_set_consistent(self):
        for article in self.context["all_articles"]:
            self.assertEqual(set(article.keys()), EXPECTED_ARTICLE_KEYS)

    def test_source_group_fields_match_contract(self):
        for group in self.context["source_groups"]:
            self.assertEqual(set(group.keys()), EXPECTED_SOURCE_GROUP_KEYS)
            for article in group["articles"]:
                self.assertEqual(set(article.keys()), EXPECTED_ARTICLE_KEYS)

    def test_audit_flags_subset_of_allowed(self):
        for article in self.context["all_articles"]:
            flags = set(article["audit_flags"])
            self.assertTrue(
                flags.issubset(ALLOWED_AUDIT_FLAGS),
                msg=f"audit_flags {flags - ALLOWED_AUDIT_FLAGS} not allowed",
            )

    def test_heuristic_score_is_numeric(self):
        for article in self.context["all_articles"]:
            self.assertIsInstance(article["heuristic_score"], (int, float))
            self.assertIsInstance(article["amount_millions"], (int, float))

    def test_candidate_articles_sorted_by_score_desc(self):
        scores = [a["heuristic_score"] for a in self.context["candidate_articles"]]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_source_groups_cover_all_feeds(self):
        feeds = json.loads(FEEDS_PATH.read_text(encoding="utf-8"))["feeds"]
        feed_names = {f["name"] for f in feeds}
        group_names = {g["source"] for g in self.context["source_groups"]}
        self.assertEqual(group_names, feed_names)

    def test_part2_article_total_equals_validation_counts(self):
        total = sum(g["article_count"] for g in self.context["source_groups"])
        self.assertEqual(total, self.context["validation"]["counts"]["articles"])

    def test_byte_level_match_with_golden(self):
        """If this fails, you changed an LLM-visible field. Update both the
        golden fixture AND the scheduled-task prompt deliberately."""
        self.assertEqual(self.context, self.golden)


class PipelineJsonOutputContractTests(unittest.TestCase):
    """Freeze the 8-field --json-output schema the scheduled task parses."""

    @classmethod
    def setUpClass(cls):
        raw = materialize_raw("golden_success.json")
        cls.result = _run_pipeline_with_stub_fetch(raw)

    def test_pipeline_json_keys_match_contract(self):
        self.assertEqual(set(self.result["payload"].keys()), EXPECTED_PIPELINE_KEYS)

    def test_pipeline_validation_passed_on_golden(self):
        self.assertTrue(self.result["payload"]["validation_passed"])
        self.assertEqual(self.result["exit_code"], 0)

    def test_pipeline_paths_are_absolute(self):
        for key in ("run_dir", "raw_path", "validation_path",
                    "llm_context_path", "report_path"):
            value = self.result["payload"][key]
            self.assertTrue(Path(value).is_absolute(),
                            msg=f"{key} should be absolute, got {value}")

    def test_pipeline_report_path_matches_validation_state(self):
        report_path = self.result["payload"]["report_path"]
        # passed=True means the success-name file (no .failed.md suffix)
        self.assertTrue(report_path.endswith("rss-report-2026-04-10.md"))
        self.assertFalse(report_path.endswith(".failed.md"))


class RawFixtureConfigSnapshotTests(unittest.TestCase):
    """Keep render-related pipeline tests pinned to fixture-backed config."""

    def test_materialized_raw_carries_fixture_config_snapshot(self):
        raw = materialize_raw("golden_success.json")
        self.assertEqual(raw.get("runtime_config", {}).get("summary_enrichment"), {
            "short_summary_threshold": load_pipeline_config_fixture()["summary_enrichment"]["short_summary_threshold"],
            "page_fallback_cap": load_pipeline_config_fixture()["summary_enrichment"]["page_fallback_cap"],
            "effective_page_fallback_cap": load_pipeline_config_fixture()["summary_enrichment"]["page_fallback_cap"],
        })
        self.assertEqual(raw.get("runtime_config", {}).get("render"), load_pipeline_config_fixture()["render"])


class ExitCodeTranslationContractTests(unittest.TestCase):
    """Lock the (scenario -> exit code) mapping the prompt branches on."""

    def _validate_and_get_code(self, scenario: str) -> int:
        raw = materialize_raw(scenario)
        code, _ = run_validator(raw)
        return code

    def test_golden_success_returns_0(self):
        self.assertEqual(self._validate_and_get_code("golden_success.json"), 0)

    def test_all_empty_returns_30(self):
        self.assertEqual(self._validate_and_get_code("all_empty_but_ok.json"), 30)

    def test_all_error_returns_30(self):
        self.assertEqual(self._validate_and_get_code("all_error.json"), 30)

    def test_partial_failure_returns_0(self):
        self.assertEqual(
            self._validate_and_get_code("partial_failure_mix.json"), 0
        )

    def test_legacy_raw_missing_meta_returns_10(self):
        # Build a raw doc that omits meta entirely
        raw = {"hours": 24, "count": 0, "articles": []}
        code, _ = run_validator(raw)
        self.assertEqual(code, 10)


if __name__ == "__main__":
    unittest.main()
