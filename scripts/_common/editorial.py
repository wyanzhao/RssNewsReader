"""Shared editorial-domain helpers for report rendering and llm_context."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence


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


class SourceGroupConsistencyError(ValueError):
    """Raised when source-group metadata contradicts grouped articles."""


@dataclass(frozen=True)
class ArticleAnalysis:
    text: str
    heuristic_score: float
    audit_flags: List[str]
    amount_millions: float


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


def normalized_text(article: Article) -> str:
    return f"{article.title}\n{article.summary}".lower()


def contains_any(text: str, phrases: Sequence[str]) -> bool:
    return any(phrase in text for phrase in phrases)


def extract_max_amount_millions(text: str) -> float:
    max_amount = 0.0
    for match in AMOUNT_PATTERN.finditer(text):
        value = float(match.group("value"))
        unit = match.group("unit").lower()
        if unit in ("billion", "bn", "b"):
            value *= 1000.0
        max_amount = max(max_amount, value)
    return max_amount


def analyze_article(article: Article) -> ArticleAnalysis:
    text = normalized_text(article)
    amount_m = extract_max_amount_millions(text)
    has_major_company = contains_any(text, MAJOR_COMPANIES)
    has_business_signal = contains_any(text, BUSINESS_PATTERNS)
    has_security_signal = contains_any(text, SECURITY_PATTERNS)
    has_breakthrough_signal = contains_any(text, BREAKTHROUGH_PATTERNS)
    has_launch_signal = contains_any(text, LAUNCH_PATTERNS)
    has_speculation = contains_any(text, SPECULATION_PATTERNS)
    has_noise = contains_any(text, NOISE_PATTERNS)
    has_hard_noise = contains_any(text, HARD_NOISE_PATTERNS)

    score = 0.0
    if has_hard_noise:
        score -= 120.0
    if has_noise:
        score -= 45.0
    if has_speculation:
        score -= 38.0
    if article.source == "Hacker News Best":
        score -= 35.0
    if amount_m >= 100.0:
        score += 120.0
    if has_business_signal:
        score += 85.0
    if has_major_company and has_launch_signal:
        score += 75.0
    elif has_launch_signal:
        score += 40.0
    if has_security_signal:
        score += 80.0
    if has_breakthrough_signal:
        score += 55.0
    if has_major_company:
        score += 18.0
    if has_speculation and amount_m < 100.0:
        score -= 32.0
    if has_speculation and not has_business_signal and not has_security_signal and amount_m < 100.0:
        score -= 45.0

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

    return ArticleAnalysis(
        text=text,
        heuristic_score=score,
        audit_flags=flags,
        amount_millions=amount_m,
    )


def score_article(article: Article) -> float:
    return analyze_article(article).heuristic_score


def choose_top_articles(articles: Sequence[Article], limit: int) -> List[Article]:
    remaining = list(articles)
    selected: List[Article] = []
    source_counts: Dict[str, int] = {}

    while remaining and len(selected) < limit:
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


def normalized_article_payload(article: Article) -> Dict[str, Any]:
    analysis = analyze_article(article)
    return {
        "source": article.source,
        "title": article.title,
        "link": article.link,
        "pub_date_utc": format_utc(article.pub_date),
        "pub_date_iso": article.pub_date.astimezone(timezone.utc).isoformat(),
        "summary_en": article.summary,
        "heuristic_score": round(analysis.heuristic_score, 2),
        "audit_flags": analysis.audit_flags,
        "amount_millions": round(analysis.amount_millions, 2),
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
