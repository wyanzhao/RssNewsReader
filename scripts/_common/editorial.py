"""Shared editorial-domain helpers for report rendering and llm_context.

Scoring, keyword pattern matching, and audit-flag derivation have been removed:
Top 30 selection and event prioritization now live entirely in the Claude Code
runtime layer (see `.claude/agents/part1-editor.md`). This module keeps only
normalization, grouping, and source-group consistency helpers.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence


@dataclass(frozen=True)
class Article:
    source: str
    title: str
    link: str
    pub_date: datetime
    summary: str
    article_text: str = ""


@dataclass(frozen=True)
class SourceGroup:
    name: str
    url: str = ""
    article_count: int = 0
    status: str = ""
    error: str = ""


class SourceGroupConsistencyError(ValueError):
    """Raised when source-group metadata contradicts grouped articles."""


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
                    article_text=str(item.get("article_text", "") or "").strip(),
                )
            )
        except Exception:
            continue
    articles.sort(key=lambda article: article.pub_date, reverse=True)
    return articles


def group_articles(articles: Sequence[Article]) -> Dict[str, List[Article]]:
    grouped: Dict[str, List[Article]] = {}
    for article in articles:
        grouped.setdefault(article.source, []).append(article)
    for group in grouped.values():
        group.sort(key=lambda article: article.pub_date, reverse=True)
    return grouped


def _entry_article_count(entry: Dict[str, Any], fallback: int) -> int:
    value = entry.get("article_count")
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return fallback


def assert_source_group_consistency(
    groups: Sequence[SourceGroup],
    grouped_articles: Dict[str, List[Article]],
) -> None:
    for group in groups:
        actual_count = len(grouped_articles.get(group.name, []))
        if group.article_count != actual_count:
            raise SourceGroupConsistencyError(
                f"source group consistency error for {group.name}: "
                f"declared article_count={group.article_count}, actual={actual_count}"
            )
        if group.status == "ok" and actual_count == 0:
            raise SourceGroupConsistencyError(
                f"source group consistency error for {group.name}: status=ok but no articles grouped"
            )
        if group.status == "empty" and actual_count != 0:
            raise SourceGroupConsistencyError(
                f"source group consistency error for {group.name}: status=empty but {actual_count} article(s) grouped"
            )


def normalize_source_groups(
    raw: Dict[str, Any],
    validation: Dict[str, Any],
    articles: Sequence[Article],
) -> List[SourceGroup]:
    grouped = group_articles(articles)
    article_counts = {name: len(items) for name, items in grouped.items()}
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
                    article_count=_entry_article_count(entry, article_counts.get(name, 0)),
                    status=str(entry.get("status", "")).strip(),
                    error=str(entry.get("error", "") or "").strip(),
                )
            )
        if roster:
            assert_source_group_consistency(roster, grouped)
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
            assert_source_group_consistency(roster, grouped)
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
                    article_count=_entry_article_count(entry, article_counts.get(name, 0)),
                    status=str(entry.get("status", "")).strip(),
                    error=str(entry.get("error", "") or "").strip(),
                )
            )
        if roster:
            assert_source_group_consistency(roster, grouped)
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
            assert_source_group_consistency(roster, grouped)
            return roster

    for name in sorted(article_counts.keys()):
        roster.append(SourceGroup(name=name, article_count=article_counts[name]))
    assert_source_group_consistency(roster, grouped)
    return roster


def normalized_article_payload(article: Article) -> Dict[str, Any]:
    return {
        "source": article.source,
        "title": article.title,
        "link": article.link,
        "pub_date_utc": format_utc(article.pub_date),
        "pub_date_iso": article.pub_date.astimezone(timezone.utc).isoformat(),
        "summary_en": article.summary,
        "article_text": article.article_text,
    }


def report_date(
    args_date: Optional[str],
    output_path: str,
    raw: Dict[str, Any],
    validation: Dict[str, Any],
) -> str:
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
