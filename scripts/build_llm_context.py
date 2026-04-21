#!/usr/bin/env python3
"""Build a compact LLM-oriented context from raw + validation artifacts."""

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

import render_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build llm_context.json from raw.json and validation.json."
    )
    parser.add_argument("--input", required=True, help="Path to raw.json")
    parser.add_argument("--validation", required=True, help="Path to validation.json")
    parser.add_argument("--output", required=True, help="Path to llm_context.json")
    parser.add_argument("--date", help="Optional YYYY-MM-DD override")
    parser.add_argument("--report-path", help="Optional final markdown output path")
    parser.add_argument("--candidate-limit", type=int, default=90,
                        help="Maximum number of ranked candidate articles")
    return parser


def load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def normalized_article_payload(article: render_report.Article) -> Dict[str, Any]:
    text = render_report._normalized_text(article)
    amount_m = render_report._extract_max_amount_millions(text)
    has_major_company = render_report._contains_any(text, render_report.MAJOR_COMPANIES)
    has_business_signal = render_report._contains_any(text, render_report.BUSINESS_PATTERNS)
    has_security_signal = render_report._contains_any(text, render_report.SECURITY_PATTERNS)
    has_breakthrough_signal = render_report._contains_any(text, render_report.BREAKTHROUGH_PATTERNS)
    has_launch_signal = render_report._contains_any(text, render_report.LAUNCH_PATTERNS)
    has_speculation = render_report._contains_any(text, render_report.SPECULATION_PATTERNS)
    has_noise = render_report._contains_any(text, render_report.NOISE_PATTERNS)
    has_hard_noise = render_report._contains_any(text, render_report.HARD_NOISE_PATTERNS)

    flags: List[str] = []
    if has_major_company:
        flags.append("major_company")
    if has_business_signal:
        flags.append("business_signal")
    if has_security_signal:
        flags.append("security_signal")
    if has_breakthrough_signal:
        flags.append("breakthrough_signal")
    if has_launch_signal:
        flags.append("launch_signal")
    if has_speculation:
        flags.append("speculation")
    if has_noise:
        flags.append("noise")
    if has_hard_noise:
        flags.append("hard_noise")
    if amount_m >= 100.0:
        flags.append("funding_or_deal_ge_100m")

    return {
        "source": article.source,
        "title": article.title,
        "link": article.link,
        "pub_date_utc": render_report.format_utc(article.pub_date),
        "pub_date_iso": article.pub_date.astimezone(render_report.timezone.utc).isoformat(),
        "summary_en": article.summary,
        "heuristic_score": round(render_report.score_article(article), 2),
        "audit_flags": flags,
        "amount_millions": round(amount_m, 2),
    }


def build_context(raw: Dict[str, Any], validation: Dict[str, Any], date_str: str,
                  candidate_limit: int, report_path: Optional[str]) -> Dict[str, Any]:
    articles = render_report.normalize_articles(raw)
    grouped = render_report.group_articles(articles)
    groups = render_report.normalize_source_groups(raw, validation, articles)

    article_payloads = [normalized_article_payload(article) for article in articles]
    ranked_articles = sorted(
        article_payloads,
        key=lambda item: (item["heuristic_score"], item["pub_date_iso"]),
        reverse=True,
    )

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
            "articles": [normalized_article_payload(article) for article in grouped.get(group.name, [])],
        })

    return {
        "meta": {
            "date": date_str,
            "generated_at_utc": render_report.as_dict(validation.get("meta")).get("generated_at_utc")
            or render_report.as_dict(raw.get("meta")).get("generated_at_utc"),
            "run_id": render_report.as_dict(validation.get("meta")).get("run_id")
            or render_report.as_dict(raw.get("meta")).get("run_id"),
            "report_path": report_path or "",
        },
        "validation": {
            "passed": validation.get("passed") is True,
            "blocking_reasons": validation.get("blocking_reasons", []),
            "warnings": validation.get("warnings", []),
            "counts": validation.get("counts", {}),
            "policy": validation.get("policy", {}),
        },
        "candidate_articles": ranked_articles[:max(candidate_limit, 0)],
        "all_articles": article_payloads,
        "source_groups": source_groups,
    }


def main() -> int:
    args = build_parser().parse_args()
    try:
        raw = load_json(args.input)
        validation = load_json(args.validation)
        date_str = args.date or render_report.report_date(None, args.output, raw, validation)
        context = build_context(raw, validation, date_str, args.candidate_limit, args.report_path)
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(context, ensure_ascii=False, indent=2), encoding="utf-8")
        print(str(output_path))
        return 0
    except Exception as exc:
        print(f"Failed to build llm context: {exc}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    raise SystemExit(main())
