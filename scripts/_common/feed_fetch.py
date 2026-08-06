"""Network fetch helpers for rss_news_monitor.py."""

from __future__ import annotations

import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from http.client import HTTPException
from typing import Callable, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .feed_parse import extract_html_summary, parse_feed
from .runtime_config import (
    DEFAULT_ARTICLE_TEXT_MAX_WORDS,
    DEFAULT_ARTICLE_TEXT_MAX_WORKERS,
    DEFAULT_PAGE_FALLBACK_CAP,
    DEFAULT_SHORT_SUMMARY_THRESHOLD,
    resolve_article_text_settings,
    resolve_page_fallback_cap,
)

SHORT_SUMMARY_THRESHOLD = DEFAULT_SHORT_SUMMARY_THRESHOLD
FALLBACK_SUMMARY_CAP = DEFAULT_PAGE_FALLBACK_CAP

DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; RSS Monitor/3.0)"


def _is_retryable(exc: BaseException) -> bool:
    """Whether re-issuing the same request could plausibly succeed.

    A 4xx is the server telling us *this request* is wrong — repeating it byte
    for byte cannot change the answer, so a UA-blocked feed used to burn the
    full retry budget on every run for nothing. ``429`` is deliberately
    excluded: it is a rate-limit signal, and waiting before retrying is exactly
    the right response to it.
    """
    if isinstance(exc, HTTPError) and 400 <= exc.code < 500 and exc.code != 429:
        return False
    return True


def fetch_url(url: str, timeout: int = 30, retries: int = 2,
              headers: Optional[Dict[str, str]] = None,
              user_agent: str = "") -> Tuple[bytes, Optional[str]]:
    """Fetch URL content with retry on transient errors."""
    request_headers = {
        "User-Agent": user_agent or DEFAULT_USER_AGENT,
        "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml",
    }
    if headers:
        request_headers.update(headers)

    last_error: Optional[BaseException] = None
    for attempt in range(retries + 1):
        try:
            req = Request(url, headers=request_headers)
            with urlopen(req, timeout=timeout) as response:
                raw = response.read()
                charset = response.headers.get_content_charset()
                return raw, charset
        # HTTPException covers http.client.IncompleteRead — a mid-transfer
        # truncation, i.e. the most transient failure there is. It descends from
        # Exception, *not* OSError, so the original three-branch tuple silently
        # excluded the one error this retry loop most needed to catch.
        except (HTTPError, URLError, OSError, HTTPException) as exc:
            last_error = exc
            if attempt >= retries or not _is_retryable(exc):
                break
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


def enrich_article_text(
    articles: List[Dict],
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_article_text_fn: Optional[Callable[[str, int], str]] = None,
) -> None:
    """Backfill ``article_text`` by fetching each article page and extracting
    its main body. Existing non-empty ``article_text`` values are preserved.

    This is a best-effort enrichment: a fetch failure or empty extraction
    leaves ``article_text`` as an empty string. Callers should still be able
    to fall back to ``summary_en``.
    """
    # Imported lazily to avoid a top-level circular import with article_extract,
    # which itself imports fetch_url / decode_content from this module.
    if fetch_article_text_fn is None:
        from .article_extract import fetch_article_text as _default_fetch_article_text
        fetch_article_text_fn = _default_fetch_article_text

    settings = resolve_article_text_settings(pipeline_config)
    if not settings["enabled"]:
        for article in articles:
            article.setdefault("article_text", "")
        return

    max_words = settings["max_words"]
    max_workers = settings["max_workers"]

    missing_links = sorted({
        str(article.get("link", "")).strip()
        for article in articles
        if not _normalize_summary(article.get("article_text"))
        and str(article.get("link", "")).strip()
    })
    if not missing_links:
        for article in articles:
            article.setdefault("article_text", "")
        return

    results: Dict[str, str] = {}
    worker_count = min(max(max_workers, 1), len(missing_links))
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(fetch_article_text_fn, link, max_words): link
            for link in missing_links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ""
                print(f"[WARN] Article text extraction failed for {link}: {exc}",
                      file=sys.stderr)

    for article in articles:
        if _normalize_summary(article.get("article_text")):
            continue
        link = str(article.get("link", "")).strip()
        extracted = _normalize_summary(results.get(link, ""))
        article["article_text"] = extracted


