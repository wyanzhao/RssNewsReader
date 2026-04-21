#!/usr/bin/env python3
"""
Render RSS daily report from raw.json + validation.json.

This script is intentionally read-only with respect to the source data:
- It does not fetch from the network.
- It does not validate completeness.
- It only renders markdown and chooses the final output path based on
  validation.passed.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence


TOP_N = 30
LINE = "=" * 70
MAJOR_COMPANIES = (
    "apple", "google", "nvidia", "openai", "microsoft", "anthropic",
    "meta", "amazon", "amd", "intel", "tesla", "waymo", "cloudflare",
)
NOISE_PATTERNS = (
    "giveaway", "deal", "discount", "sale", "how to watch", "hands-on",
    "roundup", "rumor", "rumors", "recap", "preview", "pre-order",
)
HARD_NOISE_PATTERNS = (
    "(pr)", "sponsored", "advertisement",
)
SPECULATION_PATTERNS = (
    "reportedly", "rumor", "rumors", "leak", "leaks", "claims", "claim",
    "said to", "says report",
)
BUSINESS_PATTERNS = (
    "raise", "raises", "raised", "funding", "valuation", "acquire",
    "acquires", "acquisition", "merger", "merges", "antitrust",
    "regulation", "regulatory", "export control", "ban", "fine",
    "penalty", "approval", "approved", "lawsuit", "sues",
)
LAUNCH_PATTERNS = (
    "launch", "launches", "launched", "introduces", "announces",
    "announced", "release", "releases", "released", "unveils",
    "ships", "shipping", "rolls out",
)
SECURITY_PATTERNS = (
    "breach", "breached", "vulnerability", "vulnerabilities", "exploit",
    "backdoor", "backdoors", "malware", "ransomware", "privacy", "stole",
    "stolen", "hack", "hacked", "data exposure", "zero-day", "zero day",
    "data leak", "leaked data",
)
BREAKTHROUGH_PATTERNS = (
    "paper", "research", "benchmark", "breakthrough", "milestone",
    "record", "achieves", "achieve", "state of the art", "sota",
    "new model", "inference", "agentic", "reactor", "nuclear",
)
AMOUNT_PATTERN = re.compile(
    r"(?<![A-Za-z0-9])(?P<prefix>\$)?(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>billion|million|bn|b|m)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Article:
    source: str
    title: str
    link: str
    pub_date: datetime
    summary: str


@dataclass(frozen=True)
class SourceGroup:
    name: str
    url: str = ""
    article_count: int = 0
    status: str = ""
    error: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render RSS daily report from raw.json and validation.json.",
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to raw.json produced by the RSS monitor.",
    )
    parser.add_argument(
        "--validation",
        required=True,
        help="Path to validation.json produced by the validator.",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Target markdown path for the official report.",
    )
    parser.add_argument(
        "--date",
        help="Optional YYYY-MM-DD date to display in the report title.",
    )
    return parser.parse_args()


def load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def parse_pub_date(value: Any) -> datetime:
    if isinstance(value, datetime):
        dt = value
    elif isinstance(value, str):
        raw = value.strip()
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        dt = datetime.fromisoformat(raw)
    else:
        raise ValueError(f"Unsupported pub_date value: {value!r}")

    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def format_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def format_time_only(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%H:%M UTC")


def clamp_text(text: str, limit: int) -> str:
    text = " ".join((text or "").split())
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 1)].rstrip() + "…"


def as_dict(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def normalize_articles(raw: Dict[str, Any]) -> List[Article]:
    articles: List[Article] = []
    for item in raw.get("articles", []):
        if not isinstance(item, dict):
            continue
        try:
            articles.append(
                Article(
                    source=str(item.get("source", "")).strip() or "Unknown",
                    title=str(item.get("title", "")).strip() or "(untitled)",
                    link=str(item.get("link", "")).strip(),
                    pub_date=parse_pub_date(item["pub_date"]),
                    summary=str(item.get("summary_en", "")).strip(),
                )
            )
        except Exception:
            continue
    articles.sort(key=lambda a: a.pub_date, reverse=True)
    return articles


def normalize_source_groups(raw: Dict[str, Any], validation: Dict[str, Any], articles: Sequence[Article]) -> List[SourceGroup]:
    article_counts: Dict[str, int] = {}
    for article in articles:
        article_counts[article.source] = article_counts.get(article.source, 0) + 1

    roster: List[SourceGroup] = []

    feed_results = validation.get("feed_results")
    if isinstance(feed_results, list) and feed_results:
        for entry in feed_results:
            if not isinstance(entry, dict):
                continue
            name = str(entry.get("source") or entry.get("name") or "").strip()
            if not name:
                continue
            roster.append(
                SourceGroup(
                    name=name,
                    url=str(entry.get("url", "")).strip(),
                    article_count=int(entry.get("article_count") or article_counts.get(name, 0)),
                    status=str(entry.get("status", "")).strip(),
                    error=str(entry.get("error", "") or "").strip(),
                )
            )
        if roster:
            return roster

    configured_feeds = raw.get("configured_feeds")
    if isinstance(configured_feeds, list) and configured_feeds:
        for entry in configured_feeds:
            if isinstance(entry, dict):
                name = str(entry.get("name", "")).strip()
                url = str(entry.get("url", "")).strip()
            else:
                name = str(entry).strip()
                url = ""
            if not name:
                continue
            roster.append(
                SourceGroup(
                    name=name,
                    url=url,
                    article_count=article_counts.get(name, 0),
                )
            )
        if roster:
            return roster

    raw_feed_results = raw.get("feed_results")
    if isinstance(raw_feed_results, list) and raw_feed_results:
        for entry in raw_feed_results:
            if not isinstance(entry, dict):
                continue
            name = str(entry.get("source") or entry.get("name") or "").strip()
            if not name:
                continue
            roster.append(
                SourceGroup(
                    name=name,
                    url=str(entry.get("url", "")).strip(),
                    article_count=int(entry.get("article_count") or article_counts.get(name, 0)),
                    status=str(entry.get("status", "")).strip(),
                    error=str(entry.get("error", "") or "").strip(),
                )
            )
        if roster:
            return roster

    unique_sources = raw.get("unique_sources")
    if isinstance(unique_sources, list) and unique_sources:
        for name in unique_sources:
            name_str = str(name).strip()
            if not name_str:
                continue
            roster.append(
                SourceGroup(
                    name=name_str,
                    article_count=article_counts.get(name_str, 0),
                )
            )
        if roster:
            return roster

    for name in sorted(article_counts.keys()):
        roster.append(SourceGroup(name=name, article_count=article_counts[name]))
    return roster


def group_articles(articles: Sequence[Article]) -> Dict[str, List[Article]]:
    grouped: Dict[str, List[Article]] = {}
    for article in articles:
        grouped.setdefault(article.source, []).append(article)
    for group in grouped.values():
        group.sort(key=lambda a: a.pub_date, reverse=True)
    return grouped


def _normalized_text(article: Article) -> str:
    return f"{article.title}\n{article.summary}".lower()


def _contains_any(text: str, phrases: Sequence[str]) -> bool:
    return any(phrase in text for phrase in phrases)


def _extract_max_amount_millions(text: str) -> float:
    max_amount = 0.0
    for match in AMOUNT_PATTERN.finditer(text):
        value = float(match.group("value"))
        unit = match.group("unit").lower()
        if unit in ("billion", "bn", "b"):
            value *= 1000.0
        max_amount = max(max_amount, value)
    return max_amount


def score_article(article: Article) -> float:
    text = _normalized_text(article)
    score = 0.0
    has_speculation = _contains_any(text, SPECULATION_PATTERNS)
    has_business_signal = _contains_any(text, BUSINESS_PATTERNS)
    has_security_signal = _contains_any(text, SECURITY_PATTERNS)

    if _contains_any(text, HARD_NOISE_PATTERNS):
        score -= 120.0
    if _contains_any(text, NOISE_PATTERNS):
        score -= 45.0
    if has_speculation:
        score -= 38.0
    if article.source == "Hacker News Best":
        score -= 35.0

    amount_m = _extract_max_amount_millions(text)
    has_major_company = _contains_any(text, MAJOR_COMPANIES)

    if amount_m >= 100.0:
        score += 120.0
    if has_business_signal:
        score += 85.0
    if has_major_company and _contains_any(text, LAUNCH_PATTERNS):
        score += 75.0
    elif _contains_any(text, LAUNCH_PATTERNS):
        score += 40.0
    if has_security_signal:
        score += 80.0
    if _contains_any(text, BREAKTHROUGH_PATTERNS):
        score += 55.0
    if has_major_company:
        score += 18.0
    if has_speculation and amount_m < 100.0:
        score -= 32.0
    if has_speculation and not has_business_signal and not has_security_signal and amount_m < 100.0:
        score -= 45.0
    return score


def choose_top_articles(articles: Sequence[Article]) -> List[Article]:
    remaining = list(articles)
    selected: List[Article] = []
    source_counts: Dict[str, int] = {}

    while remaining and len(selected) < TOP_N:
        best_index = 0
        best_score = None
        for idx, article in enumerate(remaining):
            diversity_penalty = source_counts.get(article.source, 0) * 14.0
            candidate_score = score_article(article) - diversity_penalty
            candidate_key = (candidate_score, article.pub_date.timestamp())
            if best_score is None or candidate_key > best_score:
                best_score = candidate_key
                best_index = idx
        chosen = remaining.pop(best_index)
        selected.append(chosen)
        source_counts[chosen.source] = source_counts.get(chosen.source, 0) + 1

    return selected


def render_report(
    raw: Dict[str, Any],
    validation: Dict[str, Any],
    date_str: str,
) -> str:
    articles = normalize_articles(raw)
    groups = normalize_source_groups(raw, validation, articles)
    article_map = group_articles(articles)
    top_articles = choose_top_articles(articles)

    passed = validation.get("passed") is True
    total_articles = len(articles)
    configured_count = as_dict(validation.get("counts")).get("configured")
    if configured_count is None:
        configured_count = raw.get("configured_feed_count")
    if configured_count is None:
        configured_count = len(groups)
    try:
        configured_count = int(configured_count)
    except Exception:
        configured_count = len(groups)

    lines: List[str] = []
    lines.append(LINE)
    lines.append(f"[{date_str}] RSS 每日精选 TOP {TOP_N}")
    lines.append(LINE)
    lines.append("")

    if passed:
        lines.append(f"数据来源：{configured_count}个RSS源，共获取 {total_articles} 篇文章")
    else:
        reasons = validation.get("blocking_reasons") or []
        reason_text = "；".join(str(item) for item in reasons if str(item).strip())
        if reason_text:
            lines.append(f"校验状态：未通过，阻断原因：{reason_text}")
        else:
            lines.append("校验状态：未通过")
        lines.append(f"数据来源：{configured_count}个RSS源，共获取 {total_articles} 篇文章")
    lines.append(f"生成时间：{date_str} UTC")
    lines.append("")

    failed_groups = [group for group in groups if group.status == "error"]
    if failed_groups:
        failure_summary = "；".join(
            f"{group.name} ({group.error})" if group.error else group.name
            for group in failed_groups
        )
        lines.append(f"抓取异常：{failure_summary}")
        lines.append("")

    if top_articles:
        for idx, article in enumerate(top_articles, 1):
            lines.append(f"{idx}. {article.title}")
            lines.append(f"   来源: {article.source}")
            lines.append(f"   时间: {format_utc(article.pub_date)}")
            lines.append(f"   链接: {article.link}")
            if article.summary:
                lines.append(f"   摘要: {clamp_text(article.summary, 200)}")
            else:
                lines.append("   摘要: （无）")
            lines.append("")
    else:
        lines.append("本次抓取结果为空，过去 24 小时未获取到可展示的文章。")
        lines.append("")

    lines.append(LINE)
    lines.append("按来源分组")
    lines.append(LINE)
    lines.append("")

    for group in groups:
        source_articles = article_map.get(group.name, [])
        group_count = len(source_articles)
        if group.status == "error":
            lines.append(f"--- {group.name} ({group_count}篇 · 抓取失败) ---")
        else:
            lines.append(f"--- {group.name} ({group_count}篇) ---")
        lines.append("")
        if group.status == "error":
            lines.append(f"抓取状态: {group.error or '抓取失败'}")
            lines.append("")
        if not source_articles:
            lines.append("无文章")
            lines.append("")
            continue

        for idx, article in enumerate(source_articles, 1):
            lines.append(f"{idx}. {article.title}")
            lines.append(f"   时间: {format_time_only(article.pub_date)} | 链接: {article.link}")
            if article.summary:
                lines.append(f"   摘要: {clamp_text(article.summary, 120)}")
            else:
                lines.append("   摘要: （无）")
            lines.append("")

    lines.append(LINE)
    lines.append("统计检查")
    lines.append(LINE)
    lines.append("")

    part1_count = len(top_articles)
    part2_groups = len(groups)
    part2_article_total = sum(len(article_map.get(group.name, [])) for group in groups)
    group_count_match = "是" if part2_groups == configured_count else "否"
    article_total_match = "是" if part2_article_total == total_articles else "否"
    unique_sources = raw.get("unique_sources")
    if not isinstance(unique_sources, list):
        unique_sources = sorted({article.source for article in articles})
    unique_source_count = raw.get("unique_source_count")
    if not isinstance(unique_source_count, int):
        unique_source_count = len(unique_sources)

    lines.append(f"- feeds.json feed 数量: {configured_count}")
    lines.append(f"- JSON count: {total_articles}")
    lines.append(f"- JSON 去重 source 列表: {json.dumps(unique_sources, ensure_ascii=False)}")
    lines.append(f"- JSON 去重 source 数量: {unique_source_count}")
    lines.append(f"- Part 1 文章数: {part1_count}")
    lines.append(f"- Part 2 分组数: {part2_groups}")
    lines.append(f"- Part 2 文章总数: {part2_article_total}")
    lines.append(f"- Part 2 分组数与 feeds.json 一致: {group_count_match}")
    lines.append(f"- Part 2 文章总数与 JSON count 一致: {article_total_match}")
    lines.append(
        f"- JSON 去重 source 数量与 feeds.json 一致: {'是' if unique_source_count == configured_count else '否'}"
    )

    passed_value = validation.get("passed") is True
    lines.append(f"- 校验结论: {'通过' if passed_value else '未通过'}")

    warnings = validation.get("warnings") or []
    if warnings:
        lines.append(f"- 校验警告: {'；'.join(str(item) for item in warnings if str(item).strip())}")
    if failed_groups:
        lines.append(
            "- 抓取失败来源: "
            + "；".join(
                f"{group.name} ({group.error})" if group.error else group.name
                for group in failed_groups
            )
        )

    if not passed_value:
        reasons = validation.get("blocking_reasons") or []
        if reasons:
            lines.append(f"- 阻断原因: {'；'.join(str(item) for item in reasons if str(item).strip())}")

    return "\n".join(lines).rstrip() + "\n"


def report_date(args_date: Optional[str], output_path: str, raw: Dict[str, Any], validation: Dict[str, Any]) -> str:
    if args_date:
        return args_date
    for candidate in (
        as_dict(validation.get("meta")).get("generated_at_utc"),
        as_dict(raw.get("meta")).get("generated_at_utc"),
    ):
        if isinstance(candidate, str) and candidate.strip():
            try:
                return parse_pub_date(candidate).strftime("%Y-%m-%d")
            except Exception:
                continue
    stem = Path(output_path).stem
    for prefix in ("rss-report-", "rss-report_"):
        if stem.startswith(prefix):
            maybe_date = stem[len(prefix):]
            if len(maybe_date) == 10:
                return maybe_date
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def failed_output_path(output_path: str) -> str:
    path = Path(output_path)
    if path.stem.endswith(".failed"):
        return str(path)
    if path.suffix.lower() == ".md":
        return str(path.with_name(f"{path.stem}.failed{path.suffix}"))
    return str(path.with_name(f"{path.name}.failed.md"))


def write_text(path: str, content: str) -> None:
    os.makedirs(str(Path(path).parent), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def main() -> int:
    args = parse_args()

    try:
        raw = load_json(args.input)
        validation = load_json(args.validation)
    except Exception as exc:
        print(f"Failed to load input: {exc}", file=sys.stderr)
        return 10

    try:
        date_str = report_date(args.date, args.output, raw, validation)
        content = render_report(raw, validation, date_str)
        passed = validation.get("passed") is True
        target_path = args.output if passed else failed_output_path(args.output)
        write_text(target_path, content)
        print(target_path)
        return 0
    except Exception as exc:
        print(f"Failed to render report: {exc}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    raise SystemExit(main())
