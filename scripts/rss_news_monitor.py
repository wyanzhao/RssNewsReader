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

import sys
import os
import re
import json
import html
import time
import argparse
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Optional, Tuple
from urllib.request import urlopen, Request
from urllib.error import URLError, HTTPError
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict
from html.parser import HTMLParser

# Path to feeds.json (same directory as this script)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FEEDS_FILE = os.path.join(SCRIPT_DIR, '..', 'feeds.json')

# Make ``_common`` importable when launched directly.
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from _common.text import dedup_link_key, parse_rss_date, strip_html  # noqa: E402

# Common Atom namespace (may or may not appear in feeds)
ATOM_NS = '{http://www.w3.org/2005/Atom}'
RSS_NS = '{http://purl.org/rss/1.0/}'
DC_NS = '{http://purl.org/dc/elements/1.1/}'
CONTENT_NS = '{http://purl.org/rss/1.0/modules/content/}'


class _MetaSummaryParser(HTMLParser):
    """Extract standard summary-bearing meta tags from an HTML document."""

    TARGET_KEYS = ("description", "og:description", "twitter:description")

    def __init__(self):
        super().__init__()
        self.meta: Dict[str, str] = {}

    def handle_starttag(self, tag: str, attrs):
        if tag.lower() != 'meta':
            return

        attr_map = {
            str(key).lower(): value
            for key, value in attrs
            if key and value is not None
        }
        content = str(attr_map.get('content', '')).strip()
        if not content:
            return

        for attr_name in ('name', 'property'):
            meta_key = str(attr_map.get(attr_name, '')).lower()
            if meta_key in self.TARGET_KEYS and meta_key not in self.meta:
                self.meta[meta_key] = content

# ---------------------------------------------------------------------------
# Feed management (feeds.json CRUD)
# ---------------------------------------------------------------------------