def enrich_article_pages(
    articles: List[Dict],
    max_summary: int = 0,
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    extract_summary_fn: Callable[[str, int], str] = extract_html_summary,
    extract_main_text_fn: Optional[Callable[[str], str]] = None,
) -> None:
    """Fetch each article page at most once and extract both enrichments.

    This merges the work previously split across :func:`enrich_missing_summaries`
    (HTML meta-summary fallback) and :func:`enrich_article_text` (main-body
    extraction). Both passes independently re-fetched the same article URLs; on
    the JSON output path that meant every short-summary article page was fetched
    twice. Here each page that needs *either* enrichment is fetched once, and the
    single decoded response feeds both extractors.

    Acceptance rules are preserved verbatim from the two original passes:

    - ``summary_en`` is replaced only when the page summary is non-empty and
      strictly longer than the current (short) summary.
    - ``article_text`` is filled only when currently empty, and is left as an
      empty string when extraction is disabled, the link is missing, or the
      fetch / extraction fails.
    """
    # Imported lazily to avoid a top-level circular import with article_extract,
    # which itself imports fetch_url / decode_content from this module.
    if extract_main_text_fn is None:
        from .article_extract import extract_main_text as _default_extract_main_text
        extract_main_text_fn = _default_extract_main_text
    from .article_extract import truncate_words

    settings = resolve_article_text_settings(pipeline_config)
    body_enabled = bool(settings["enabled"])
    max_words = settings["max_words"]
    worker_count_cfg = settings["max_workers"]
    fallback_limit = _fallback_summary_limit(max_summary, pipeline_config)

    # Parity with enrich_article_text: the field always exists once this pass
    # has been asked to run, even when extraction is disabled or a link is
    # missing.
    for article in articles:
        article.setdefault("article_text", "")

    # Decide, per unique link, what each single fetch must yield.
    needs: Dict[str, Tuple[bool, bool]] = {}
    for article in articles:
        link = str(article.get("link", "")).strip()
        if not link:
            continue
        want_summary = _needs_summary_fallback(article.get("summary_en"), pipeline_config)
        want_body = body_enabled and not _normalize_summary(article.get("article_text"))
        if want_summary or want_body:
            prev = needs.get(link, (False, False))
            needs[link] = (prev[0] or want_summary, prev[1] or want_body)

    if not needs:
        return

    def _fetch_page(link: str, want_summary: bool, want_body: bool) -> Tuple[str, str]:
        raw, charset = fetch_url_fn(
            link,
            timeout=20,
            retries=1,
            headers={"Accept": "text/html, application/xhtml+xml"},
        )
        content = decode_content_fn(raw, charset)
        summary = extract_summary_fn(content, max_chars=fallback_limit) if want_summary else ""
        body = truncate_words(extract_main_text_fn(content), max_words) if want_body else ""
        return summary, body

    links = sorted(needs)
    worker_count = min(max(worker_count_cfg, 1), len(links))
    results: Dict[str, Tuple[str, str]] = {}
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(_fetch_page, link, needs[link][0], needs[link][1]): link
            for link in links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ("", "")
                print(f"[WARN] Article page enrichment failed for {link}: {exc}",
                      file=sys.stderr)

    for article in articles:
        link = str(article.get("link", "")).strip()
        if link not in results:
            continue
        fetched_summary, fetched_body = results[link]

        current_summary = _normalize_summary(article.get("summary_en"))
        if _needs_summary_fallback(current_summary, pipeline_config):
            fallback = _normalize_summary(fetched_summary)
            if fallback and (not current_summary or len(fallback) > len(current_summary)):
                article["summary_en"] = fallback

        if body_enabled and not _normalize_summary(article.get("article_text")):
            article["article_text"] = _normalize_summary(fetched_body)


def fetch_rss_feed(
    name: str,
    url: str,
    hours: int = 24,
    max_summary: int = 0,
    *,
    user_agent: str = "",
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    parse_feed_fn: Callable[[str, int], List[Dict]] = parse_feed,
) -> Tuple[List[Dict], Optional[str], Optional[str]]:
    """Fetch and parse a single RSS/Atom feed.

    Returns ``(articles_within_window, error, newest_item_date)``.
    ``newest_item_date`` is the ISO timestamp of the newest item in the whole
    feed *before* window filtering — a feed can be HTTP-healthy and XML-valid
    yet dormant, and only this pre-filter timestamp can tell (a stale feed
    otherwise looks identical to a quiet day).
    """
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)

    try:
        raw, charset = fetch_url_fn(url, user_agent=user_agent)
        content = decode_content_fn(raw, charset)
        articles = parse_feed_fn(content, max_summary=max_summary)
        newest = max(
            (article["pub_date"] for article in articles if article.get("pub_date")),
            default=None,
        )
        newest_iso = newest.isoformat() if newest else None
        articles = [article for article in articles if article["pub_date"] >= cutoff]

        for article in articles:
            article["source"] = name

        return articles, None, newest_iso
    except HTTPError as exc:
        msg = f"HTTP {exc.code}"
        print(f"[WARN] {name}: {msg} - {url}", file=sys.stderr)
        return [], msg, None
    except URLError as exc:
        msg = f"Connection failed - {exc.reason}"
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg, None
    except Exception as exc:
        msg = str(exc)
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg, None


def fetch_all_feeds(
    feed_list: List[Dict],
    hours: int = 24,
    max_workers: int = 8,
    max_summary: int = 0,
    *,
    fetch_feed_fn: Callable[..., Tuple] = fetch_rss_feed,
) -> Tuple[List[Dict], Dict[str, Optional[str]], Dict[str, Optional[str]]]:
    """Concurrently fetch all feeds.

    Returns ``(all_articles, feed_status, feed_newest)`` where ``feed_newest``
    maps feed name to the pre-window-filter newest item timestamp (or None).
    Injected ``fetch_feed_fn`` stubs may still return the legacy 2-tuple.
    """
    all_articles = []
    feed_status: Dict[str, Optional[str]] = {}
    feed_newest: Dict[str, Optional[str]] = {}

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {}
        for feed in feed_list:
            # Passed only when the feed actually opts in, so the legacy
            # four-positional call shape that injected stubs rely on stays
            # exactly as it was for the feeds that do not override the UA.
            overrides = {}
            user_agent = str(feed.get("user_agent") or "").strip()
            if user_agent:
                overrides["user_agent"] = user_agent
            future = executor.submit(
                fetch_feed_fn, feed["name"], feed["url"], hours, max_summary, **overrides,
            )
            futures[future] = feed["name"]
        for future in as_completed(futures):
            name = futures[future]
            try:
                result = future.result()
                if len(result) >= 3:
                    articles, error, newest = result[0], result[1], result[2]
                else:
                    articles, error = result
                    newest = None
                all_articles.extend(articles)
                feed_status[name] = error
                feed_newest[name] = newest
            except Exception as exc:
                feed_status[name] = str(exc)
                feed_newest[name] = None
                print(f"[WARN] {name}: {exc}", file=sys.stderr)

    return all_articles, feed_status, feed_newest
