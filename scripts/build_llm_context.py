#!/usr/bin/env python3
"""Build a compact LLM-oriented context from raw + validation artifacts."""

import argparse
import json
import sys
from datetime import timezone
from pathlib import Path
from typing import Any, Dict, Optional

# Make ``scripts/`` importable when this file is launched directly or imported
# via ``importlib`` in tests.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from _common.editorial import (  # noqa: E402
    Article,
    PART2_SUMMARY_HARD_CAP,
    as_dict,
    format_utc,
    group_articles,
    normalize_articles,
    normalize_source_groups,
    normalized_article_payload,
    report_date,
    summary_lint_errors,
)
from _common.editorial_cache import (  # noqa: E402
    default_cache_path,
    load_cache,
    lookup_entry,
)
from _common.runtime_config import (  # noqa: E402
    DEFAULT_LLM_CONTEXT_MAX_BYTES,
    DEFAULT_PART1_BRIEF_MAX_BYTES,
    DEFAULT_PART2_CONTEXT_MAX_BYTES,
    DEFAULT_TOTAL_CONTEXT_MAX_BYTES,
)


PART1_BRIEF_ARTICLE_TEXT_WORDS = 70
PART2_FALLBACK_ARTICLE_TEXT_WORDS = 60

# runs/_feedback.md is an optional, user-maintained taste log ("this pick was
# noise", "we missed X"). The most recent lines ride into part1_brief.json so
# the editor can calibrate without anyone editing the agent prompt.
FEEDBACK_MAX_LINES = 20
FEEDBACK_MAX_LINE_CHARS = 200


def load_feedback_lines(path: Path) -> list:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return []
    lines = [line.strip() for line in text.splitlines()]
    lines = [line for line in lines if line and not line.startswith("#")]
    return [line[:FEEDBACK_MAX_LINE_CHARS] for line in lines[-FEEDBACK_MAX_LINES:]]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build llm_context.json from raw.json and validation.json."
    )
    parser.add_argument("--input", required=True, help="Path to raw.json")
    parser.add_argument("--validation", required=True, help="Path to validation.json")
    parser.add_argument("--output", required=True, help="Path to llm_context.json")
    parser.add_argument("--date", help="Optional YYYY-MM-DD override")
    parser.add_argument("--report-path", help="Optional final markdown output path")
    return parser


def load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def _clip_words(text: str, max_words: int) -> str:
    words = " ".join((text or "").split()).split()
    if max_words <= 0 or len(words) <= max_words:
        return " ".join(words)
    return " ".join(words[:max_words]) + "..."


def _config_short_summary_threshold(raw: Dict[str, Any]) -> int:
    runtime_config = as_dict(raw.get("runtime_config"))
    summary_config = as_dict(runtime_config.get("summary_enrichment"))
    value = summary_config.get("short_summary_threshold")
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    return 80


def article_ref_payload(article: Article) -> Dict[str, Any]:
    return {
        "title": article.title,
        "link": article.link,
        "pub_date_iso": article.pub_date.astimezone(timezone.utc).isoformat(),
    }


def part1_brief_article_payload(article: Article,
                                short_summary_threshold: int) -> Dict[str, Any]:
    payload = {
        "source": article.source,
        "title": article.title,
        "link": article.link,
        "pub_date_utc": format_utc(article.pub_date),
        "pub_date_iso": article.pub_date.astimezone(timezone.utc).isoformat(),
        "summary_en": article.summary,
    }
    # First-pass shortlisting works from title + source + summary_en; the body
    # preview is only needed as a fallback signal when the feed summary is
    # missing or too short. Skipping it otherwise roughly halves the brief.
    summary = " ".join((article.summary or "").split())
    if not summary or len(summary) < short_summary_threshold:
        preview = _clip_words(article.article_text, PART1_BRIEF_ARTICLE_TEXT_WORDS)
        if preview:
            payload["article_text_preview"] = preview
    return payload


