"""Shared text / URL / date utilities.

Migrated verbatim from rss_news_monitor.py to remove cross-file duplication.
Behaviour MUST stay byte-identical to the originals (clean_summary,
parse_date, link.rstrip('/')-style dedup key) so the downstream raw.json
shape does not drift. Stage 3 may upgrade these — for now they are pure
extractions.
"""

from __future__ import annotations

import html
import re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Optional


# 2000-word legacy soft cap kept identical to rss_news_monitor.clean_summary.
DEFAULT_SUMMARY_WORD_CAP = 2000


def strip_html(description: str, max_chars: int = 0) -> str:
    """Strip HTML tags from a description string and optionally truncate.

    Behaviour parity with the original `clean_summary`:
    - Removes any `<...>` tag span.
    - HTML-unescapes entities.
    - Collapses whitespace.
    - When ``max_chars > 0``: truncates to that character budget at a word
      boundary, suffixed with "...".
    - When ``max_chars == 0`` (legacy): hard-caps at 2000 words.
    """
    if not description:
        return ""

    text = re.sub(r"<[^>]+>", "", description)
    text = html.unescape(text)
    text = re.sub(r"\s+", " ", text).strip()

    if max_chars > 0 and len(text) > max_chars:
        truncated = text[:max_chars].rsplit(" ", 1)[0]
        return truncated + "..."

    if max_chars == 0:
        words = text.split()
        if len(words) > DEFAULT_SUMMARY_WORD_CAP:
            return " ".join(words[:DEFAULT_SUMMARY_WORD_CAP]) + "..."

    return text


def parse_rss_date(date_str: str) -> Optional[datetime]:
    """Parse RSS/Atom date strings into a timezone-aware datetime.

    Behaviour parity with the original `parse_date` in rss_news_monitor.py:
    1. Try RFC 2822 via email.utils.parsedate_to_datetime
    2. Try ISO 8601 (with Z -> +00:00 normalization)
    3. Strip trailing tz suffix and try a list of common formats, falling
       back to UTC

    Returns ``None`` if the input is empty or unparseable.
    """
    if not date_str:
        return None

    try:
        date_str = date_str.strip()

        try:
            return parsedate_to_datetime(date_str)
        except Exception:
            pass

        try:
            iso_str = date_str.replace("Z", "+00:00")
            parsed = datetime.fromisoformat(iso_str)
            if parsed.tzinfo is None:
                return parsed.replace(tzinfo=timezone.utc)
            return parsed
        except Exception:
            pass

        cleaned = date_str
        cleaned = re.sub(r"\s*[+-]\d{2}:?\d{2}$", "", cleaned)
        cleaned = re.sub(r"\s*(UTC|GMT|PST|PDT|EST|EDT|CST|CDT|MST|MDT)$", "", cleaned)
        if cleaned.endswith("Z"):
            cleaned = cleaned[:-1]
        cleaned = " ".join(cleaned.split())

        formats = [
            "%a, %d %b %Y %H:%M:%S",
            "%Y-%m-%dT%H:%M:%S",
            "%Y-%m-%d %H:%M:%S",
            "%Y-%m-%dT%H:%M:%S.%f",
            "%Y-%m-%d",
        ]
        for fmt in formats:
            try:
                parsed = datetime.strptime(cleaned, fmt)
                return parsed.replace(tzinfo=timezone.utc)
            except ValueError:
                continue

        return None
    except Exception:
        return None


def dedup_link_key(link: str) -> str:
    """Return the stable key used for link-based deduplication.

    Behaviour parity with `dedup_articles` in rss_news_monitor.py: trims a
    single trailing slash. Stage 3 may extend this (lower-case host, drop
    `?utm_*`, sort query) — for now keep identical to legacy.
    """
    return (link or "").rstrip("/")
