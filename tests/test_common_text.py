"""Stage-1 unit tests for scripts/_common/text.py.

These pin the byte-level behaviour of the migrated helpers so that future
internal cleanups cannot silently regress raw.json shape.
"""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.text import (  # noqa: E402
    DEFAULT_SUMMARY_WORD_CAP,
    dedup_link_key,
    parse_rss_date,
    strip_html,
)


class StripHtmlTests(unittest.TestCase):
    def test_empty_returns_empty_string(self):
        self.assertEqual(strip_html(""), "")
        self.assertEqual(strip_html(None), "")  # type: ignore[arg-type]

    def test_strips_tags_and_unescapes_entities(self):
        raw = "<p>Hello&nbsp;<b>world</b> &amp; more</p>"
        # `&nbsp;` decodes to U+00A0; the subsequent `\s+` -> " " collapse
        # turns it into a regular space (legacy behaviour).
        self.assertEqual(strip_html(raw), "Hello world & more")

    def test_collapses_whitespace(self):
        self.assertEqual(strip_html("a\n\n   b\t\tc"), "a b c")

    def test_max_chars_truncates_at_word_boundary_with_ellipsis(self):
        text = "alpha beta gamma delta epsilon"
        # max_chars 13 -> "alpha beta..."
        self.assertEqual(strip_html(text, max_chars=13), "alpha beta...")

    def test_max_chars_zero_uses_word_cap(self):
        body = " ".join(f"w{i}" for i in range(DEFAULT_SUMMARY_WORD_CAP + 5))
        out = strip_html(body, max_chars=0)
        # Legacy joins kept words then appends "..." with no separator, so the
        # tail token becomes ``wN...`` and the word count stays at the cap.
        self.assertTrue(out.endswith("..."))
        self.assertEqual(len(out.split()), DEFAULT_SUMMARY_WORD_CAP)

    def test_short_text_below_cap_unchanged(self):
        self.assertEqual(strip_html("short body"), "short body")


class ParseRssDateTests(unittest.TestCase):
    def test_returns_none_for_empty(self):
        self.assertIsNone(parse_rss_date(""))
        self.assertIsNone(parse_rss_date(None))  # type: ignore[arg-type]

    def test_rfc_2822_round_trip(self):
        dt = parse_rss_date("Fri, 10 Apr 2026 21:43:33 +0000")
        self.assertIsNotNone(dt)
        self.assertEqual(dt.tzinfo, timezone.utc)
        self.assertEqual(dt.replace(tzinfo=timezone.utc),
                         datetime(2026, 4, 10, 21, 43, 33, tzinfo=timezone.utc))

    def test_iso_8601_with_z_suffix(self):
        dt = parse_rss_date("2026-04-10T21:43:33Z")
        self.assertIsNotNone(dt)
        self.assertEqual(dt, datetime(2026, 4, 10, 21, 43, 33, tzinfo=timezone.utc))

    def test_iso_8601_with_offset(self):
        dt = parse_rss_date("2026-04-10T14:57:17-07:00")
        self.assertIsNotNone(dt)
        self.assertEqual(dt.utcoffset().total_seconds(), -7 * 3600)

    def test_fallback_format_assigns_utc(self):
        dt = parse_rss_date("2026-04-10 12:00:00 UTC")
        self.assertIsNotNone(dt)
        self.assertEqual(dt.tzinfo, timezone.utc)

    def test_plain_date_assigns_utc_midnight(self):
        dt = parse_rss_date("2026-04-10")
        self.assertIsNotNone(dt)
        self.assertEqual(dt, datetime(2026, 4, 10, 0, 0, 0, tzinfo=timezone.utc))

    def test_garbage_returns_none(self):
        self.assertIsNone(parse_rss_date("not a date"))


class DedupLinkKeyTests(unittest.TestCase):
    def test_strips_single_trailing_slash(self):
        self.assertEqual(
            dedup_link_key("https://example.com/path/"),
            "https://example.com/path",
        )

    def test_no_trailing_slash_unchanged(self):
        self.assertEqual(
            dedup_link_key("https://example.com/path"),
            "https://example.com/path",
        )

    def test_empty_returns_empty(self):
        self.assertEqual(dedup_link_key(""), "")
        self.assertEqual(dedup_link_key(None), "")  # type: ignore[arg-type]

    def test_distinguishes_query_and_fragment(self):
        # Stage-1 behaviour intentionally does NOT normalize query order or
        # case. Stage 3 may upgrade. Lock that we are still in legacy mode.
        a = dedup_link_key("https://example.com/x?a=1&b=2")
        b = dedup_link_key("https://example.com/x?b=2&a=1")
        self.assertNotEqual(a, b)