def load_feeds() -> List[Dict]:
    """Load feed list from feeds.json."""
    try:
        with open(FEEDS_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data.get('feeds', [])
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def save_feeds(feeds: List[Dict]):
    """Save feed list to feeds.json."""
    os.makedirs(os.path.dirname(FEEDS_FILE), exist_ok=True)
    with open(FEEDS_FILE, 'w', encoding='utf-8') as f:
        json.dump({'feeds': feeds}, f, indent=2, ensure_ascii=False)


def add_feed(name: str, url: str, category: str = ''):
    """Add a feed to feeds.json."""
    feeds = load_feeds()
    for feed in feeds:
        if feed['url'] == url:
            print(f"Feed already exists: {feed['name']} ({url})")
            return
    entry = {'name': name, 'url': url}
    if category:
        entry['category'] = category
    feeds.append(entry)
    save_feeds(feeds)
    print(f"Added: {name} -> {url}")


def remove_feed(name: str) -> bool:
    """Remove a feed from feeds.json by name."""
    feeds = load_feeds()
    original_count = len(feeds)
    feeds = [f for f in feeds if f['name'] != name]
    if len(feeds) == original_count:
        print(f"Feed not found: '{name}'")
        list_feeds()
        return False
    save_feeds(feeds)
    print(f"Removed: {name}")
    return True


def list_feeds():
    """List all feeds in feeds.json."""
    feeds = load_feeds()
    if not feeds:
        print("No feeds configured. Add one with: --add 'Name' 'URL'")
        return
    print(f"Configured feeds ({len(feeds)}):")
    print("=" * 60)

    # Group by category if any feed has one
    has_categories = any(f.get('category') for f in feeds)
    if has_categories:
        by_cat = defaultdict(list)
        for f in feeds:
            cat = f.get('category', 'Uncategorized')
            by_cat[cat].append(f)
        idx = 1
        for cat in sorted(by_cat.keys()):
            print(f"\n  [{cat}]")
            for feed in by_cat[cat]:
                print(f"    {idx}. {feed['name']}")
                print(f"       {feed['url']}")
                idx += 1
    else:
        for i, feed in enumerate(feeds, 1):
            print(f"  {i}. {feed['name']}")
            print(f"     {feed['url']}")
    print()


# ---------------------------------------------------------------------------
# OPML import
# ---------------------------------------------------------------------------


def import_opml(filepath: str):
    """Import feeds from an OPML file into feeds.json."""
    try:
        tree = ET.parse(filepath)
    except (ET.ParseError, FileNotFoundError) as e:
        print(f"Error reading OPML file: {e}", file=sys.stderr)
        return

    existing = load_feeds()
    existing_urls = {f['url'] for f in existing}

    new_feeds = []
    for outline in tree.findall('.//outline[@xmlUrl]'):
        url = outline.get('xmlUrl', '')
        if url and url not in existing_urls:
            name = outline.get('title') or outline.get('text', url)
            category = ''
            # Try to get category from parent outline
            parent = None
            for parent_outline in tree.findall('.//outline'):
                if outline in list(parent_outline):
                    parent = parent_outline
                    break
            if parent is not None and parent.get('text'):
                category = parent.get('text', '')

            entry = {'name': name, 'url': url}
            if category:
                entry['category'] = category
            new_feeds.append(entry)
            existing_urls.add(url)

    if not new_feeds:
        print("No new feeds found in OPML file (all already exist or file is empty).")
        return

    existing.extend(new_feeds)
    save_feeds(existing)
    print(f"Imported {len(new_feeds)} new feed(s) from {filepath}:")
    for f in new_feeds:
        cat_str = f" [{f['category']}]" if f.get('category') else ''
        print(f"  + {f['name']}{cat_str}")
        print(f"    {f['url']}")


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


def _get_text(element: Optional[ET.Element]) -> str:
    """Safely extract text content from an XML element."""
    if element is None:
        return ''
    # ElementTree handles CDATA and entities automatically
    return (element.text or '').strip()


def extract_html_summary(content: str, max_chars: int = 0) -> str:
    """Extract a short summary from HTML meta tags.

    Falls back across standard metadata fields commonly populated by article
    pages when RSS/Atom entries omit descriptions entirely.
    """
    parser = _MetaSummaryParser()
    try:
        parser.feed(content)
    except Exception:
        return ''

    for key in _MetaSummaryParser.TARGET_KEYS:
        raw = parser.meta.get(key, '')
        summary = clean_summary(raw, max_chars=max_chars)
        if summary:
            return summary
    return ''


def _find_atom_link(item: ET.Element) -> str:
    """Find the best link in an Atom entry.

    Prefers rel='alternate' (the article link), falls back to
    the first link with an href attribute.
    """
    # Search with namespace first, then without
    for ns in [ATOM_NS, '']:
        # First pass: look for rel="alternate" (or no rel, which defaults to alternate)
        for link_el in item.findall(f'{ns}link'):
            rel = link_el.get('rel', 'alternate')
            if rel == 'alternate':
                href = link_el.get('href', '')
                if href:
                    return href

    # Fallback: any link with href
    for ns in [ATOM_NS, '']:
        link_el = item.find(f'{ns}link')
        if link_el is not None:
            href = link_el.get('href', '')
            if href:
                return href

    # Last resort: <link>text</link>
    return _get_text(item.find(f'{ATOM_NS}link')) or _get_text(item.find('link'))


def parse_feed(content: str, max_summary: int = 0) -> List[Dict]:
    """Parse RSS 2.0 or Atom feed content using ElementTree.

    Returns list of dicts with keys: title, link, pub_date, pub_date_str, summary_en.
    Deduplication is NOT done here — caller is responsible.
    max_summary: if > 0, truncate summary_en to this many characters.
    """
    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        print(f"  [ERROR] XML parse failed: {e}", file=sys.stderr)
        return []

    articles = []

    # Detect feed type
    root_tag = root.tag.lower()
    is_atom = 'feed' in root_tag or ATOM_NS in root.tag

    if is_atom:
        # Atom feed: <feed><entry>
        # Try with namespace first, then without
        items = root.findall(f'{ATOM_NS}entry')
        if not items:
            items = root.findall('entry')
    else:
        # RSS 2.0: <rss><channel><item>
        # RSS 1.0 / RDF: <rdf:RDF><item xmlns="http://purl.org/rss/1.0/">
        items = root.findall('.//item')
        if not items:
            items = root.findall(f'.//{RSS_NS}item')

    for item in items:
        if is_atom:
            # Atom entry parsing
            title = _get_text(item.find(f'{ATOM_NS}title')) or _get_text(item.find('title'))

            # Atom link: prefer rel="alternate"
            link = _find_atom_link(item)

            # Date: <published> or <updated>
            pub_date_str = (
                _get_text(item.find(f'{ATOM_NS}published'))
                or _get_text(item.find(f'{ATOM_NS}updated'))
                or _get_text(item.find('published'))
                or _get_text(item.find('updated'))
            )

            # Summary: <summary> or <content>
            summary = (
                _get_text(item.find(f'{ATOM_NS}summary'))
                or _get_text(item.find(f'{ATOM_NS}content'))
                or _get_text(item.find('summary'))
                or _get_text(item.find('content'))
            )
        else:
            # RSS 2.0 / RSS 1.0 item parsing, with RSS 1.0 namespace fallbacks.
            title = _get_text(item.find('title')) or _get_text(item.find(f'{RSS_NS}title'))
            link = _get_text(item.find('link')) or _get_text(item.find(f'{RSS_NS}link'))
            pub_date_str = (
                _get_text(item.find('pubDate'))
                or _get_text(item.find('published'))
                or _get_text(item.find(f'{DC_NS}date'))
            )
            summary = (
                _get_text(item.find('description'))
                or _get_text(item.find(f'{RSS_NS}description'))
                or _get_text(item.find('summary'))
                or _get_text(item.find('content'))
                or _get_text(item.find(f'{CONTENT_NS}encoded'))
            )

        if not title:
            continue

        pub_date = parse_date(pub_date_str)
        if not pub_date:
            continue

        articles.append({
            'title': html.unescape(title),
            'link': link,
            'pub_date': pub_date,
            'pub_date_str': pub_date.strftime('%Y-%m-%d %H:%M UTC'),
            'summary_en': clean_summary(summary, max_chars=max_summary),
        })

    return articles


# ---------------------------------------------------------------------------
# Network fetching with retry and encoding detection
# ---------------------------------------------------------------------------


def fetch_url(url: str, timeout: int = 30, retries: int = 2,
              headers: Optional[Dict[str, str]] = None) -> Tuple[bytes, Optional[str]]:
    """Fetch URL content with retry on transient errors.

    Returns (raw_bytes, charset_or_none).
    """
    request_headers = {
        'User-Agent': 'Mozilla/5.0 (compatible; RSS Monitor/3.0)',
        'Accept': 'application/rss+xml, application/atom+xml, application/xml, text/xml',
    }
    if headers:
        request_headers.update(headers)
    last_error = None
    for attempt in range(retries + 1):
        try:
            req = Request(url, headers=request_headers)
            with urlopen(req, timeout=timeout) as response:
                raw = response.read()
                charset = response.headers.get_content_charset()
                return raw, charset
        except (HTTPError, URLError, OSError) as e:
            last_error = e
            if attempt < retries:
                time.sleep(1)
    raise last_error  # type: ignore[misc]


def decode_content(raw: bytes, charset: Optional[str]) -> str:
    """Decode raw bytes using detected charset, falling back to utf-8."""
    encoding = charset or 'utf-8'
    try:
        return raw.decode(encoding)
    except (UnicodeDecodeError, LookupError):
        return raw.decode('utf-8', errors='replace')


def fetch_article_summary(url: str, max_summary: int = 0) -> str:
    """Fetch a linked article page and extract a fallback summary from HTML."""
    raw, charset = fetch_url(
        url,
        timeout=20,
        retries=1,
        headers={
            'Accept': 'text/html, application/xhtml+xml',
        },
    )
    content = decode_content(raw, charset)
    return extract_html_summary(content, max_chars=max_summary)


def enrich_missing_summaries(articles: List[Dict], max_summary: int = 0,
                             max_workers: int = 2) -> None:
    """Backfill empty summaries from linked article pages when feeds omit them."""
    missing_links = sorted({
        str(article.get('link', '')).strip()
        for article in articles
        if not article.get('summary_en') and str(article.get('link', '')).strip()
    })
    if not missing_links:
        return

    results: Dict[str, str] = {}
    worker_count = min(max_workers, len(missing_links))
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(fetch_article_summary, link, max_summary): link
            for link in missing_links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ''
                print(f"[WARN] Summary fallback failed for {link}: {exc}", file=sys.stderr)

    for article in articles:
        if article.get('summary_en'):
            continue
        link = str(article.get('link', '')).strip()
        fallback = results.get(link, '')
        if fallback:
            article['summary_en'] = fallback


def fetch_rss_feed(name: str, url: str, hours: int = 24,
                   max_summary: int = 0) -> Tuple[List[Dict], Optional[str]]:
    """Fetch and parse a single RSS/Atom feed.

    Returns (articles, error_message_or_none).
    Articles are NOT deduped — caller handles dedup.
    """
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)

    try:
        raw, charset = fetch_url(url)
        content = decode_content(raw, charset)
        articles = parse_feed(content, max_summary=max_summary)

        # Filter by time
        articles = [a for a in articles if a['pub_date'] >= cutoff]

        # Add source name
        for a in articles:
            a['source'] = name

        return articles, None

    except HTTPError as e:
        msg = f"HTTP {e.code}"
        print(f"[WARN] {name}: {msg} - {url}", file=sys.stderr)
        return [], msg
    except URLError as e:
        msg = f"Connection failed - {e.reason}"
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg
    except Exception as e:
        msg = str(e)
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg


