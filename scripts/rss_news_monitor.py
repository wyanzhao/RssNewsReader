#!/usr/bin/env python3
"""
RSS News Monitor - Extract news from the past N hours from multiple RSS feeds.
Uses xml.etree.ElementTree (stdlib) for robust XML parsing.
Concurrent fetching via ThreadPoolExecutor for performance.

Usage:
    # Use feeds from feeds.json
    python3 rss_news_monitor.py [--hours HOURS]

    # Use specific feeds (override feeds.json)
    python3 rss_news_monitor.py --feeds "url1" "url2" [--hours HOURS]

    # Add/remove feeds in feeds.json
    python3 rss_news_monitor.py --add "Name" "https://example.com/feed.xml"
    python3 rss_news_monitor.py --remove "Name"

    # List feeds in feeds.json
    python3 rss_news_monitor.py --list

    # Output as JSON
    python3 rss_news_monitor.py --json [--hours HOURS]

    # Summary / health check
    python3 rss_news_monitor.py --summary [--hours HOURS]

    # Import feeds from OPML file
    python3 rss_news_monitor.py --import-opml feeds.opml
"""

import argparse
import html
import os
import sys
import time
from datetime import datetime
from typing import Any, Dict, List, Optional

# Path to feeds.json (same directory as this script)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FEEDS_FILE = os.path.join(SCRIPT_DIR, '..', 'feeds.json')

# Make ``_common`` importable when launched directly.
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from _common.feed_config import (  # noqa: E402
    add_feed as _add_feed,
    import_opml as _import_opml,
    list_feeds as _list_feeds,
    load_feeds as _load_feeds,
    save_feeds as _save_feeds,
    remove_feed as _remove_feed,
)
from _common.article_extract import (  # noqa: E402
    fetch_article_text as _fetch_article_text,
)
from _common.feed_fetch import (  # noqa: E402
    decode_content as _decode_content,
    enrich_article_text as _enrich_article_text,
    enrich_missing_summaries as _enrich_missing_summaries,
    fetch_all_feeds as _fetch_all_feeds,
    fetch_article_summary as _fetch_article_summary,
    fetch_rss_feed as _fetch_rss_feed,
    fetch_url as _fetch_url,
)
from _common.feed_output import (  # noqa: E402
    dedup_articles,
    output_json,
    output_summary,
    output_text_grouped,
)
from _common.feed_parse import (  # noqa: E402
    extract_html_summary as _extract_html_summary,
    parse_feed as _parse_feed,
)
from _common.runtime_config import (  # noqa: E402
    build_runtime_config_snapshot as _build_runtime_config_snapshot,
    load_pipeline_config as _load_pipeline_config,
)
from _common.text import parse_rss_date, strip_html  # noqa: E402


def load_feeds() -> List[Dict]:
    """Load feed list from feeds.json."""
    return _load_feeds(FEEDS_FILE)


def save_feeds(feeds: List[Dict]) -> None:
    """Save feed list to feeds.json."""
    _save_feeds(feeds, FEEDS_FILE)


def add_feed(name: str, url: str, category: str = "") -> None:
    """Add a feed to feeds.json."""
    _add_feed(name, url, category, FEEDS_FILE)


def remove_feed(name: str) -> bool:
    """Remove a feed from feeds.json by name."""
    return _remove_feed(name, FEEDS_FILE)


def list_feeds() -> None:
    """List all feeds in feeds.json."""
    _list_feeds(FEEDS_FILE)


def import_opml(filepath: str) -> None:
    """Import feeds from an OPML file into feeds.json."""
    _import_opml(filepath, FEEDS_FILE)


# ---------------------------------------------------------------------------
# Date parsing
# ---------------------------------------------------------------------------


def parse_date(date_str: str) -> Optional[datetime]:
    """Parse RSS/Atom date string to timezone-aware datetime object.

    Thin shim that delegates to :func:`_common.text.parse_rss_date`. Kept for
    backward compatibility with existing callers and tests that import this
    function from the ``rss_news_monitor`` module surface.
    """
    return parse_rss_date(date_str)


# ---------------------------------------------------------------------------
# XML / feed parsing helpers
# ---------------------------------------------------------------------------


def clean_summary(description: str, max_chars: int = 0) -> str:
    """Clean HTML from description and optionally truncate to max_chars.

    Thin shim that delegates to :func:`_common.text.strip_html`. Behaviour is
    byte-identical (including the legacy 2000-word soft cap when
    ``max_chars == 0``).
    """
    return strip_html(description, max_chars=max_chars)

parse_feed = _parse_feed
extract_html_summary = _extract_html_summary
fetch_url = _fetch_url
decode_content = _decode_content


def fetch_article_summary(url: str, max_summary: int = 0) -> str:
    """Fetch a linked article page and extract a fallback summary from HTML."""
    return _fetch_article_summary(
        url,
        max_summary,
        fetch_url_fn=fetch_url,
        decode_content_fn=decode_content,
        extract_summary_fn=extract_html_summary,
    )


def enrich_missing_summaries(articles: List[Dict], max_summary: int = 0,
                             max_workers: int = 2,
                             pipeline_config: Optional[Dict[str, Any]] = None) -> None:
    """Backfill empty or too-short summaries from linked article pages."""
    _enrich_missing_summaries(
        articles,
        max_summary,
        max_workers,
        pipeline_config,
        fetch_summary_fn=fetch_article_summary,
    )


def fetch_article_text(url: str, max_words: int) -> str:
    """Fetch a linked article page and extract main body text as words."""
    return _fetch_article_text(
        url,
        max_words,
        fetch_url_fn=fetch_url,
        decode_content_fn=decode_content,
    )


