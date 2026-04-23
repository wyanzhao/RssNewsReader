"""Feed configuration helpers for rss_news_monitor.py.

These functions are extracted from the original monolithic monitor script with
behaviour kept intentionally stable. The CLI layer still owns which feeds file
path to use; this module provides the reusable implementation.
"""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Dict, List


DEFAULT_FEEDS_FILE = Path(__file__).resolve().parents[2] / "feeds.json"


def load_feeds(feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> List[Dict]:
    """Load feed list from feeds.json."""
    try:
        with Path(feeds_file).open("r", encoding="utf-8") as handle:
            data = json.load(handle)
            return data.get("feeds", [])
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def save_feeds(feeds: List[Dict], feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> None:
    """Save feed list to feeds.json."""
    path = Path(feeds_file)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"feeds": feeds}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )


def add_feed(name: str, url: str, category: str = "",
             feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> None:
    """Add a feed to feeds.json."""
    feeds = load_feeds(feeds_file)
    for feed in feeds:
        if feed["url"] == url:
            print(f"Feed already exists: {feed['name']} ({url})")
            return
    entry = {"name": name, "url": url}
    if category:
        entry["category"] = category
    feeds.append(entry)
    save_feeds(feeds, feeds_file)
    print(f"Added: {name} -> {url}")


def remove_feed(name: str, feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> bool:
    """Remove a feed from feeds.json by name."""
    feeds = load_feeds(feeds_file)
    original_count = len(feeds)
    feeds = [feed for feed in feeds if feed["name"] != name]
    if len(feeds) == original_count:
        print(f"Feed not found: '{name}'")
        list_feeds(feeds_file)
        return False
    save_feeds(feeds, feeds_file)
    print(f"Removed: {name}")
    return True


def list_feeds(feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> None:
    """List all feeds in feeds.json."""
    feeds = load_feeds(feeds_file)
    if not feeds:
        print("No feeds configured. Add one with: --add 'Name' 'URL'")
        return
    print(f"Configured feeds ({len(feeds)}):")
    print("=" * 60)

    has_categories = any(feed.get("category") for feed in feeds)
    if has_categories:
        by_category = defaultdict(list)
        for feed in feeds:
            category = feed.get("category", "Uncategorized")
            by_category[category].append(feed)
        idx = 1
        for category in sorted(by_category.keys()):
            print(f"\n  [{category}]")
            for feed in by_category[category]:
                print(f"    {idx}. {feed['name']}")
                print(f"       {feed['url']}")
                idx += 1
    else:
        for index, feed in enumerate(feeds, 1):
            print(f"  {index}. {feed['name']}")
            print(f"     {feed['url']}")
    print()


def import_opml(filepath: str, feeds_file: str | Path = DEFAULT_FEEDS_FILE) -> None:
    """Import feeds from an OPML file into feeds.json."""
    try:
        tree = ET.parse(filepath)
    except (ET.ParseError, FileNotFoundError) as exc:
        print(f"Error reading OPML file: {exc}", file=sys.stderr)
        return

    existing = load_feeds(feeds_file)
    existing_urls = {feed["url"] for feed in existing}

    new_feeds = []
    for outline in tree.findall(".//outline[@xmlUrl]"):
        url = outline.get("xmlUrl", "")
        if url and url not in existing_urls:
            name = outline.get("title") or outline.get("text", url)
            category = ""
            parent = None
            for parent_outline in tree.findall(".//outline"):
                if outline in list(parent_outline):
                    parent = parent_outline
                    break
            if parent is not None and parent.get("text"):
                category = parent.get("text", "")

            entry = {"name": name, "url": url}
            if category:
                entry["category"] = category
            new_feeds.append(entry)
            existing_urls.add(url)

    if not new_feeds:
        print("No new feeds found in OPML file (all already exist or file is empty).")
        return

    existing.extend(new_feeds)
    save_feeds(existing, feeds_file)
    print(f"Imported {len(new_feeds)} new feed(s) from {filepath}:")
    for feed in new_feeds:
        category_suffix = f" [{feed['category']}]" if feed.get("category") else ""
        print(f"  + {feed['name']}{category_suffix}")
        print(f"    {feed['url']}")