# ---------------------------------------------------------------------------
# Concurrent fetching and deduplication
# ---------------------------------------------------------------------------


def fetch_all_feeds(feed_list: List[Dict], hours: int = 24,
                    max_workers: int = 8,
                    max_summary: int = 0) -> Tuple[List[Dict], Dict[str, Optional[str]]]:
    """Concurrently fetch all feeds.

    Returns (all_articles, {feed_name: error_or_none}).
    """
    all_articles = []
    feed_status: Dict[str, Optional[str]] = {}

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {
            executor.submit(fetch_rss_feed, f['name'], f['url'], hours, max_summary): f['name']
            for f in feed_list
        }
        for future in as_completed(futures):
            name = futures[future]
            try:
                articles, error = future.result()
                all_articles.extend(articles)
                feed_status[name] = error
            except Exception as e:
                feed_status[name] = str(e)
                print(f"[WARN] {name}: {e}", file=sys.stderr)

    return all_articles, feed_status


def dedup_articles(articles: List[Dict]) -> List[Dict]:
    """Deduplicate articles by normalized link."""
    seen = set()
    deduped = []
    for a in articles:
        key = dedup_link_key(a['link'])
        if key and key not in seen:
            seen.add(key)
            deduped.append(a)
        elif not key:
            # Keep articles without links (rare but possible)
            deduped.append(a)
    return deduped


