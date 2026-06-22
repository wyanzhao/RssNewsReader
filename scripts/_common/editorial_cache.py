"""Shared editorial cache helpers for DailyNews runtime artifacts."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional


# How long a cached Chinese summary stays usable before ``update_entries``
# prunes it. Matches the runs/ retention default so the cache cannot grow
# without bound.
DEFAULT_CACHE_MAX_AGE_DAYS = 90


def clean_text(value: Any) -> str:
    return " ".join(str(value or "").split())


def cache_key(article: Dict[str, Any]) -> str:
    """Stable, link-based cache key.

    The cache stores LLM-written Chinese summaries so they can be reused across
    runs (notably same-day re-runs and retries). The previous key hashed
    ``link + summary_en + article_text``; because ``article_text`` is re-extracted
    best-effort on every run and ``summary_en`` can drift, that key almost never
    matched and the cache had a ~0% hit rate. Keying on the article ``link`` —
    the same identity used for dedup — lets a recurring article reuse its prior
    summary. :func:`legacy_cache_key` is still consulted on lookup so entries
    written by the old scheme remain discoverable during rollover.
    """
    material = "v2\0" + clean_text(article.get("link"))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def legacy_cache_key(article: Dict[str, Any]) -> str:
    """Pre-v2 cache key (link + summary_en + article_text).

    Retained only so existing on-disk entries stay discoverable until they are
    re-written under the v2 key or pruned by age.
    """
    material = "\0".join(
        [
            clean_text(article.get("link")),
            clean_text(article.get("summary_en")),
            clean_text(article.get("article_text")),
        ]
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def event_key(item: Dict[str, Any]) -> str:
    value = clean_text(item.get("event_key"))
    if value:
        return value
    title = clean_text(item.get("title")).lower()
    return re.sub(r"[^a-z0-9]+", "-", title).strip("-")[:120]


def default_cache_path(llm_context_path: str | Path) -> Path:
    run_dir = Path(llm_context_path).resolve().parent
    return run_dir.parent / "_cache" / "editorial_cache.json"


def load_cache(path: str | Path) -> Dict[str, Any]:
    cache_path = Path(path)
    if not cache_path.exists():
        return {"version": 1, "entries": {}}
    payload = json.loads(cache_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("editorial cache must be a JSON object")
    entries = payload.setdefault("entries", {})
    if not isinstance(entries, dict):
        raise ValueError("editorial cache entries must be a JSON object")
    payload.setdefault("version", 1)
    return payload


def write_cache(path: str | Path, cache: Dict[str, Any]) -> None:
    cache_path = Path(path)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")


def lookup_entry(article: Dict[str, Any],
                 cache: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
    if not cache:
        return None
    entries = cache.get("entries")
    if not isinstance(entries, dict):
        return None
    # Prefer the stable v2 key; fall back to the legacy key so entries written
    # before the key change remain reachable until they roll over or age out.
    for key in (cache_key(article), legacy_cache_key(article)):
        entry = entries.get(key)
        if isinstance(entry, dict):
            return entry
    return None


def prune_stale_entries(cache: Dict[str, Any],
                        max_age_days: Optional[int] = DEFAULT_CACHE_MAX_AGE_DAYS,
                        now: Optional[datetime] = None) -> Dict[str, Any]:
    """Drop entries whose ``updated_at_utc`` is older than ``max_age_days``.

    Entries with a missing or unparseable timestamp are kept defensively. A
    ``None`` or negative ``max_age_days`` disables pruning.
    """
    entries = cache.get("entries")
    if not isinstance(entries, dict) or max_age_days is None or max_age_days < 0:
        return cache
    if now is None:
        now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=max_age_days)
    kept: Dict[str, Any] = {}
    for key, entry in entries.items():
        if not isinstance(entry, dict):
            continue
        timestamp = entry.get("updated_at_utc")
        if isinstance(timestamp, str) and timestamp.strip():
            try:
                parsed = datetime.fromisoformat(timestamp)
            except ValueError:
                kept[key] = entry
                continue
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            if parsed < cutoff:
                continue
        kept[key] = entry
    cache["entries"] = kept
    return cache


def update_entries(cache: Dict[str, Any],
                   articles_by_link: Dict[str, Dict[str, Any]],
                   summary_items: Iterable[Dict[str, Any]],
                   *,
                   max_age_days: Optional[int] = DEFAULT_CACHE_MAX_AGE_DAYS,
                   now: Optional[datetime] = None) -> Dict[str, Any]:
    entries = cache.setdefault("entries", {})
    if not isinstance(entries, dict):
        raise ValueError("editorial cache entries must be a JSON object")

    now_dt = now or datetime.now(timezone.utc)
    now_iso = now_dt.isoformat()
    for item in summary_items:
        link = clean_text(item.get("link"))
        if not link:
            continue
        article = articles_by_link.get(link)
        if not article:
            continue
        key = cache_key(article)
        entries[key] = {
            "link": link,
            "source": article.get("source", ""),
            "title": article.get("title", ""),
            "summary_zh": item.get("summary_zh", ""),
            "noise_bucket": item.get("noise_bucket", "covered"),
            "event_key": event_key(item),
            "updated_at_utc": now_iso,
        }
    # Bound cache growth: freshly written entries carry ``now_iso`` and always
    # survive; only genuinely stale entries are dropped.
    prune_stale_entries(cache, max_age_days=max_age_days, now=now_dt)
    return cache
