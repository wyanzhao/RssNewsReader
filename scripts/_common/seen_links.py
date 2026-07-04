"""Cross-run seen-links ledger for the DailyNews pipeline.

The daily fetch uses a rolling time window relative to "now". Run-time jitter
between consecutive days therefore leaves coverage gaps (a run at 10:05
followed by one at 13:03 the next day never sees ~3h of articles), and simply
widening the window would instead re-report boundary articles on two
consecutive days.

This ledger resolves both: the window is widened (``fetch.hours`` > 24) and
articles whose link was already covered by an *earlier* day's published
report are dropped at fetch time. Entries are keyed by the same normalized
link key used for in-run dedup and are recorded by
``editorial_runtime.py assemble`` — i.e. only links that actually landed in a
published success report are ever marked as seen, so blocked or failed days
never poison the next day's fetch. Entries dated *today* never filter, which
keeps same-day re-runs idempotent.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

from .text import dedup_link_key

DEFAULT_SEEN_LINKS_MAX_AGE_DAYS = 14


def default_seen_links_path(llm_context_path: str | Path) -> Path:
    run_dir = Path(llm_context_path).resolve().parent
    return run_dir.parent / "_seen_links.json"


def load_seen_links(path: str | Path) -> Dict[str, str]:
    """Load the ledger's ``{link_key: "YYYY-MM-DD"}`` entries.

    A missing file is an empty ledger; structurally invalid content raises
    ValueError and callers decide whether to skip filtering (fetch side) or
    rebuild fresh (assemble side).
    """
    ledger_path = Path(path)
    if not ledger_path.exists():
        return {}
    payload = json.loads(ledger_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("seen-links ledger must be a JSON object")
    entries = payload.get("entries", {})
    if not isinstance(entries, dict):
        raise ValueError("seen-links entries must be a JSON object")
    return {
        str(key): str(value)
        for key, value in entries.items()
        if isinstance(key, str) and isinstance(value, str)
    }


def write_seen_links(path: str | Path, entries: Dict[str, str]) -> None:
    ledger_path = Path(path)
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    ledger_path.write_text(
        json.dumps({"version": 1, "entries": entries},
                   ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


def filter_previously_reported(articles: List[Dict],
                               entries: Dict[str, str],
                               report_date: str) -> Tuple[List[Dict], int]:
    """Drop articles whose link was reported on a date before ``report_date``.

    Same-date entries are kept so a same-day re-run reproduces the same
    article set instead of filtering itself down to nothing.
    """
    if not entries:
        return articles, 0
    kept: List[Dict] = []
    dropped = 0
    for article in articles:
        key = dedup_link_key(str(article.get("link", "")))
        seen_on = entries.get(key) if key else None
        if seen_on and seen_on < report_date:
            dropped += 1
            continue
        kept.append(article)
    return kept, dropped


def record_reported_links(entries: Dict[str, str],
                          links: Iterable[str],
                          report_date: str) -> Dict[str, str]:
    for link in links:
        key = dedup_link_key(str(link or ""))
        if not key:
            continue
        existing = entries.get(key)
        if existing is None or existing < report_date:
            entries[key] = report_date
    return entries


def prune_seen_links(entries: Dict[str, str],
                     report_date: str,
                     max_age_days: int = DEFAULT_SEEN_LINKS_MAX_AGE_DAYS) -> Dict[str, str]:
    """Drop entries older than ``max_age_days`` relative to ``report_date``.

    The fetch window is far shorter than the retention period; the extra days
    also shield against feeds that re-publish old items with bumped dates.
    Date math is done lexically on ISO dates via a day-count comparison.
    """
    from datetime import date

    try:
        anchor = date.fromisoformat(report_date)
    except ValueError:
        return entries
    kept: Dict[str, str] = {}
    for key, seen_on in entries.items():
        try:
            age = (anchor - date.fromisoformat(seen_on)).days
        except ValueError:
            continue
        if age <= max_age_days:
            kept[key] = seen_on
    entries.clear()
    entries.update(kept)
    return entries