# ---------------------------------------------------------------------------
# Output formatters
# ---------------------------------------------------------------------------


def output_json(articles: List[Dict], hours: int = 24,
                feed_list: Optional[List[Dict]] = None,
                feed_status: Optional[Dict[str, Optional[str]]] = None,
                input_mode: str = 'feeds.json'):
    """Output articles as JSON for downstream processing."""
    articles.sort(key=lambda x: x['pub_date'], reverse=True)
    by_source = defaultdict(int)
    for article in articles:
        by_source[article['source']] += 1

    unique_sources = sorted(by_source.keys())
    generated_at = datetime.now(timezone.utc)
    output = {
        'meta': {
            'generated_at_utc': generated_at.isoformat().replace('+00:00', 'Z'),
            'run_id': f"rss-{generated_at.strftime('%Y%m%dT%H%M%SZ')}-{os.urandom(4).hex()}",
            'input_mode': input_mode,
            'feed_count_expected': len(feed_list) if feed_list is not None else None,
        },
        'hours': hours,
        'count': len(articles),
        'unique_source_count': len(unique_sources),
        'unique_sources': unique_sources,
        'articles': [
            {
                'source': a['source'],
                'title': a['title'],
                'link': a['link'],
                'pub_date': a['pub_date'].isoformat(),
                'summary_en': a['summary_en'],
            }
            for a in articles
        ]
    }

    if feed_list is not None:
        output['configured_feed_count'] = len(feed_list)
        output['configured_feeds'] = [feed['name'] for feed in feed_list]

        feed_results = []
        for feed in feed_list:
            name = feed['name']
            error = (feed_status or {}).get(name)
            article_count = by_source.get(name, 0)
            if error:
                status = 'error'
            elif article_count == 0:
                status = 'empty'
            else:
                status = 'ok'

            feed_results.append({
                'source': name,
                'url': feed['url'],
                'status': status,
                'error': error,
                'article_count': article_count,
            })

        output['feed_results'] = feed_results

    print(json.dumps(output, ensure_ascii=False, indent=2))


