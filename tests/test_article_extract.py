"""Offline tests for ``scripts/_common/article_extract.py``.

The module itself does no network I/O in these tests; ``fetch_article_text``
is exercised via a fake ``fetch_url`` / ``decode_content`` chain.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common import article_extract  # noqa: E402


class ExtractMainTextTests(unittest.TestCase):
    def test_prefers_article_container_over_sidebar(self):
        html = """
        <html><body>
          <nav><p>Home</p><p>About</p></nav>
          <aside><p>Subscribe to our newsletter!</p></aside>
          <article>
            <h1>Breaking News</h1>
            <p>OpenAI announced a new model today.</p>
            <p>It costs $1.5 billion to train.</p>
          </article>
          <footer><p>&copy; 2026 Example</p></footer>
        </body></html>
        """
        text = article_extract.extract_main_text(html)
        self.assertIn("Breaking News", text)
        self.assertIn("OpenAI announced a new model today.", text)
        self.assertIn("1.5 billion", text)
        self.assertNotIn("Home", text)
        self.assertNotIn("Subscribe", text)
        self.assertNotIn("2026 Example", text)

    def test_falls_back_to_all_paragraphs_when_no_container(self):
        html = """
        <html><body>
          <div><p>First paragraph content.</p></div>
          <div><p>Second paragraph content.</p></div>
          <script>var x = 1;</script>
        </body></html>
        """
        text = article_extract.extract_main_text(html)
        self.assertIn("First paragraph content.", text)
        self.assertIn("Second paragraph content.", text)
        self.assertNotIn("var x", text)

    def test_drops_script_and_style_content(self):
        html = """
        <html><head><style>.a { color: red; }</style></head>
        <body><article><p>Readable body.</p><script>alert(1);</script></article></body></html>
        """
        text = article_extract.extract_main_text(html)
        self.assertEqual(text.strip(), "Readable body.")

    def test_unescapes_html_entities(self):
        html = "<article><p>Apple &amp; Google &mdash; deal closes.</p></article>"
        text = article_extract.extract_main_text(html)
        self.assertIn("Apple & Google", text)
        self.assertIn("deal closes", text)

    def test_collapses_whitespace_within_paragraph(self):
        html = "<article><p>  many    spaces\n   and\tlines  </p></article>"
        text = article_extract.extract_main_text(html)
        self.assertEqual(text.strip(), "many spaces and lines")

    def test_empty_html_returns_empty(self):
        self.assertEqual(article_extract.extract_main_text(""), "")
        self.assertEqual(article_extract.extract_main_text("<html></html>"), "")

    def test_role_main_counts_as_container(self):
        html = """
        <html><body>
          <div><p>Sidebar blurb.</p></div>
          <section role="main"><p>Main story body.</p></section>
        </body></html>
        """
        text = article_extract.extract_main_text(html)
        self.assertIn("Main story body.", text)
        self.assertNotIn("Sidebar blurb.", text)

    def test_role_main_container_depth_decrements_on_closing_tag(self):
        """Regression: closing a ``role='main'`` section (on a non-<main>/<article>
        tag) must decrement the container depth. Otherwise post-body
        boilerplate below the section is still treated as preferred content
        and leaks into ``article_text``.
        """
        html = """
        <html><body>
          <section role="main"><p>Main story body.</p></section>
          <div><p>Related links below the main section.</p></div>
        </body></html>
        """
        text = article_extract.extract_main_text(html)
        self.assertIn("Main story body.", text)
        self.assertNotIn("Related links below the main section.", text)


class TruncateWordsTests(unittest.TestCase):
    def test_truncates_when_over_limit(self):
        text = "one two three four five six"
        self.assertEqual(
            article_extract.truncate_words(text, 3),
            "one two three...",
        )

    def test_does_not_truncate_when_under_limit(self):
        text = "one two"
        self.assertEqual(article_extract.truncate_words(text, 5), "one two")

    def test_zero_max_words_returns_full_text(self):
        text = "one two three"
        self.assertEqual(article_extract.truncate_words(text, 0), "one two three")

    def test_empty_input_returns_empty(self):
        self.assertEqual(article_extract.truncate_words("", 10), "")


class FetchArticleTextTests(unittest.TestCase):
    def test_end_to_end_with_fake_fetcher(self):
        html = (
            "<html><body><article>"
            "<p>Paragraph one with enough words to exceed the limit.</p>"
            "<p>Paragraph two continues the story.</p>"
            "</article></body></html>"
        )

        def fake_fetch(url, timeout=0, retries=0, headers=None):
            return html.encode("utf-8"), "utf-8"

        def fake_decode(raw, charset):
            return raw.decode(charset or "utf-8")

        result = article_extract.fetch_article_text(
            "https://example.com/story",
            max_words=6,
            fetch_url_fn=fake_fetch,
            decode_content_fn=fake_decode,
        )
        self.assertEqual(result, "Paragraph one with enough words to...")

    def test_fetch_failure_propagates(self):
        def fake_fetch(url, timeout=0, retries=0, headers=None):
            raise OSError("connection refused")

        with self.assertRaises(OSError):
            article_extract.fetch_article_text(
                "https://example.com/story",
                max_words=50,
                fetch_url_fn=fake_fetch,
            )


if __name__ == "__main__":
    unittest.main()
