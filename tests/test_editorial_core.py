from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TESTS = Path(__file__).resolve().parent
FIXTURES = TESTS / "fixtures"
SCRIPTS = ROOT / "scripts"
EDITORIAL_MODULE_PATH = SCRIPTS / "_common" / "editorial.py"
LLM_CONTEXT_SCRIPT = SCRIPTS / "build_llm_context.py"
RENDER_SCRIPT = SCRIPTS / "render_report.py"


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to import {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _import_test_helpers():
    return _load_module("test_qc_offline_helpers_for_editorial", TESTS / "test_qc_offline.py")


_helpers = _import_test_helpers()
materialize_raw = _helpers.materialize_raw
run_validator = _helpers.run_validator
PIPELINE_CONFIG_PATH = _helpers.PIPELINE_CONFIG_PATH


class EditorialCoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.editorial = _load_module("dailynews_editorial_core", EDITORIAL_MODULE_PATH)
        cls.render_module = _load_module("dailynews_render_report", RENDER_SCRIPT)
        cls.golden_context = json.loads((FIXTURES / "llm_context_golden.json").read_text(encoding="utf-8"))

    def test_normalized_article_payload_matches_golden_fixture(self):
        raw_data = materialize_raw("golden_success.json")
        articles = self.editorial.normalize_articles(raw_data)
        payloads = [self.editorial.normalized_article_payload(article) for article in articles]
        self.assertEqual(payloads, self.golden_context["all_articles"])

    def test_normalize_source_groups_rejects_contradictory_status(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)
        self.assertEqual(code, 0)

        bad_validation = json.loads(json.dumps(validation))
        for entry in bad_validation["feed_results"]:
            if entry["source"] == "Ars Technica":
                entry["status"] = "empty"
                entry["article_count"] = 0
                break
        else:
            self.fail("Ars Technica feed_result not found in fixture")

        articles = self.editorial.normalize_articles(raw_data)
        with self.assertRaises(self.editorial.SourceGroupConsistencyError):
            self.editorial.normalize_source_groups(raw_data, bad_validation, articles)

    def test_build_llm_context_cli_fails_on_source_group_contradiction(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)
        self.assertEqual(code, 0)

        bad_validation = json.loads(json.dumps(validation))
        for entry in bad_validation["feed_results"]:
            if entry["source"] == "Ars Technica":
                entry["status"] = "empty"
                entry["article_count"] = 0
                break

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            raw_path = tmp / "raw.json"
            validation_path = tmp / "validation.json"
            output_path = tmp / "llm_context.json"
            raw_path.write_text(json.dumps(raw_data, ensure_ascii=False, indent=2), encoding="utf-8")
            validation_path.write_text(json.dumps(bad_validation, ensure_ascii=False, indent=2), encoding="utf-8")

            proc = subprocess.run(
                [
                    sys.executable,
                    str(LLM_CONTEXT_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(output_path),
                    "--date", "2026-04-10",
                    "--report-path", str(tmp / "rss-report-2026-04-10.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            output_exists = output_path.exists()

        self.assertEqual(proc.returncode, 40)
        self.assertIn("source group consistency error", proc.stderr)
        self.assertFalse(output_exists)

    def test_render_report_cli_fails_on_source_group_contradiction(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)
        self.assertEqual(code, 0)

        bad_validation = json.loads(json.dumps(validation))
        for entry in bad_validation["feed_results"]:
            if entry["source"] == "Ars Technica":
                entry["status"] = "empty"
                entry["article_count"] = 0
                break

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            raw_path = tmp / "raw.json"
            validation_path = tmp / "validation.json"
            output_path = tmp / "rss-report-2026-04-10.md"
            raw_path.write_text(json.dumps(raw_data, ensure_ascii=False, indent=2), encoding="utf-8")
            validation_path.write_text(json.dumps(bad_validation, ensure_ascii=False, indent=2), encoding="utf-8")

            proc = subprocess.run(
                [
                    sys.executable,
                    str(RENDER_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(output_path),
                    "--date", "2026-04-10",
                    *(
                        ["--config", str(PIPELINE_CONFIG_PATH)]
                        if PIPELINE_CONFIG_PATH.exists() else []
                    ),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            output_exists = output_path.exists()

        self.assertEqual(proc.returncode, 40)
        self.assertIn("source group consistency error", proc.stderr)
        self.assertFalse(output_exists)

    def test_resolve_render_config_prefers_raw_snapshot(self):
        raw_data = materialize_raw("golden_success.json")
        raw_data["runtime_config"] = {
            "config_path": str(PIPELINE_CONFIG_PATH.resolve()),
            "summary_enrichment": {
                "short_summary_threshold": 25,
                "page_fallback_cap": 111,
                "effective_page_fallback_cap": 111,
            },
            "render": {
                "part1_summary_max_chars": 21,
                "part2_summary_max_chars": 13,
            }
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = Path(tmpdir) / "pipeline_config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "render": {
                            "part1_summary_max_chars": 80,
                            "part2_summary_max_chars": 70,
                        }
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )
            config = self.render_module.resolve_render_config(raw_data, str(config_path))

        self.assertEqual(config["part1_summary_max_chars"], 21)
        self.assertEqual(config["part2_summary_max_chars"], 13)


if __name__ == "__main__":
    unittest.main()