def part2_summary_material(article: Article,
                           short_summary_threshold: int,
                           cache: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    article_payload = normalized_article_payload(article)
    cache_entry = lookup_entry(article_payload, cache)
    if cache_entry and str(cache_entry.get("summary_zh", "")).strip():
        cached_summary = str(cache_entry.get("summary_zh", ""))
        # Injection guard: a cached summary that would fail the assemble lint
        # (over the Part 2 hard cap or carrying links — e.g. a legacy or
        # hand-edited entry) must not ride in with needs_summary=False. The
        # drafter only rewrites needs_summary=True articles, so an unguarded
        # bad hit would deterministically block assemble with no agent allowed
        # to fix it. Demote to a normal miss instead.
        lint = summary_lint_errors(cached_summary, article.link, PART2_SUMMARY_HARD_CAP)
        if not lint:
            return {
                "summary_zh": cached_summary,
                "cache_status": "hit",
                "needs_summary": False,
                "summary_source": "cache",
                "event_key": str(cache_entry.get("event_key", "")),
                "noise_bucket": str(cache_entry.get("noise_bucket", "covered")),
            }
        print(
            f"WARN: cached part2 summary fails lint, demoted to miss: {'; '.join(lint)}",
            file=sys.stderr,
        )

    summary = " ".join((article.summary or "").split())
    if summary and len(summary) >= short_summary_threshold:
        return {
            "summary_material": summary,
            "summary_source": "summary_en",
            "cache_status": "miss",
            "needs_summary": True,
        }
    article_text = _clip_words(article.article_text, PART2_FALLBACK_ARTICLE_TEXT_WORDS)
    if article_text:
        return {
            "summary_material": article_text,
            "summary_source": "article_text_fallback",
            "cache_status": "miss",
            "needs_summary": True,
        }
    if summary:
        return {
            "summary_material": summary,
            "summary_source": "short_summary_en",
            "cache_status": "miss",
            "needs_summary": True,
        }
    return {
        "summary_material": "",
        "summary_source": "empty",
        "cache_status": "miss",
        "needs_summary": True,
    }


def _context_meta(raw: Dict[str, Any],
                  validation: Dict[str, Any],
                  date_str: str,
                  report_path: Optional[str]) -> Dict[str, Any]:
    return {
        "date": date_str,
        "generated_at_utc": as_dict(validation.get("meta")).get("generated_at_utc")
        or as_dict(raw.get("meta")).get("generated_at_utc"),
        "run_id": as_dict(validation.get("meta")).get("run_id")
        or as_dict(raw.get("meta")).get("run_id"),
        "report_path": report_path or "",
    }


def build_context(raw: Dict[str, Any], validation: Dict[str, Any], date_str: str,
                  report_path: Optional[str]) -> Dict[str, Any]:
    articles = normalize_articles(raw)
    grouped = group_articles(articles)
    groups = normalize_source_groups(raw, validation, articles)

    article_payloads = [normalized_article_payload(article) for article in articles]
    meta = _context_meta(raw, validation, date_str, report_path)

    source_groups = []
    for group in groups:
        source_groups.append({
            "source": group.name,
            "url": group.url,
            "status": next(
                (
                    entry.get("status")
                    for entry in validation.get("feed_results", [])
                    if isinstance(entry, dict) and entry.get("source") == group.name
                ),
                None,
            ),
            "article_count": len(grouped.get(group.name, [])),
            "article_refs": [
                article_ref_payload(article)
                for article in grouped.get(group.name, [])
            ],
        })

    return {
        "meta": meta,
        "validation": {
            "passed": validation.get("passed") is True,
            "blocking_reasons": validation.get("blocking_reasons", []),
            "warnings": validation.get("warnings", []),
            "counts": validation.get("counts", {}),
            "policy": validation.get("policy", {}),
        },
        "all_articles": article_payloads,
        "source_groups": source_groups,
    }


def build_part1_brief(raw: Dict[str, Any],
                      validation: Dict[str, Any],
                      date_str: str,
                      report_path: Optional[str],
                      feedback_lines: Optional[list] = None) -> Dict[str, Any]:
    articles = normalize_articles(raw)
    short_summary_threshold = _config_short_summary_threshold(raw)
    brief = {
        "meta": _context_meta(raw, validation, date_str, report_path),
        "article_count": len(articles),
        "article_text_preview_words": PART1_BRIEF_ARTICLE_TEXT_WORDS,
        "preview_policy": {
            "short_summary_threshold": short_summary_threshold,
            "included_when": "summary_en_missing_or_shorter_than_threshold",
        },
        "articles": [
            part1_brief_article_payload(article, short_summary_threshold)
            for article in articles
        ],
    }
    if feedback_lines:
        brief["editor_feedback"] = list(feedback_lines)
    return brief


def build_part2_context(raw: Dict[str, Any],
                        validation: Dict[str, Any],
                        date_str: str,
                        report_path: Optional[str],
                        cache_path: Optional[str | Path] = None) -> Dict[str, Any]:
    articles = normalize_articles(raw)
    grouped = group_articles(articles)
    groups = normalize_source_groups(raw, validation, articles)
    short_summary_threshold = _config_short_summary_threshold(raw)
    cache = load_cache(cache_path) if cache_path else None
    feed_errors = {
        str(entry.get("source")): str(entry.get("error") or "")
        for entry in validation.get("feed_results", [])
        if isinstance(entry, dict)
    }

    source_groups = []
    for group in groups:
        article_entries = []
        for article in grouped.get(group.name, []):
            article_entries.append({
                "title": article.title,
                "link": article.link,
                "pub_date_utc": format_utc(article.pub_date),
                "pub_date_iso": article.pub_date.astimezone(timezone.utc).isoformat(),
                **part2_summary_material(article, short_summary_threshold, cache),
            })
        source_groups.append({
            "source": group.name,
            "url": group.url,
            "status": group.status,
            "article_count": len(article_entries),
            "error_text": feed_errors.get(group.name) or None,
            "articles": article_entries,
        })

    return {
        "meta": _context_meta(raw, validation, date_str, report_path),
        "summary_policy": {
            "prefer": "summary_en",
            "fallback": "article_text",
            "short_summary_threshold": short_summary_threshold,
            "article_text_fallback_words": PART2_FALLBACK_ARTICLE_TEXT_WORDS,
        },
        "total_articles": len(articles),
        "cache": {
            "path": str(cache_path) if cache_path else "",
            "hits": sum(
                1
                for group in source_groups
                for article in group["articles"]
                if article.get("cache_status") == "hit"
            ),
            "misses": sum(
                1
                for group in source_groups
                for article in group["articles"]
                if article.get("needs_summary") is True
            ),
        },
        "groups": source_groups,
    }


def _budget_limits(raw: Dict[str, Any]) -> Dict[str, int]:
    config = as_dict(as_dict(raw.get("runtime_config")).get("context_budget"))
    defaults = {
        "llm_context_max_bytes": DEFAULT_LLM_CONTEXT_MAX_BYTES,
        "part1_brief_max_bytes": DEFAULT_PART1_BRIEF_MAX_BYTES,
        "part2_context_max_bytes": DEFAULT_PART2_CONTEXT_MAX_BYTES,
        "total_context_max_bytes": DEFAULT_TOTAL_CONTEXT_MAX_BYTES,
    }
    limits = {}
    for key, default in defaults.items():
        value = config.get(key)
        limits[key] = value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else default
    return limits


def build_context_budget(raw: Dict[str, Any],
                         context: Dict[str, Any],
                         part1_brief: Dict[str, Any],
                         part2_context: Dict[str, Any],
                         sizes: Dict[str, int]) -> Dict[str, Any]:
    limits = _budget_limits(raw)
    total_size = (
        sizes.get("llm_context_bytes", 0)
        + sizes.get("part1_brief_bytes", 0)
        + sizes.get("part2_context_bytes", 0)
    )
    sizes = dict(sizes)
    sizes["total_context_bytes"] = total_size
    checks = [
        ("llm_context_bytes", "llm_context_max_bytes"),
        ("part1_brief_bytes", "part1_brief_max_bytes"),
        ("part2_context_bytes", "part2_context_max_bytes"),
        ("total_context_bytes", "total_context_max_bytes"),
    ]
    violations = [
        {
            "size": size_key,
            "actual": sizes[size_key],
            "limit": limits[limit_key],
        }
        for size_key, limit_key in checks
        if sizes.get(size_key, 0) > limits[limit_key]
    ]
    per_source = [
        {
            "source": group.get("source"),
            "article_count": group.get("article_count", 0),
        }
        for group in context.get("source_groups", [])
        if isinstance(group, dict)
    ]
    missing_part2 = int(as_dict(part2_context.get("cache")).get("misses") or 0)
    return {
        "meta": context.get("meta", {}),
        "limits": limits,
        "sizes": sizes,
        "counts": {
            "articles": len(context.get("all_articles", [])),
            "sources": len(context.get("source_groups", [])),
            "part2_cache_hits": int(as_dict(part2_context.get("cache")).get("hits") or 0),
            "part2_missing_summaries": missing_part2,
        },
        "per_source": per_source,
        "within_budget": not violations,
        "violations": violations,
    }


def write_json_payload(path: Path, payload: Dict[str, Any]) -> int:
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    path.write_text(text, encoding="utf-8")
    return len(text.encode("utf-8"))


def main() -> int:
    args = build_parser().parse_args()
    try:
        raw = load_json(args.input)
        validation = load_json(args.validation)
        date_str = report_date(args.date, args.output, raw, validation)
        context = build_context(raw, validation, date_str, args.report_path)
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        feedback_lines = load_feedback_lines(output_path.parent.parent / "_feedback.md")
        part1_brief = build_part1_brief(
            raw, validation, date_str, args.report_path,
            feedback_lines=feedback_lines,
        )
        cache_path = default_cache_path(output_path)
        part2_context = build_part2_context(
            raw,
            validation,
            date_str,
            args.report_path,
            cache_path=cache_path,
        )
        sizes = {
            "llm_context_bytes": write_json_payload(output_path, context),
            "part1_brief_bytes": write_json_payload(output_path.parent / "part1_brief.json", part1_brief),
            "part2_context_bytes": write_json_payload(output_path.parent / "part2_context.json", part2_context),
        }
        write_json_payload(
            output_path.parent / "context_budget.json",
            build_context_budget(raw, context, part1_brief, part2_context, sizes),
        )
        print(str(output_path))
        return 0
    except Exception as exc:
        print(f"Failed to build llm context: {exc}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    raise SystemExit(main())