def enrich_article_text(articles: List[Dict],
                        pipeline_config: Optional[Dict[str, Any]] = None) -> None:
    """Backfill ``article_text`` by extracting main body from article pages."""
    _enrich_article_text(
        articles,
        pipeline_config,
        fetch_article_text_fn=fetch_article_text,
    )


def fetch_rss_feed(name: str, url: str, hours: int = 24,
                   max_summary: int = 0):
    """Fetch and parse a single RSS/Atom feed."""
    return _fetch_rss_feed(
        name,
        url,
        hours,
        max_summary,
        fetch_url_fn=fetch_url,
        decode_content_fn=decode_content,
        parse_feed_fn=parse_feed,
    )


def fetch_all_feeds(feed_list: List[Dict], hours: int = 24,
                    max_workers: int = 8,
                    max_summary: int = 0):
    """Concurrently fetch all feeds."""
    return _fetch_all_feeds(
        feed_list,
        hours,
        max_workers,
        max_summary,
        fetch_feed_fn=fetch_rss_feed,
    )


# ---------------------------------------------------------------------------
# CLI argument parsing
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    """Build the argument parser."""
    parser = argparse.ArgumentParser(
        description='RSS News Monitor - Fetch and filter news from multiple RSS/Atom feeds.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
examples:
  %(prog)s                          Fetch past 24h from all configured feeds
  %(prog)s --hours 12               Fetch past 12h
  %(prog)s --json --hours 24        Output as JSON
  %(prog)s --summary                Feed health check
  %(prog)s --list                   List configured feeds
  %(prog)s --add "Name" "URL"       Add a feed
  %(prog)s --remove "Name"          Remove a feed
  %(prog)s --import-opml feeds.opml Import feeds from OPML
  %(prog)s --feeds url1 url2        Use specific feeds (bypass feeds.json)
"""
    )

    parser.add_argument('--hours', type=int, default=24,
                        help='Number of hours to look back (default: 24)')
    parser.add_argument('--json', action='store_true',
                        help='Output as JSON for downstream processing')
    parser.add_argument('--summary', action='store_true',
                        help='Show feed health summary instead of full output')
    parser.add_argument('--list', action='store_true',
                        help='List all configured feeds')
    parser.add_argument('--add', nargs=2, metavar=('NAME', 'URL'),
                        help='Add a new feed to feeds.json')
    parser.add_argument('--remove', metavar='NAME',
                        help='Remove a feed from feeds.json by name')
    parser.add_argument('--feeds', nargs='+', metavar='URL',
                        help='Use specific feed URLs (bypass feeds.json)')
    parser.add_argument('--import-opml', metavar='FILE',
                        help='Import feeds from an OPML file')
    parser.add_argument('--workers', type=int, default=8,
                        help='Max concurrent feed fetches (default: 8)')
    parser.add_argument('--max-summary', type=int, default=0,
                        help='Truncate each article summary to N characters (0 = no truncation). '
                             'Use 300 for compact output suitable for LLM processing.')
    parser.add_argument('--config', metavar='FILE',
                        help='Path to pipeline_config.json (defaults to repo pipeline_config.json)')

    return parser


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = build_parser()
    args = parser.parse_args()

    # Handle --list
    if args.list:
        list_feeds()
        return

    # Handle --add
    if args.add:
        name, url = args.add
        add_feed(name, url)
        return

    # Handle --remove
    if args.remove:
        remove_feed(args.remove)
        return

    # Handle --import-opml
    if args.import_opml:
        import_opml(args.import_opml)
        return

    # Determine feed list
    if args.feeds:
        feed_list = [{'name': url, 'url': url} for url in args.feeds]
    else:
        feed_list = load_feeds()

    try:
        pipeline_config, config_path = _load_pipeline_config(args.config)
    except Exception as exc:
        print(f"Failed to load pipeline config: {exc}", file=sys.stderr)
        sys.exit(1)

    if not feed_list:
        print("No feeds configured.")
        print("Add feeds: --add 'Name' 'https://example.com/feed.xml'")
        print("Or use:    --feeds 'https://example.com/feed.xml'")
        sys.exit(1)

    # Fetch all feeds concurrently
    start_time = time.time()
    all_articles, feed_status = fetch_all_feeds(feed_list, args.hours, args.workers, args.max_summary)
    elapsed = time.time() - start_time

    # Deduplicate
    all_articles = dedup_articles(all_articles)
    enrich_missing_summaries(
        all_articles,
        max_summary=args.max_summary,
        pipeline_config=pipeline_config,
    )
    # article_text enrichment fetches every article URL a second time, so
    # only run it when the output mode actually consumes the field. Today
    # only the JSON output (downstream of --json) carries article_text;
    # --summary and the default grouped-text output do not.
    if args.json:
        enrich_article_text(
            all_articles,
            pipeline_config=pipeline_config,
        )
    config_snapshot = _build_runtime_config_snapshot(
        pipeline_config,
        config_path,
        max_summary=args.max_summary,
    )

    print(f"[INFO] Fetched {len(all_articles)} articles from {len(feed_list)} feeds "
          f"in {elapsed:.1f}s", file=sys.stderr)

    # Output results
    if args.summary:
        output_summary(feed_list, feed_status, all_articles, args.hours)
    elif args.json:
        input_mode = 'cli_feeds' if args.feeds else 'feeds.json'
        output_json(
            all_articles,
            args.hours,
            feed_list,
            feed_status,
            input_mode=input_mode,
            config_snapshot=config_snapshot,
        )
    else:
        output_text_grouped(all_articles, args.hours, feed_list)


if __name__ == '__main__':
    main()