class FetchPathRegressionTests(unittest.TestCase):
    """Catch regressions in rss_news_monitor.py when stage-1+ refactors
    accidentally remove an import the fetch path still uses (e.g. ``html``).

    Prior bug (caught in production): we removed ``import html`` from
    rss_news_monitor.py while migrating clean_summary to _common.text, but
    parse_feed still called html.unescape(title) directly — every feed
    failed with ``NameError: name 'html' is not defined``.
    """

    def test_parse_feed_runs_without_nameerror_on_minimal_rss(self):
        """Smoke-import rss_news_monitor and exercise its parse_feed path
        with a self-contained RSS document containing an HTML entity in the
        title. This will raise NameError if ``import html`` (or any other
        fetch-path import) is missing."""
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "rss_news_monitor",
            SCRIPTS / "rss_news_monitor.py",
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)

        # ``html`` must be reachable as a module attribute — guards against
        # someone re-deleting the import in a future refactor.
        self.assertTrue(hasattr(module, "html"),
                        "rss_news_monitor.py must keep `import html`; "
                        "parse_feed -> html.unescape(title) depends on it.")

        # Use a future pub_date so it survives the --hours filter.
        future = (datetime(2099, 1, 1, tzinfo=timezone.utc)
                  .strftime("%a, %d %b %Y %H:%M:%S +0000"))
        rss_xml = (
            "<?xml version='1.0' encoding='UTF-8'?>"
            "<rss version='2.0'><channel>"
            "<title>fake</title><link>https://example.com</link>"
            "<description>fake</description>"
            "<item>"
            "<title>Hello &amp; goodbye</title>"
            "<link>https://example.com/article</link>"
            f"<pubDate>{future}</pubDate>"
            "<description>Body with &lt;b&gt;tags&lt;/b&gt;.</description>"
            "</item>"
            "</channel></rss>"
        )

        articles = module.parse_feed(rss_xml, max_summary=200)
        self.assertEqual(len(articles), 1)
        # Verify html.unescape actually ran on the title
        self.assertEqual(articles[0]["title"], "Hello & goodbye")
        self.assertEqual(articles[0]["link"], "https://example.com/article")

    def test_parse_feed_supports_rdf_rss_with_dc_date(self):
        """RSS 1.0 / RDF feeds such as Nature use a default RSS namespace,
        ``dc:date`` for publication date, and ``content:encoded`` for body."""
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "rss_news_monitor",
            SCRIPTS / "rss_news_monitor.py",
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)

        rdf_xml = """<?xml version='1.0' encoding='UTF-8'?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:dc="http://purl.org/dc/elements/1.1/"
         xmlns:content="http://purl.org/rss/1.0/modules/content/"
         xmlns="http://purl.org/rss/1.0/">
  <channel rdf:about="https://example.com/feed">
    <title>Example RDF Feed</title>
    <link>https://example.com/feed</link>
    <description>Example</description>
  </channel>
  <item rdf:about="https://example.com/paper">
    <title><![CDATA[Signal from RDF feed]]></title>
    <link>https://example.com/paper</link>
    <dc:date>2099-01-01</dc:date>
    <content:encoded><![CDATA[<p>Research summary with <b>markup</b>.</p>]]></content:encoded>
  </item>
</rdf:RDF>
"""

        articles = module.parse_feed(rdf_xml, max_summary=200)
        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0]["title"], "Signal from RDF feed")
        self.assertEqual(articles[0]["link"], "https://example.com/paper")
        self.assertEqual(articles[0]["pub_date"], datetime(2099, 1, 1, 0, 0, tzinfo=timezone.utc))
        self.assertEqual(articles[0]["summary_en"], "Research summary with markup.")

    def test_extract_html_summary_prefers_standard_meta_tags(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "rss_news_monitor",
            SCRIPTS / "rss_news_monitor.py",
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)

        html_doc = """<!doctype html>
<html>
  <head>
    <meta property="og:description" content="Open graph fallback">
    <meta name="description" content="Primary meta summary">
  </head>
</html>
"""
        self.assertEqual(
            module.extract_html_summary(html_doc, max_chars=200),
            "Primary meta summary",
        )

    def test_enrich_missing_summaries_backfills_empty_entries(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "rss_news_monitor",
            SCRIPTS / "rss_news_monitor.py",
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)

        original = module.fetch_article_summary
        try:
            module.fetch_article_summary = lambda url, max_summary=0: "Filled from article page"
            articles = [
                {
                    "title": "Example",
                    "link": "https://example.com/post",
                    "summary_en": "",
                },
                {
                    "title": "Already populated",
                    "link": "https://example.com/post-2",
                    "summary_en": "Keep existing",
                },
            ]
            module.enrich_missing_summaries(articles, max_summary=120, max_workers=2)
            self.assertEqual(articles[0]["summary_en"], "Filled from article page")
            self.assertEqual(articles[1]["summary_en"], "Keep existing")
        finally:
            module.fetch_article_summary = original


if __name__ == "__main__":
    unittest.main()
