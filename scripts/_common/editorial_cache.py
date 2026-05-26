"""Shared editorial cache helpers for DailyNews runtime artifacts."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional


def clean_text(value: Any) -> str:
    return " ".join(str(value or "").split())


def cache_key(article: Dict[str, Any]) -> str:
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
    entry = entries.get(cache_key(article))
    return entry if isinstance(entry, dict) else None


def update_entries(cache: Dict[str, Any],
                   articles_by_link: Dict[str, Dict[str, Any]],
                   summary_items: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    entries = cache.setdefault("entries", {})
    if not isinstance(entries, dict):
        raise ValueError("editorial cache entries must be a JSON object")

    now = datetime.now(timezone.utc).isoformat()
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
            "updated_at_utc": now,
        }
    return cache
