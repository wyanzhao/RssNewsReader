from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
# Tests pin to a fixture feeds.json so they stay stable across user edits to
# the real feeds.json at the repo root.
FEEDS_PATH = FIXTURES / "feeds_fixture.json"
PIPELINE_CONFIG_PATH = FIXTURES / "pipeline_config_fixture.json"
VALIDATE_SCRIPT = ROOT / "scripts" / "qc_validate.py"
RENDER_SCRIPT = ROOT / "scripts" / "render_report.py"
RSS_MONITOR_SCRIPT = ROOT / "scripts" / "rss_news_monitor.py"
LLM_CONTEXT_SCRIPT = ROOT / "scripts" / "build_llm_context.py"

EXIT_OK = 0
EXIT_INPUT_DAMAGED = 10
EXIT_CONTRACT_MISMATCH = 20
EXIT_DATA_QUALITY_BLOCK = 30


def load_json_fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def load_text_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def load_feeds():
    return json.loads(FEEDS_PATH.read_text(encoding="utf-8"))["feeds"]


def load_pipeline_config_fixture():
    if PIPELINE_CONFIG_PATH.exists():
        return json.loads(PIPELINE_CONFIG_PATH.read_text(encoding="utf-8"))
    return {
        "summary_enrichment": {
            "short_summary_threshold": 80,
            "page_fallback_cap": 300,
        },
        "render": {
            "part1_summary_max_chars": 200,
            "part2_summary_max_chars": 200,
        }
    }


def make_render_config(part1_summary_max_chars: int, part2_summary_max_chars: int):
    return {
        "render": {
            "part1_summary_max_chars": part1_summary_max_chars,
            "part2_summary_max_chars": part2_summary_max_chars,
        }
    }


def make_runtime_config_snapshot(
    *,
    part1_summary_max_chars: int = 200,
    part2_summary_max_chars: int = 200,
    short_summary_threshold: int = 80,
    page_fallback_cap: int = 300,
    effective_page_fallback_cap: int | None = None,
    config_path: Path | None = None,
):
    return {
        "config_path": str((config_path or PIPELINE_CONFIG_PATH).resolve()),
        "summary_enrichment": {
            "short_summary_threshold": short_summary_threshold,
            "page_fallback_cap": page_fallback_cap,
            "effective_page_fallback_cap": (
                page_fallback_cap if effective_page_fallback_cap is None else effective_page_fallback_cap
            ),
        },
        "render": {
            "part1_summary_max_chars": part1_summary_max_chars,
            "part2_summary_max_chars": part2_summary_max_chars,
        },
    }


