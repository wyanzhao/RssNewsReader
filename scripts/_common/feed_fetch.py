"""Network fetch helpers for rss_news_monitor.py."""

from __future__ import annotations

import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from typing import Callable, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .feed_parse import extract_html_summary, parse_feed
from .runtime_config import (
    DEFAULT_PAGE_FALLBACK_CAP,
    DEFAULT_SHORT_SUMMARY_THRESHOLD,
    resolve_page_fallback_cap,
)

SHORT_SUMMARY_THRESHOLD = DEFAULT_SHORT_SUMMARY_THRESHOLD
FALLBACK_SUMMARY_CAP = DEFAULT_PAGE_FALLBACK_CAP


def fetch_url(url: str, timeout: int = 30, retries: int = 2,
              headers: Optional[Dict[str, str]] = None) -> Tuple[bytes, Optional[str]]:
    """Fetch URL content with retry on transient errors."""
    request_headers = {
        "User-Agent": "Mozilla/5.0 (compatible; RSS Monitor/3.0)",
        "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml",
    }
    if headers:
        request_headers.update(headers)

    last_error: HTTPError | URLError | OSError | None = None
    for attempt in range(retries + 1):
        try:
            req = Request(url, headers=request_headers)
            with urlopen(req, timeout=timeout) as response:
                raw = response.read()
                charset = response.headers.get_content_charset()
                return raw, charset
        except (HTTPError, URLError, OSError) as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(1)

    if last_error is None:
        raise RuntimeError(f"fetch_url failed without a recorded error: {url}")
    raise last_error


def decode_content(raw: bytes, charset: Optional[str]) -> str:
    """Decode raw bytes using detected charset, falling back to utf-8."""
    encoding = charset or "utf-8"
    try:
        return raw.decode(encoding)
    except (UnicodeDecodeError, LookupError):
        return raw.decode("utf-8", errors="replace")


def fetch_article_summary(
    url: str,
    max_summary: int = 0,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    extract_summary_fn: Callable[[str, int], str] = extract_html_summary,
) -> str:
    """Fetch a linked article page and extract a fallback summary from HTML."""
    raw, charset = fetch_url_fn(
        url,
        timeout=20,
        retries=1,
        headers={
            "Accept": "text/html, application/xhtml+xml",
        },
    )
    content = decode_content_fn(raw, charset)
    return extract_summary_fn(content, max_chars=max_summary)


def _normalize_summary(summary: object) -> str:
    return str(summary or "").strip()


def _short_summary_threshold(pipeline_config: Optional[Dict[str, object]] = None) -> int:
    if pipeline_config:
        summary_config = pipeline_config.get("summary_enrichment", {})
        if isinstance(summary_config, dict):
            value = summary_config.get("short_summary_threshold")
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                return value
    return DEFAULT_SHORT_SUMMARY_THRESHOLD


def _needs_summary_fallback(summary: object,
                            pipeline_config: Optional[Dict[str, object]] = None) -> bool:
    return len(_normalize_summary(summary)) < _short_summary_threshold(pipeline_config)


def _fallback_summary_limit(max_summary: int,
                            pipeline_config: Optional[Dict[str, object]] = None) -> int:
    if pipeline_config:
        return resolve_page_fallback_cap(max_summary, pipeline_config)
    if max_summary <= 0:
        return DEFAULT_PAGE_FALLBACK_CAP
    return min(max_summary, DEFAULT_PAGE_FALLBACK_CAP)


def enrich_missing_summaries(
    articles: List[Dict],
    max_summary: int = 0,
    max_workers: int = 2,
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_summary_fn: Callable[[str, int], str] = fetch_article_summary,
) -> None:
    """Backfill empty or too-short summaries from linked article pages."""
    missing_links = sorted({
        str(article.get("link", "")).strip()
        for article in articles
        if _needs_summary_fallback(article.get("summary_en"), pipeline_config)
        and str(article.get("link", "")).strip()
    })
    if not missing_links:
        return

    results: Dict[str, str] = {}
    worker_count = min(max_workers, len(missing_links))
    fallback_limit = _fallback_summary_limit(max_summary, pipeline_config)
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(fetch_summary_fn, link, fallback_limit): link
            for link in missing_links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ""
                print(f"[WARN] Summary fallback failed for {link}: {exc}", file=sys.stderr)

    for article in articles:
        current_summary = _normalize_summary(article.get("summary_en"))
        if not _needs_summary_fallback(current_summary, pipeline_config):
            continue
        link = str(article.get("link", "")).strip()
        fallback = _normalize_summary(results.get(link, ""))
        if not fallback:
            continue
        if current_summary and len(fallback) <= len(current_summary):
            continue
        if fallback:
            article["summary_en"] = fallback


def fetch_rss_feed(
    name: str,
    url: str,
    hours: int = 24,
    max_summary: int = 0,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    parse_feed_fn: Callable[[str, int], List[Dict]] = parse_feed,
) -> Tuple[List[Dict], Optional[str]]:
    """Fetch and parse a single RSS/Atom feed."""
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)

    try:
        raw, charset = fetch_url_fn(url)
        content = decode_content_fn(raw, charset)
        articles = parse_feed_fn(content, max_summary=max_summary)
        articles = [article for article in articles if article["pub_date"] >= cutoff]

        for article in articles:
            article["source"] = name

        return articles, None
    except HTTPError as exc:
        msg = f"HTTP {exc.code}"
        print(f"[WARN] {name}: {msg} - {url}", file=sys.stderr)
        return [], msg
    except URLError as exc:
        msg = f"Connection failed - {exc.reason}"
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg
    except Exception as exc:
        msg = str(exc)
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg


def fetch_all_feeds(
    feed_list: List[Dict],
    hours: int = 24,
    max_workers: int = 8,
    max_summary: int = 0,
    *,
    fetch_feed_fn: Callable[[str, str, int, int], Tuple[List[Dict], Optional[str]]] = fetch_rss_feed,
) -> Tuple[List[Dict], Dict[str, Optional[str]]]:
    """Concurrently fetch all feeds."""
    all_articles = []
    feed_status: Dict[str, Optional[str]] = {}

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {
            executor.submit(fetch_feed_fn, feed["name"], feed["url"], hours, max_summary): feed["name"]
            for feed in feed_list
        }
        for future in as_completed(futures):
            name = futures[future]
            try:
                articles, error = future.result()
                all_articles.extend(articles)
                feed_status[name] = error
            except Exception as exc:
                feed_status[name] = str(exc)
                print(f"[WARN] {name}: {exc}", file=sys.stderr)

    return all_articles, feed_status
