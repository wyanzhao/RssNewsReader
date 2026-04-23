"""XML and HTML summary parsing helpers for rss_news_monitor.py."""

from __future__ import annotations

import html
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from html.parser import HTMLParser
from typing import Dict, List, Optional

from .text import parse_rss_date, strip_html


ATOM_NS = "{http://www.w3.org/2005/Atom}"
RSS_NS = "{http://purl.org/rss/1.0/}"
DC_NS = "{http://purl.org/dc/elements/1.1/}"
CONTENT_NS = "{http://purl.org/rss/1.0/modules/content/}"


class _MetaSummaryParser(HTMLParser):
    """Extract standard summary-bearing meta tags from an HTML document."""

    TARGET_KEYS = ("description", "og:description", "twitter:description")

    def __init__(self):
        super().__init__()
        self.meta: Dict[str, str] = {}

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() != "meta":
            return

        attr_map = {
            str(key).lower(): value
            for key, value in attrs
            if key and value is not None
        }
        content = str(attr_map.get("content", "")).strip()
        if not content:
            return

        for attr_name in ("name", "property"):
            meta_key = str(attr_map.get(attr_name, "")).lower()
            if meta_key in self.TARGET_KEYS and meta_key not in self.meta:
                self.meta[meta_key] = content


def _get_text(element: Optional[ET.Element]) -> str:
    """Safely extract text content from an XML element."""
    if element is None:
        return ""
    return (element.text or "").strip()


def extract_html_summary(content: str, max_chars: int = 0) -> str:
    """Extract a short summary from HTML meta tags."""
    parser = _MetaSummaryParser()
    try:
        parser.feed(content)
    except Exception:
        return ""

    for key in _MetaSummaryParser.TARGET_KEYS:
        raw = parser.meta.get(key, "")
        summary = strip_html(raw, max_chars=max_chars)
        if summary:
            return summary
    return ""


def _find_atom_link(item: ET.Element) -> str:
    """Find the best link in an Atom entry."""
    for namespace in [ATOM_NS, ""]:
        for link_el in item.findall(f"{namespace}link"):
            rel = link_el.get("rel", "alternate")
            if rel == "alternate":
                href = link_el.get("href", "")
                if href:
                    return href

    for namespace in [ATOM_NS, ""]:
        link_el = item.find(f"{namespace}link")
        if link_el is not None:
            href = link_el.get("href", "")
            if href:
                return href

    return _get_text(item.find(f"{ATOM_NS}link")) or _get_text(item.find("link"))


def parse_feed(content: str, max_summary: int = 0) -> List[Dict]:
    """Parse RSS 2.0 or Atom feed content using ElementTree."""
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        print(f"  [ERROR] XML parse failed: {exc}", file=sys.stderr)
        return []

    articles = []
    root_tag = root.tag.lower()
    is_atom = "feed" in root_tag or ATOM_NS in root.tag

    if is_atom:
        items = root.findall(f"{ATOM_NS}entry")
        if not items:
            items = root.findall("entry")
    else:
        items = root.findall(".//item")
        if not items:
            items = root.findall(f".//{RSS_NS}item")

    for item in items:
        if is_atom:
            title = _get_text(item.find(f"{ATOM_NS}title")) or _get_text(item.find("title"))
            link = _find_atom_link(item)
            pub_date_str = (
                _get_text(item.find(f"{ATOM_NS}published"))
                or _get_text(item.find(f"{ATOM_NS}updated"))
                or _get_text(item.find("published"))
                or _get_text(item.find("updated"))
            )
            summary = (
                _get_text(item.find(f"{ATOM_NS}summary"))
                or _get_text(item.find(f"{ATOM_NS}content"))
                or _get_text(item.find("summary"))
                or _get_text(item.find("content"))
            )
        else:
            title = _get_text(item.find("title")) or _get_text(item.find(f"{RSS_NS}title"))
            link = _get_text(item.find("link")) or _get_text(item.find(f"{RSS_NS}link"))
            pub_date_str = (
                _get_text(item.find("pubDate"))
                or _get_text(item.find("published"))
                or _get_text(item.find(f"{DC_NS}date"))
            )
            summary = (
                _get_text(item.find("description"))
                or _get_text(item.find(f"{RSS_NS}description"))
                or _get_text(item.find("summary"))
                or _get_text(item.find("content"))
                or _get_text(item.find(f"{CONTENT_NS}encoded"))
            )

        if not title:
            continue

        pub_date = parse_rss_date(pub_date_str)
        if not pub_date:
            continue

        articles.append({
            "title": html.unescape(title),
            "link": link,
            "pub_date": pub_date,
            "pub_date_str": pub_date.strftime("%Y-%m-%d %H:%M UTC"),
            "summary_en": strip_html(summary, max_chars=max_summary),
        })

    return articles