def output_text_grouped(articles: List[Dict], hours: int = 24,
                        feed_list: Optional[List[Dict]] = None):
    """Output articles grouped by source, with each group sorted by time."""
    if not articles:
        print(f"\nNo news found in the past {hours} hours from any feed.")
        return

    print("=" * 70)
    print(f"RSS News - Past {hours} Hours | {len(articles)} articles")
    print("=" * 70)

    # Group by source
    by_source = defaultdict(list)
    for a in articles:
        by_source[a['source']].append(a)

    # Sort each group by time (newest first)
    for source in by_source:
        by_source[source].sort(key=lambda x: x['pub_date'], reverse=True)

    # Build source order: sources with articles first (sorted by article count desc),
    # then empty sources from feed_list
    source_order = sorted(by_source.keys(), key=lambda s: -len(by_source[s]))

    # Add empty sources from feed_list if provided
    if feed_list:
        all_source_names = [f['name'] for f in feed_list]
        for name in all_source_names:
            if name not in by_source:
                source_order.append(name)

    global_idx = 1
    for source in source_order:
        source_articles = by_source.get(source, [])
        print(f"\n--- [{source}] ({len(source_articles)}篇) ---\n")

        if not source_articles:
            print("  (No articles in this time range)")
            print()
            continue

        for a in source_articles:
            print(f"  {global_idx}. {a['title']}")
            print(f"     Time:  {a['pub_date_str']}")
            print(f"     Link:  {a['link']}")
            if a['summary_en']:
                print(f"     Summary: {a['summary_en']}")
            print()
            global_idx += 1

    print("=" * 70)
    print(f"Total: {len(articles)} article(s) from {len(by_source)} source(s)")


def output_summary(feed_list: List[Dict], feed_status: Dict[str, Optional[str]],
                   articles: List[Dict], hours: int = 24):
    """Output a summary / health check of feed status."""
    by_source = defaultdict(int)
    for a in articles:
        by_source[a['source']] += 1

    print("=" * 60)
    print(f"Feed Health Summary (past {hours}h)")
    print("=" * 60)
    print()

    total_articles = 0
    ok_count = 0
    warn_count = 0
    fail_count = 0

    for feed in feed_list:
        name = feed['name']
        error = feed_status.get(name)
        count = by_source.get(name, 0)
        total_articles += count

        if error:
            print(f"  ❌ {name}: FAILED ({error})")
            fail_count += 1
        elif count == 0:
            print(f"  ⚠️  {name}: 0 articles")
            warn_count += 1
        else:
            print(f"  ✅ {name}: {count} articles")
            ok_count += 1

    print()
    print(f"  Total: {total_articles} articles | "
          f"✅ {ok_count} OK | ⚠️ {warn_count} empty | ❌ {fail_count} failed")
    print()


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
    enrich_missing_summaries(all_articles, max_summary=args.max_summary)

    print(f"[INFO] Fetched {len(all_articles)} articles from {len(feed_list)} feeds "
          f"in {elapsed:.1f}s", file=sys.stderr)

    # Output results
    if args.summary:
        output_summary(feed_list, feed_status, all_articles, args.hours)
    elif args.json:
        input_mode = 'cli_feeds' if args.feeds else 'feeds.json'
        output_json(all_articles, args.hours, feed_list, feed_status, input_mode=input_mode)
    else:
        output_text_grouped(all_articles, args.hours, feed_list)


if __name__ == '__main__':
    main()
