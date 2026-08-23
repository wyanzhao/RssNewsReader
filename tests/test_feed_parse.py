"""Unit tests for scripts/_common/feed_parse.py security guard.

ElementTree does not fetch external entities, but it expands internal general
entities, so untrusted feed XML must be rejected when it declares entities
("billion laughs" amplification). These tests pin both directions of that
policy offline.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.feed_parse import (  # noqa: E402
    FeedParseError,
    contains_entity_declaration,
    parse_feed,
)


BILLION_LAUGHS = """<?xml version="1.0"?>
<!DOCTYPE rss [
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
]>
<rss version="2.0"><channel><item>
  <title>&lol2;</title><link>https://x/1</link>
</item></channel></rss>
"""

PLAIN_RSS = """<?xml version="1.0"?>
<rss version="2.0"><channel>
  <item><title>Hello</title><link>https://x/1</link>
  <pubDate>Sat, 22 Aug 2026 10:00:00 +0000</pubDate>
  <description>A plain item with no entities.</description></item>
</channel></rss>
"""


class EntityDeclarationGuardTests(unittest.TestCase):
    def test_billion_laughs_document_is_rejected(self):
        with self.assertRaises(FeedParseError):
            parse_feed(BILLION_LAUGHS)

    def test_single_entity_declaration_is_rejected(self):
        doc = '<!DOCTYPE rss [<!ENTITY x "y">]><rss><channel/></rss>'
        with self.assertRaises(FeedParseError):
            parse_feed(doc)

    def test_rejection_is_case_insensitive(self):
        self.assertTrue(contains_entity_declaration("<!doctype x [<!entity a \"b\">]>"))

    def test_bytes_input_is_checked(self):
        self.assertTrue(contains_entity_declaration(b"<!ENTITY boom"))
        self.assertFalse(contains_entity_declaration(b"<rss/>"))

    def test_plain_feed_still_parses(self):
        articles = parse_feed(PLAIN_RSS)
        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0]["title"], "Hello")

    def test_escaped_entity_text_is_not_a_declaration(self):
        # Escaped content text (&lt;!ENTITY) is what a legitimate article about
        # XML would carry; only a raw declaration in the DTD is dangerous.
        doc = PLAIN_RSS.replace("no entities", "&lt;!ENTITY escaped is fine&gt;")
        articles = parse_feed(doc)
        self.assertEqual(len(articles), 1)


if __name__ == "__main__":
    unittest.main()
