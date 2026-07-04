from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tests.test_qc_offline import (
    LLM_CONTEXT_SCRIPT,
    materialize_raw,
    run_validator,
)


ROOT = Path(__file__).resolve().parents[1]
EDITORIAL_RUNTIME_SCRIPT = ROOT / "scripts" / "editorial_runtime.py"


def _write_json(path: Path, payload) -> Path:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


class EditorialRuntimeTests(unittest.TestCase):
    def _build_artifacts(self, tmp: Path):
        raw = materialize_raw("golden_success.json")
        code, validation = run_validator(raw)
        self.assertEqual(code, 0)

        raw_path = _write_json(tmp / "raw.json", raw)
        validation_path = _write_json(tmp / "validation.json", validation)
        context_path = tmp / "llm_context.json"
        proc = subprocess.run(
            [
                sys.executable,
                str(LLM_CONTEXT_SCRIPT),
                "--input", str(raw_path),
                "--validation", str(validation_path),
                "--output", str(context_path),
                "--date", "2026-04-10",
                "--report-path", str(tmp / "rss-report-2026-04-10.md"),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return raw, validation, context_path, validation_path

    def _handoffs(self, context):
        articles_by_link = {article["link"]: article for article in context["all_articles"]}
        # part1_plan is link-keyed: rank is implicit in array order and the
        # authoritative title/source/timestamp are joined at assemble time.
        part1_items = []
        for rank, article in enumerate(context["all_articles"][:2], 1):
            part1_items.append({
                "link": article["link"],
                "summary_zh": f"中文摘要 {rank}",
                "also_links": [],
                "noise_bucket": "selected",
                "event_key": f"event-{rank}",
            })

        groups = []
        for group in context["source_groups"]:
            articles = []
            for ref in group["article_refs"]:
                article = articles_by_link[ref["link"]]
                articles.append({
                    "title": ref["title"],
                    "link": ref["link"],
                    "pub_date_iso": ref["pub_date_iso"],
                    "summary_zh": f"{article['source']} 的中文摘要",
                })
            groups.append({
                "source": group["source"],
                "status": group["status"],
                "article_count": len(articles),
                "error_text": None,
                "articles": articles,
            })

        return (
            {"items": part1_items, "shortfall": 28, "notes": []},
            {"groups": groups, "total_articles": len(context["all_articles"])},
        )

    def test_llm_context_sidecars_and_source_group_refs_are_compact(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            _raw, _validation, context_path, _validation_path = self._build_artifacts(tmp)
            context = json.loads(context_path.read_text(encoding="utf-8"))
            part2_context = json.loads((tmp / "part2_context.json").read_text(encoding="utf-8"))
            context_budget = json.loads((tmp / "context_budget.json").read_text(encoding="utf-8"))

            self.assertTrue((tmp / "part1_brief.json").exists())
            self.assertTrue((tmp / "part2_context.json").exists())
            self.assertTrue((tmp / "context_budget.json").exists())
            self.assertIn("article_refs", context["source_groups"][0])
            self.assertNotIn("articles", context["source_groups"][0])
            for group in context["source_groups"]:
                for ref in group["article_refs"]:
                    self.assertNotIn("article_text", ref)
                    self.assertNotIn("summary_en", ref)
            for group in part2_context["groups"]:
                for article in group["articles"]:
                    self.assertIn("summary_material", article)
                    self.assertTrue(article["needs_summary"])
                    self.assertEqual(article["cache_status"], "miss")
                    self.assertNotIn("article_text", article)
            self.assertIn("sizes", context_budget)
            self.assertIn("within_budget", context_budget)
            self.assertNotIn("recommended_strategy", context_budget)

    def test_part2_context_uses_article_text_only_for_short_summary_fallback(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            raw = materialize_raw("golden_success.json")
            raw["articles"][0]["summary_en"] = "tiny"
            raw["articles"][0]["article_text"] = " ".join(f"body{idx}" for idx in range(100))
            code, validation = run_validator(raw)
            self.assertEqual(code, 0)

            raw_path = _write_json(tmp / "raw.json", raw)
            validation_path = _write_json(tmp / "validation.json", validation)
            context_path = tmp / "llm_context.json"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(LLM_CONTEXT_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(context_path),
                    "--date", "2026-04-10",
                    "--report-path", str(tmp / "rss-report-2026-04-10.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            part2_context = json.loads((tmp / "part2_context.json").read_text(encoding="utf-8"))

            fallback_articles = [
                article
                for group in part2_context["groups"]
                for article in group["articles"]
                if article["link"] == raw["articles"][0]["link"]
            ]
            self.assertEqual(len(fallback_articles), 1)
            self.assertEqual(fallback_articles[0]["summary_source"], "article_text_fallback")
            self.assertLessEqual(len(fallback_articles[0]["summary_material"].split()), 61)

    def test_context_budget_reports_violations(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            raw = materialize_raw("golden_success.json")
            raw["runtime_config"]["context_budget"] = {
                "llm_context_max_bytes": 1,
                "part1_brief_max_bytes": 1,
                "part2_context_max_bytes": 1,
                "total_context_max_bytes": 1,
            }
            code, validation = run_validator(raw)
            self.assertEqual(code, 0)

            raw_path = _write_json(tmp / "raw.json", raw)
            validation_path = _write_json(tmp / "validation.json", validation)
            context_path = tmp / "llm_context.json"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(LLM_CONTEXT_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(context_path),
                    "--date", "2026-04-10",
                    "--report-path", str(tmp / "rss-report-2026-04-10.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            budget = json.loads((tmp / "context_budget.json").read_text(encoding="utf-8"))
            self.assertFalse(budget["within_budget"])
            self.assertGreaterEqual(len(budget["violations"]), 1)

    def test_part2_context_uses_cache_and_merge_part2_requires_only_missing_summaries(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            run_dir = tmp / "runs" / "2026-04-10"
            run_dir.mkdir(parents=True)
            raw = materialize_raw("golden_success.json")
            code, validation = run_validator(raw)
            self.assertEqual(code, 0)
            cached_article = raw["articles"][0]
            # Compute the key with the real cache helper so this test follows
            # the implementation's keying scheme (stable link-based v2 key)
            # instead of duplicating the formula.
            sys.path.insert(0, str(ROOT / "scripts"))
            from _common.editorial_cache import cache_key as _cache_key
            cache_key = _cache_key(cached_article)
            cache_path = tmp / "runs" / "_cache" / "editorial_cache.json"
            cache_path.parent.mkdir(parents=True)
            cache_path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "entries": {
                            cache_key: {
                                "link": cached_article["link"],
                                "source": cached_article["source"],
                                "title": cached_article["title"],
                                "summary_zh": "缓存里的中文摘要",
                                "noise_bucket": "covered",
                                "event_key": "cached-event",
                            }
                        },
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

            raw_path = _write_json(run_dir / "raw.json", raw)
            validation_path = _write_json(run_dir / "validation.json", validation)
            context_path = run_dir / "llm_context.json"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(LLM_CONTEXT_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(context_path),
                    "--date", "2026-04-10",
                    "--report-path", str(tmp / "rss-report-2026-04-10.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            part2_context_path = run_dir / "part2_context.json"
            part2_context = json.loads(part2_context_path.read_text(encoding="utf-8"))
            cached_entries = [
                article
                for group in part2_context["groups"]
                for article in group["articles"]
                if article["link"] == cached_article["link"]
            ]
            self.assertEqual(len(cached_entries), 1)
            self.assertFalse(cached_entries[0]["needs_summary"])
            self.assertEqual(cached_entries[0]["summary_zh"], "缓存里的中文摘要")

            missing_items = [
                {
                    "link": article["link"],
                    "summary_zh": f"新摘要 {idx}",
                }
                for idx, group in enumerate(part2_context["groups"], 1)
                for article in group["articles"]
                if article.get("needs_summary") is True
            ]
            missing_path = _write_json(run_dir / "part2_missing_summaries.json", {"items": missing_items})
            merged_path = run_dir / "part2_draft.json"
            merge = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "merge-part2",
                    "--part2-context", str(part2_context_path),
                    "--missing", str(missing_path),
                    "--output", str(merged_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(merge.returncode, 0, merge.stderr)
            merged = json.loads(merged_path.read_text(encoding="utf-8"))
            all_summaries = [
                article["summary_zh"]
                for group in merged["groups"]
                for article in group["articles"]
            ]
            self.assertIn("缓存里的中文摘要", all_summaries)
            self.assertEqual(merged["total_articles"], len(raw["articles"]))

    def test_audit_shortlist_assemble_review_and_cache(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            _raw, _validation, context_path, validation_path = self._build_artifacts(tmp)
            context = json.loads(context_path.read_text(encoding="utf-8"))
            part1, part2 = self._handoffs(context)
            part1_path = _write_json(tmp / "part1_plan.json", part1)
            part2_path = _write_json(tmp / "part2_draft.json", part2)

            audit = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "audit",
                    "--llm-context", str(context_path),
                    "--validation", str(validation_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(audit.returncode, 0, audit.stderr)

            shortlist_path = _write_json(
                tmp / "part1_shortlist.json",
                {"links": [context["all_articles"][0]["link"]]},
            )
            shortlist_context_path = tmp / "part1_shortlist_context.json"
            shortlist = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "shortlist-context",
                    "--llm-context", str(context_path),
                    "--shortlist", str(shortlist_path),
                    "--output", str(shortlist_context_path),
                    "--no-cache",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(shortlist.returncode, 0, shortlist.stderr)
            shortlist_context = json.loads(shortlist_context_path.read_text(encoding="utf-8"))
            self.assertEqual(shortlist_context["article_count"], 1)
            self.assertEqual(shortlist_context["cache_hits"], 0)

            report_path = tmp / "rss-report-2026-04-10.md"
            cache_path = tmp / "editorial_cache.json"
            assemble = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "assemble",
                    "--llm-context", str(context_path),
                    "--validation", str(validation_path),
                    "--part1", str(part1_path),
                    "--part2", str(part2_path),
                    "--output", str(report_path),
                    "--cache-path", str(cache_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(assemble.returncode, 0, assemble.stderr)
            self.assertTrue(report_path.exists())
            self.assertTrue(cache_path.exists())
            report_text = report_path.read_text(encoding="utf-8")
            self.assertIn("# DailyNews · 2026-04-10", report_text)
            # Titles / sources come from llm_context via the link join, not
            # from the link-keyed part1 plan.
            first_article = context["all_articles"][0]
            self.assertIn(first_article["title"], report_text)
            self.assertIn(f"来源：{first_article['source']}", report_text)

            review = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "review",
                    "--llm-context", str(context_path),
                    "--validation", str(validation_path),
                    "--part1", str(part1_path),
                    "--part2", str(part2_path),
                    "--report", str(report_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(review.returncode, 0, review.stderr)
            self.assertTrue(json.loads(review.stdout)["passed"])
            cache = json.loads(cache_path.read_text(encoding="utf-8"))
            self.assertGreaterEqual(len(cache["entries"]), len(context["all_articles"]))
            # Part 1 and Part 2 summaries land in separate cache slots so the
            # long event summary never replays into the short Part 2 slot.
            sys.path.insert(0, str(ROOT / "scripts"))
            from _common.editorial_cache import cache_key as _cache_key
            top_entry = cache["entries"][_cache_key(context["all_articles"][0])]
            self.assertEqual(top_entry["part1_summary_zh"], "中文摘要 1")
            self.assertTrue(top_entry["summary_zh"].endswith("的中文摘要"))

    def test_part1_brief_carries_preview_only_for_short_summaries(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            raw = materialize_raw("golden_success.json")
            raw["articles"][0]["summary_en"] = "tiny"
            raw["articles"][0]["article_text"] = " ".join(f"body{idx}" for idx in range(100))
            code, validation = run_validator(raw)
            self.assertEqual(code, 0)

            raw_path = _write_json(tmp / "raw.json", raw)
            validation_path = _write_json(tmp / "validation.json", validation)
            context_path = tmp / "llm_context.json"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(LLM_CONTEXT_SCRIPT),
                    "--input", str(raw_path),
                    "--validation", str(validation_path),
                    "--output", str(context_path),
                    "--date", "2026-04-10",
                    "--report-path", str(tmp / "rss-report-2026-04-10.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            brief = json.loads((tmp / "part1_brief.json").read_text(encoding="utf-8"))
            threshold = raw["runtime_config"]["summary_enrichment"]["short_summary_threshold"]
            self.assertEqual(
                brief["preview_policy"]["short_summary_threshold"], threshold
            )
            raw_by_link = {article["link"]: article for article in raw["articles"]}
            with_preview = 0
            for article in brief["articles"]:
                source_article = raw_by_link[article["link"]]
                summary = " ".join((source_article.get("summary_en") or "").split())
                has_text = bool((source_article.get("article_text") or "").strip())
                expected = (not summary or len(summary) < threshold) and has_text
                self.assertEqual(
                    "article_text_preview" in article,
                    expected,
                    msg=f"unexpected preview presence for {article['link']}",
                )
                with_preview += int("article_text_preview" in article)
            self.assertGreaterEqual(with_preview, 1)
            self.assertLess(with_preview, len(brief["articles"]))

    def test_shortlist_context_injects_part1_cache_hits(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            _raw, _validation, context_path, _validation_path = self._build_artifacts(tmp)
            context = json.loads(context_path.read_text(encoding="utf-8"))
            sys.path.insert(0, str(ROOT / "scripts"))
            from _common.editorial_cache import cache_key as _cache_key

            part1_cached, part2_only = context["all_articles"][:2]
            cache_path = _write_json(tmp / "editorial_cache.json", {
                "version": 1,
                "entries": {
                    _cache_key(part1_cached): {
                        "link": part1_cached["link"],
                        "part1_summary_zh": "昨日的事件级摘要",
                        "event_key": "cached-event",
                        "summary_zh": "昨日的短摘要",
                    },
                    # part2-style flat entry must NOT inject into Part 1.
                    _cache_key(part2_only): {
                        "link": part2_only["link"],
                        "summary_zh": "只有 Part 2 摘要",
                    },
                },
            })
            shortlist_path = _write_json(
                tmp / "part1_shortlist.json",
                {"links": [part1_cached["link"], part2_only["link"]]},
            )
            output_path = tmp / "part1_shortlist_context.json"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(EDITORIAL_RUNTIME_SCRIPT),
                    "shortlist-context",
                    "--llm-context", str(context_path),
                    "--shortlist", str(shortlist_path),
                    "--output", str(output_path),
                    "--cache-path", str(cache_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            payload = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(payload["cache_hits"], 1)
            by_link = {article["link"]: article for article in payload["articles"]}
            hit = by_link[part1_cached["link"]]
            self.assertEqual(hit["cached_summary_zh"], "昨日的事件级摘要")
            self.assertEqual(hit["cached_event_key"], "cached-event")
            self.assertNotIn("cached_summary_zh", by_link[part2_only["link"]])


if __name__ == "__main__":
    unittest.main()