def load_monitor_module():
    spec = importlib.util.spec_from_file_location("rss_news_monitor", RSS_MONITOR_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError("Failed to import rss_news_monitor.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def load_render_module():
    spec = importlib.util.spec_from_file_location("render_report", RENDER_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError("Failed to import render_report.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def materialize_articles(article_indices):
    samples = load_json_fixture("article_samples.json")["articles"]
    return [dict(samples[index]) for index in article_indices]


def materialize_raw(scenario_name: str):
    feeds = load_feeds()
    scenario = load_json_fixture(scenario_name)
    articles = materialize_articles(scenario["article_indices"])

    counts_by_source = {}
    for article in articles:
        counts_by_source[article["source"]] = counts_by_source.get(article["source"], 0) + 1

    default_status = dict(scenario["default_feed_status"])
    overrides = scenario["feed_overrides"]
    feed_results = []
    for feed in feeds:
        spec = dict(default_status)
        spec.update(overrides.get(feed["name"], {}))
        feed_results.append({
            "source": feed["name"],
            "url": feed["url"],
            "status": spec["status"],
            "error": spec.get("error"),
            "article_count": spec.get("article_count", counts_by_source.get(feed["name"], 0)),
        })

    unique_sources = sorted({article["source"] for article in articles})
    return {
        "meta": {
            "generated_at_utc": "2026-04-10T22:00:00Z",
            "run_id": f"test-{scenario_name}",
            "input_mode": "feeds.json",
            "feed_count_expected": len(feeds),
        },
        "hours": 24,
        "count": len(articles),
        "unique_source_count": len(unique_sources),
        "unique_sources": unique_sources,
        "configured_feed_count": len(feeds),
        "configured_feeds": [feed["name"] for feed in feeds],
        "feed_results": feed_results,
        "runtime_config": make_runtime_config_snapshot(
            short_summary_threshold=load_pipeline_config_fixture()["summary_enrichment"]["short_summary_threshold"],
            page_fallback_cap=load_pipeline_config_fixture()["summary_enrichment"]["page_fallback_cap"],
            effective_page_fallback_cap=load_pipeline_config_fixture()["summary_enrichment"]["page_fallback_cap"],
            part1_summary_max_chars=load_pipeline_config_fixture()["render"]["part1_summary_max_chars"],
            part2_summary_max_chars=load_pipeline_config_fixture()["render"]["part2_summary_max_chars"],
        ),
        "articles": articles,
    }


def run_validator(raw_data):
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        raw_path = tmp / "raw.json"
        raw_path.write_text(json.dumps(raw_data, ensure_ascii=False, indent=2), encoding="utf-8")
        proc = subprocess.run(
            [sys.executable, str(VALIDATE_SCRIPT), "--input", str(raw_path), "--feeds", str(FEEDS_PATH)],
            capture_output=True,
            text=True,
            check=False,
        )
        validation = json.loads(proc.stdout)
        return proc.returncode, validation


def run_renderer(raw_data, validation_data, report_name="rss-report-2026-04-10.md",
                 config_path=None):
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        raw_path = tmp / "raw.json"
        validation_path = tmp / "validation.json"
        output_path = tmp / report_name
        raw_path.write_text(json.dumps(raw_data, ensure_ascii=False, indent=2), encoding="utf-8")
        validation_path.write_text(json.dumps(validation_data, ensure_ascii=False, indent=2), encoding="utf-8")

        command = [
            sys.executable,
            str(RENDER_SCRIPT),
            "--input", str(raw_path),
            "--validation", str(validation_path),
            "--output", str(output_path),
            "--date", "2026-04-10",
        ]
        effective_config_path = config_path
        if effective_config_path is None and PIPELINE_CONFIG_PATH.exists():
            effective_config_path = PIPELINE_CONFIG_PATH
        if effective_config_path is not None:
            command.extend(["--config", str(effective_config_path)])

        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
        )

        rendered_path = Path(proc.stdout.strip())
        rendered_text = rendered_path.read_text(encoding="utf-8") if rendered_path.exists() else ""
        return {
            "returncode": proc.returncode,
            "official_name": output_path.name,
            "official_exists": output_path.exists(),
            "rendered_name": rendered_path.name,
            "rendered_exists": rendered_path.exists(),
            "rendered_text": rendered_text,
        }


def run_llm_context(raw_data, validation_data, report_name="rss-report-2026-04-10.md"):
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        raw_path = tmp / "raw.json"
        validation_path = tmp / "validation.json"
        context_path = tmp / "llm_context.json"
        report_path = tmp / report_name
        raw_path.write_text(json.dumps(raw_data, ensure_ascii=False, indent=2), encoding="utf-8")
        validation_path.write_text(json.dumps(validation_data, ensure_ascii=False, indent=2), encoding="utf-8")

        proc = subprocess.run(
            [
                sys.executable,
                str(LLM_CONTEXT_SCRIPT),
                "--input", str(raw_path),
                "--validation", str(validation_path),
                "--output", str(context_path),
                "--date", "2026-04-10",
                "--report-path", str(report_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )

        context = json.loads(context_path.read_text(encoding="utf-8")) if context_path.exists() else None
        return proc.returncode, context


def parse_markdown(text: str):
    section = None
    part1_count = 0
    group_count = 0
    group_article_total = 0

    for line in text.splitlines():
        if line == "按来源分组":
            section = "part2"
            continue
        if line == "统计检查":
            section = "stats"
            continue
        if line.startswith("=" * 70):
            if section is None:
                section = "part1"
            continue

        if section == "part1" and re.match(r"^\d+\. .+$", line):
            part1_count += 1
        elif section == "part2" and re.match(r"^--- .+ \(\d+篇(?: · 抓取失败)?\) ---$", line):
            group_count += 1
        elif section == "part2" and re.match(r"^\d+\. .+$", line):
            group_article_total += 1

    return part1_count, group_count, group_article_total


class OfflineQCTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.feeds = load_feeds()
        cls.monitor = load_monitor_module()
        cls.render_module = load_render_module()

    def test_validate_success_fixture_returns_zero(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)

        self.assertEqual(code, EXIT_OK)
        self.assertTrue(validation["passed"])
        self.assertEqual(validation["counts"]["configured"], len(self.feeds))
        self.assertEqual(validation["counts"]["results"], len(self.feeds))
        self.assertEqual(validation["counts"]["articles"], 3)
        self.assertEqual(validation["counts"]["ok"], 3)
        self.assertEqual(validation["counts"]["empty"], len(self.feeds) - 3)
        self.assertEqual(validation["counts"]["error"], 0)

    def test_validator_blocks_source_group_contradiction_when_totals_still_match(self):
        raw_data = materialize_raw("golden_success.json")
        for entry in raw_data["feed_results"]:
            if entry["source"] == "Ars Technica":
                entry["status"] = "empty"
                entry["article_count"] = 0
                entry["error"] = None
            elif entry["source"] == "The Verge":
                entry["status"] = "ok"
                entry["article_count"] = 1
                entry["error"] = None

        code, validation = run_validator(raw_data)

        self.assertEqual(code, EXIT_CONTRACT_MISMATCH)
        self.assertFalse(validation["passed"])
        reasons = "\n".join(validation["blocking_reasons"])
        self.assertIn("article_count mismatch for source Ars Technica", reasons)
        self.assertIn("article_count mismatch for source The Verge", reasons)
        self.assertNotIn("sum(article_count) mismatch", reasons)

    def test_validator_requires_status_and_error_state_to_align(self):
        raw_data = materialize_raw("golden_success.json")
        for entry in raw_data["feed_results"]:
            if entry["source"] == "TechCrunch":
                entry["status"] = "ok"
                entry["error"] = "HTTP 500"
                break

        code, validation = run_validator(raw_data)

        self.assertEqual(code, EXIT_CONTRACT_MISMATCH)
        self.assertFalse(validation["passed"])
        self.assertIn(
            "status ok must not include error: TechCrunch",
            "\n".join(validation["blocking_reasons"]),
        )

    def test_partial_failure_marks_failed_sources_but_keeps_formal_report(self):
        raw_data = materialize_raw("partial_failure_mix.json")
        code, validation = run_validator(raw_data)

        self.assertEqual(code, EXIT_OK)
        self.assertTrue(validation["passed"])
        self.assertFalse(validation["blocking_reasons"])
        self.assertEqual(validation["counts"]["blocking_error"], 0)
        self.assertEqual(validation["counts"]["warn_error"], 1)
        self.assertIn("The Verge", " ".join(validation["warnings"]))

        render_result = run_renderer(raw_data, validation)
        self.assertEqual(render_result["returncode"], EXIT_OK)
        self.assertTrue(render_result["official_exists"])
        self.assertTrue(render_result["rendered_exists"])
        self.assertEqual(render_result["rendered_name"], "rss-report-2026-04-10.md")
        self.assertIn("--- The Verge (1篇 · 抓取失败) ---", render_result["rendered_text"])
        self.assertIn("抓取状态: HTTP 503", render_result["rendered_text"])
        self.assertIn("校验结论: 通过", render_result["rendered_text"])

    def test_warn_only_error_feed_does_not_block(self):
        warn_feed = next((feed for feed in self.feeds if feed.get("error_policy") == "warn"), None)
        if warn_feed is None:
            self.skipTest("No warn-only feed configured in feeds.json")

        raw_data = materialize_raw("golden_success.json")
        for entry in raw_data["feed_results"]:
            if entry["source"] == warn_feed["name"]:
                entry["status"] = "error"
                entry["error"] = "HTTP 403"
                entry["article_count"] = 0
                break

        code, validation = run_validator(raw_data)
        self.assertEqual(code, EXIT_OK)
        self.assertTrue(validation["passed"])
        self.assertEqual(validation["counts"]["blocking_error"], 0)
        self.assertEqual(validation["counts"]["warn_error"], 1)
        self.assertIn(warn_feed["name"], " ".join(validation["warnings"]))

    def test_all_empty_but_ok_blocks_formal_report(self):
        raw_data = materialize_raw("all_empty_but_ok.json")
        code, validation = run_validator(raw_data)

        self.assertEqual(code, EXIT_DATA_QUALITY_BLOCK)
        self.assertFalse(validation["passed"])
        self.assertIn("count == 0", validation["blocking_reasons"])
        self.assertEqual(validation["counts"]["empty"], len(self.feeds))
        self.assertEqual(validation["counts"]["error"], 0)

    def test_all_error_blocks_formal_report(self):
        raw_data = materialize_raw("all_error.json")
        code, validation = run_validator(raw_data)
        warn_feed_count = sum(1 for feed in self.feeds if feed.get("error_policy") == "warn")

        self.assertEqual(code, EXIT_DATA_QUALITY_BLOCK)
        self.assertFalse(validation["passed"])
        self.assertIn("count == 0", validation["blocking_reasons"])
        self.assertNotIn(f"error_count > 0 ({len(self.feeds) - warn_feed_count})", validation["blocking_reasons"])
        self.assertEqual(validation["counts"]["error"], len(self.feeds))
        self.assertEqual(validation["counts"]["blocking_error"], 0)
        self.assertEqual(validation["counts"]["warn_error"], len(self.feeds))

    def test_dedup_collision_uses_monitor_logic(self):
        scenario = load_json_fixture("dedup_collision.json")
        raw_articles = materialize_articles(scenario["raw_article_indices"])
        deduped = self.monitor.dedup_articles(raw_articles)

        self.assertEqual(len(raw_articles), 3)
        self.assertEqual(len(deduped), scenario["expected_dedup_count"])
        self.assertEqual([item["source"] for item in deduped], scenario["expected_sources_after_dedup"])

    def test_deterministic_top_articles_preserve_input_order(self):
        """Renderer fallback is pure time-desc: it must not re-rank by signals.

        Top 30 editorial selection lives in `part1-editor` (Claude Code runtime);
        the static renderer only slices the already-sorted article list.
        """
        article_cls = self.render_module.Article
        parse_pub_date = self.render_module.parse_pub_date
        choose_top_articles = self.render_module.choose_top_articles

        articles = [
            article_cls(
                source="TechPowerUp",
                title="(PR) MegaBundle Spring Sale Drops Software Prices Again",
                link="https://example.com/pr-sale",
                pub_date=parse_pub_date("2026-04-10T21:00:00+00:00"),
                summary="Discount bundle for software buyers.",
            ),
            article_cls(
                source="TechCrunch",
                title="Startup raises $650 million to build AI infrastructure",
                link="https://example.com/funding",
                pub_date=parse_pub_date("2026-04-10T20:59:00+00:00"),
                summary="The company said the new round will fund expansion of data centers and hiring.",
            ),
            article_cls(
                source="Ars Technica",
                title="Google introduces new Chrome feature",
                link="https://example.com/feature",
                pub_date=parse_pub_date("2026-04-10T20:58:00+00:00"),
                summary="A regular product feature update.",
            ),
        ]

        ranked = choose_top_articles(articles)
        self.assertEqual([a.link for a in ranked], [a.link for a in articles])

    def test_choose_top_articles_caps_at_top_n(self):
        article_cls = self.render_module.Article
        parse_pub_date = self.render_module.parse_pub_date
        choose_top_articles = self.render_module.choose_top_articles

        articles = [
            article_cls(
                source=f"Source {idx}",
                title=f"Important AI update {idx}",
                link=f"https://example.com/item-{idx}",
                pub_date=parse_pub_date(f"2026-04-10T{idx % 24:02d}:00:00+00:00"),
                summary="A meaningful update for ranking coverage.",
            )
            for idx in range(self.render_module.TOP_N + 7)
        ]

        ranked = choose_top_articles(articles)
        self.assertEqual(len(ranked), self.render_module.TOP_N)

    def test_render_prefers_raw_snapshot_over_config_file(self):
        raw_data = materialize_raw("golden_success.json")
        raw_data["runtime_config"] = make_runtime_config_snapshot(
            part1_summary_max_chars=24,
            part2_summary_max_chars=17,
        )
        code, validation = run_validator(raw_data)
        self.assertEqual(code, EXIT_OK)

        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = Path(tmpdir) / "pipeline_config.json"
            config_path.write_text(
                json.dumps(make_render_config(80, 70), ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            render_result = run_renderer(raw_data, validation, config_path=config_path)

        self.assertEqual(render_result["returncode"], EXIT_OK)
        articles = self.render_module.normalize_articles(raw_data)
        top_article = self.render_module.choose_top_articles(articles)[0]
        expected_part1 = self.render_module.clamp_text(top_article.summary, 24)
        expected_part2 = self.render_module.clamp_text(top_article.summary, 17)
        self.assertIn(f"摘要: {expected_part1}", render_result["rendered_text"])
        self.assertIn(f"摘要: {expected_part2}", render_result["rendered_text"])

    def test_render_legacy_raw_falls_back_to_explicit_config_file(self):
        raw_data = materialize_raw("golden_success.json")
        raw_data.pop("runtime_config", None)
        code, validation = run_validator(raw_data)
        self.assertEqual(code, EXIT_OK)

        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = Path(tmpdir) / "pipeline_config.json"
            config_path.write_text(
                json.dumps(make_render_config(22, 15), ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            render_result = run_renderer(raw_data, validation, config_path=config_path)

        self.assertEqual(render_result["returncode"], EXIT_OK)
        articles = self.render_module.normalize_articles(raw_data)
        top_article = self.render_module.choose_top_articles(articles)[0]
        expected_part1 = self.render_module.clamp_text(top_article.summary, 22)
        expected_part2 = self.render_module.clamp_text(top_article.summary, 15)
        self.assertIn(f"摘要: {expected_part1}", render_result["rendered_text"])
        self.assertIn(f"摘要: {expected_part2}", render_result["rendered_text"])

    def test_render_markdown_matches_golden_fixture(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)
        self.assertEqual(code, EXIT_OK)

        render_result = run_renderer(raw_data, validation)
        expected = load_text_fixture("markdown_render_golden.md")

        self.assertEqual(render_result["returncode"], EXIT_OK)
        self.assertTrue(render_result["official_exists"])
        self.assertTrue(render_result["rendered_exists"])
        self.assertEqual(render_result["rendered_name"], "rss-report-2026-04-10.md")
        self.assertEqual(render_result["rendered_text"], expected)

        part1_count, group_count, group_article_total = parse_markdown(render_result["rendered_text"])
        self.assertEqual(part1_count, 3)
        self.assertEqual(group_count, len(self.feeds))
        self.assertEqual(group_article_total, 3)

    def test_llm_context_contains_all_articles_and_source_groups(self):
        raw_data = materialize_raw("golden_success.json")
        code, validation = run_validator(raw_data)
        self.assertEqual(code, EXIT_OK)

        ctx_code, context = run_llm_context(raw_data, validation)
        self.assertEqual(ctx_code, EXIT_OK)
        self.assertIsNotNone(context)
        self.assertTrue(context["validation"]["passed"])
        self.assertEqual(len(context["source_groups"]), len(self.feeds))
        self.assertEqual(len(context["all_articles"]), 3)
        self.assertTrue(context["meta"]["report_path"].endswith("rss-report-2026-04-10.md"))
        # all_articles is time-desc; no scoring fields are emitted
        self.assertEqual(
            context["all_articles"][0]["title"],
            "The Latest Foldable iPhone Rumors: What's Changed and What We Know Now",
        )
        self.assertNotIn("heuristic_score", context["all_articles"][0])
        self.assertNotIn("audit_flags", context["all_articles"][0])
        self.assertNotIn("amount_millions", context["all_articles"][0])
        self.assertNotIn("candidate_articles", context)
        # article_text is a best-effort field; may be empty but must be present
        self.assertIn("article_text", context["all_articles"][0])

    def test_invalid_legacy_raw_contract_returns_10(self):
        legacy_raw = {
            "hours": 24,
            "count": 1,
            "articles": materialize_articles([0]),
        }
        code, validation = run_validator(legacy_raw)
        self.assertEqual(code, EXIT_INPUT_DAMAGED)
        self.assertFalse(validation["passed"])
        self.assertIn("raw.json.meta", validation["blocking_reasons"][0])


if __name__ == "__main__":
    unittest.main()
