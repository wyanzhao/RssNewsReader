"""Lightweight main-text extraction for article pages.

This module implements a stdlib-only readability-style extractor. It is
intentionally simple: no third-party dependencies, no DOM library. Given an
HTML document, it walks the stream with ``html.parser.HTMLParser`` and
collects text from block-level elements (``<p>``, ``<li>``, ``<h1>`` .. ``<h6>``,
``<blockquote>``, ``<pre>``), preferring content that lives inside an
``<article>``, ``<main>``, or ``role='main'`` container. Chrome-like regions
(``<nav>``, ``<aside>``, ``<footer>``, ``<header>``, ``<form>``, etc.) and
script/style blocks are dropped wholesale.

The goal is to give downstream LLM summarization a few hundred words of real
article body instead of a SEO meta description. It is best-effort: pages
without an ``<article>``/``<main>`` container fall back to the union of all
``<p>``-like blocks, which is noisier but still far richer than meta tags.
"""

from __future__ import annotations

import re
from html.parser import HTMLParser
from typing import Callable, List, Optional, Tuple

from .feed_fetch import decode_content, fetch_url


BLOCK_TAGS = frozenset({
    "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre",
})
SKIP_TAGS = frozenset({
    "script", "style", "noscript", "nav", "aside", "footer", "header",
    "form", "figure", "button", "iframe", "svg", "select", "template",
    "picture", "source",
})
PREFERRED_CONTAINERS = frozenset({"article", "main"})
DEFAULT_MAX_WORDS = 300


class _MainTextParser(HTMLParser):
    """Collect block-level text, preferring content inside article/main."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._skip_depth = 0
        self._container_depth = 0
        self._saw_container = False
        self._block_stack: List[dict] = []
        self._container_blocks: List[str] = []
        self._fallback_blocks: List[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        tag = tag.lower()
        attr_map = {
            str(key).lower(): value
            for key, value in attrs
            if key and value is not None
        }

        if tag in SKIP_TAGS:
            self._skip_depth += 1
            return
        if self._skip_depth > 0:
            return

        role = str(attr_map.get("role", "")).lower()
        if tag in PREFERRED_CONTAINERS or role == "main":
            self._container_depth += 1
            self._saw_container = True

        if tag in BLOCK_TAGS:
            self._block_stack.append({
                "in_container": self._container_depth > 0,
                "buf": [],
            })

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()

        if tag in SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if self._skip_depth > 0:
            return

        if tag in BLOCK_TAGS and self._block_stack:
            block = self._block_stack.pop()
            text = re.sub(r"\s+", " ", "".join(block["buf"])).strip()
            if text:
                if block["in_container"]:
                    self._container_blocks.append(text)
                self._fallback_blocks.append(text)

        if tag in PREFERRED_CONTAINERS:
            self._container_depth = max(0, self._container_depth - 1)

    def handle_data(self, data: str) -> None:
        if self._skip_depth > 0:
            return
        if not data or self._block_stack is None:
            return
        if self._block_stack:
            self._block_stack[-1]["buf"].append(data)

    def result(self) -> str:
        blocks: List[str]
        if self._saw_container and self._container_blocks:
            blocks = self._container_blocks
        else:
            blocks = self._fallback_blocks
        return "\n".join(blocks)


def extract_main_text(html_content: str) -> str:
    """Return best-effort article body text from HTML content."""
    if not html_content:
        return ""
    parser = _MainTextParser()
    try:
        parser.feed(html_content)
    except Exception:
        # HTMLParser is very forgiving, but malformed inputs can still raise.
        return ""
    return parser.result()


def truncate_words(text: str, max_words: int) -> str:
    """Truncate ``text`` to ``max_words`` whitespace-delimited tokens."""
    if not text:
        return ""
    if max_words <= 0:
        return text
    words = text.split()
    if len(words) <= max_words:
        return " ".join(words)
    return " ".join(words[:max_words]) + "..."


def fetch_article_text(
    url: str,
    max_words: int = DEFAULT_MAX_WORDS,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    extract_main_text_fn: Callable[[str], str] = extract_main_text,
) -> str:
    """Fetch a linked article page and return truncated main body text."""
    raw, charset = fetch_url_fn(
        url,
        timeout=20,
        retries=1,
        headers={
            "Accept": "text/html, application/xhtml+xml",
        },
    )
    content = decode_content_fn(raw, charset)
    body = extract_main_text_fn(content)
    return truncate_words(body, max_words)
