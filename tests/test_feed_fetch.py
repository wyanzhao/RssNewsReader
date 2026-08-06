"""Unit tests for scripts/_common/feed_fetch.py.

These pin the behaviour of the network fetch path (feed fetch, feed-status
aggregation, summary fallback, article-body extraction, and the merged
single-fetch enrichment). The fetch path is otherwise only exercised by the
real-network end-to-end smoke, so these tests are the safety net that makes
fetch-path refactors (notably the single-fetch ``enrich_article_pages`` merge)
reviewable offline.

All network access is stubbed via injected ``*_fn`` callables; no test here
opens a socket.
"""

from __future__ import annotations

import io
import sys
import unittest
from datetime import datetime, timedelta, timezone
from http.client import IncompleteRead
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError, URLError


def _http_error(url: str, code: int, msg: str) -> HTTPError:
    # Pass a real (empty) fp and close it up front. CPython still allocates a
    # tempfile-backed body behind HTTPError, and an unclosed one emits a
    # ResourceWarning whenever the exception is garbage-collected. Closing at
    # construction releases it while leaving .code / .msg readable, which is all
    # the fetch path ever touches.
    error = HTTPError(url, code, msg, hdrs={}, fp=io.BytesIO(b""))
    error.close()
    return error


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common import feed_fetch  # noqa: E402
from _common.feed_fetch import (  # noqa: E402
    enrich_article_pages,
    enrich_article_text,
    enrich_missing_summaries,
    fetch_all_feeds,
    fetch_rss_feed,
)


def _article(link: str, *, pub_date: datetime, summary_en: str = "",
             title: str = "T", source: str = "S") -> dict:
    return {
        "title": title,
        "link": link,
        "source": source,
        "pub_date": pub_date,
        "summary_en": summary_en,
    }


class _Headers:
    def __init__(self, charset: str | None) -> None:
        self._charset = charset

    def get_content_charset(self):
        return self._charset


class _FakeResponse:
    def __init__(self, body: bytes = b"<rss/>", charset: str | None = "utf-8") -> None:
        self._body = body
        self.headers = _Headers(charset)

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


class FetchUrlRetryTests(unittest.TestCase):
    """The retry predicate: what counts as transient and what does not.

    ``fetch_url`` is the one place in the pipeline that decides whether a failed
    request is worth repeating. Both directions of that decision are load-bearing
    and neither shows up in the end-to-end smoke, so they are pinned here.
    """

    def _run(self, urlopen_fn, **kwargs):
        attempts: list = []

        def counting(req, timeout=None):
            attempts.append(req)
            return urlopen_fn(len(attempts), req)

        with mock.patch.object(feed_fetch, "urlopen", counting), \
                mock.patch.object(feed_fetch.time, "sleep"):
            try:
                result = feed_fetch.fetch_url("https://x/feed.xml", **kwargs)
                return attempts, result, None
            except Exception as exc:  # noqa: BLE001 - the error is the assertion target
                return attempts, None, exc

    def test_incomplete_read_is_retried_and_can_succeed(self):
        # The regression this guards: IncompleteRead descends from HTTPException,
        # not OSError, so the original retry tuple never caught it and a
        # mid-transfer truncation became a permanent feed error on the first try.
        def flaky(attempt, _req):
            if attempt == 1:
                raise IncompleteRead(b"half a doc")
            return _FakeResponse(b"<rss/>")

        attempts, result, error = self._run(flaky)
        self.assertIsNone(error)
        self.assertEqual(result, (b"<rss/>", "utf-8"))
        self.assertEqual(len(attempts), 2)

    def test_incomplete_read_still_propagates_after_exhausting_retries(self):
        def always(_attempt, _req):
            raise IncompleteRead(b"half a doc")

        attempts, _result, error = self._run(always)
        self.assertIsInstance(error, IncompleteRead)
        self.assertEqual(len(attempts), 3)

    def test_client_error_fails_fast_without_retrying(self):
        def blocked(_attempt, _req):
            raise _http_error("https://x/feed.xml", 403, "Forbidden")

        attempts, _result, error = self._run(blocked)
        self.assertIsInstance(error, HTTPError)
        self.assertEqual(error.code, 403)
        self.assertEqual(len(attempts), 1, "a 403 cannot change on an identical retry")

    def test_rate_limit_is_still_retried(self):
        def throttled(_attempt, _req):
            raise _http_error("https://x/feed.xml", 429, "Too Many Requests")

        attempts, _result, error = self._run(throttled)
        self.assertEqual(error.code, 429)
        self.assertEqual(len(attempts), 3, "429 is the one 4xx where waiting helps")

    def test_server_error_is_still_retried(self):
        def flaky(_attempt, _req):
            raise _http_error("https://x/feed.xml", 503, "Service Unavailable")

        attempts, _result, error = self._run(flaky)
        self.assertEqual(error.code, 503)
        self.assertEqual(len(attempts), 3)

    def test_connection_error_is_still_retried(self):
        def offline(_attempt, _req):
            raise URLError("name resolution failed")

        attempts, _result, error = self._run(offline)
        self.assertIsInstance(error, URLError)
        self.assertEqual(len(attempts), 3)

    def test_default_user_agent_is_sent_when_no_override(self):
        attempts, _result, error = self._run(lambda _a, _r: _FakeResponse())
        self.assertIsNone(error)
        self.assertEqual(
            attempts[0].get_header("User-agent"), feed_fetch.DEFAULT_USER_AGENT,
        )

    def test_user_agent_override_replaces_the_default(self):
        attempts, _result, error = self._run(
            lambda _a, _r: _FakeResponse(), user_agent="CustomReader/1.0",
        )
        self.assertIsNone(error)
        self.assertEqual(attempts[0].get_header("User-agent"), "CustomReader/1.0")

    def test_blank_user_agent_falls_back_to_the_default(self):
        attempts, _result, _error = self._run(
            lambda _a, _r: _FakeResponse(), user_agent="",
        )
        self.assertEqual(
            attempts[0].get_header("User-agent"), feed_fetch.DEFAULT_USER_AGENT,
        )


class FetchRssFeedTests(unittest.TestCase):
    def test_success_filters_articles_outside_window(self):
        now = datetime.now(timezone.utc)
        recent = now - timedelta(hours=1)
        stale = now - timedelta(hours=48)

        def fake_parse(content, max_summary=0):
            return [
                {"title": "fresh", "link": "https://x/1", "pub_date": recent},
                {"title": "old", "link": "https://x/2", "pub_date": stale},
            ]

        articles, error, newest = fetch_rss_feed(
            "Feed",
            "https://x/feed.xml",
            hours=24,
            fetch_url_fn=lambda url, **_: (b"<rss/>", "utf-8"),
            decode_content_fn=lambda raw, charset: "<rss/>",
            parse_feed_fn=fake_parse,
        )

        self.assertIsNone(error)
        self.assertEqual([a["title"] for a in articles], ["fresh"])
        # The source name is stamped onto every surviving article.
        self.assertEqual(articles[0]["source"], "Feed")
        # newest_item_date reflects the whole feed, pre-window-filter.
        self.assertEqual(newest, recent.isoformat())

    def test_newest_item_date_survives_window_filter(self):
        """A dormant feed yields zero in-window articles but still reports its
        newest item — the signal the stale-feed warning depends on."""
        now = datetime.now(timezone.utc)
        old = now - timedelta(days=45)
        older = now - timedelta(days=60)

        def fake_parse(content, max_summary=0):
            return [
                {"title": "a", "link": "https://x/1", "pub_date": older},
                {"title": "b", "link": "https://x/2", "pub_date": old},
            ]

        articles, error, newest = fetch_rss_feed(
            "Dormant",
            "https://x/feed.xml",
            hours=24,
            fetch_url_fn=lambda url, **_: (b"<rss/>", "utf-8"),
            decode_content_fn=lambda raw, charset: "<rss/>",
            parse_feed_fn=fake_parse,
        )
        self.assertIsNone(error)
        self.assertEqual(articles, [])
        self.assertEqual(newest, old.isoformat())

    def test_http_error_reports_http_code(self):
        def boom(url, **_):
            raise _http_error(url, 404, "Not Found")

        articles, error, newest = fetch_rss_feed(
            "Feed", "https://x/feed.xml", fetch_url_fn=boom,
        )
        self.assertEqual(articles, [])
        self.assertEqual(error, "HTTP 404")
        self.assertIsNone(newest)

    def test_url_error_reports_connection_failure(self):
        def boom(url, **_):
            raise URLError("name resolution failed")

        articles, error, newest = fetch_rss_feed(
            "Feed", "https://x/feed.xml", fetch_url_fn=boom,
        )
        self.assertEqual(articles, [])
        self.assertIn("Connection failed", error)
        self.assertIn("name resolution failed", error)
        self.assertIsNone(newest)


    def test_user_agent_is_forwarded_to_the_fetcher(self):
        seen = {}

        def capture(url, **kwargs):
            seen.update(kwargs)
            return b"<rss/>", "utf-8"

        fetch_rss_feed(
            "Feed", "https://x/feed.xml", user_agent="CustomReader/1.0",
            fetch_url_fn=capture,
            decode_content_fn=lambda raw, charset: "",
            parse_feed_fn=lambda content, max_summary=0: [],
        )
        self.assertEqual(seen.get("user_agent"), "CustomReader/1.0")


class FetchAllFeedsTests(unittest.TestCase):
    def test_user_agent_override_is_passed_only_when_the_feed_declares_one(self):
        # The two tests below this one call fetch_feed_fn stubs that take exactly
        # four positional args and no **kwargs. That is the compatibility this
        # conditional protects: feeds without an override must keep the old call
        # shape, or every injected stub in the suite breaks at once.
        calls: list = []

        def fake_fetch(name, url, hours, max_summary, **kwargs):
            calls.append((name, kwargs))
            return [], None, None

        fetch_all_feeds(
            [
                {"name": "Plain", "url": "https://x/a"},
                {"name": "Override", "url": "https://x/b", "user_agent": "CustomReader/1.0"},
                {"name": "Blank", "url": "https://x/c", "user_agent": "   "},
            ],
            max_workers=1,
            fetch_feed_fn=fake_fetch,
        )

        by_name = dict(calls)
        self.assertEqual(by_name["Plain"], {})
        self.assertEqual(by_name["Override"], {"user_agent": "CustomReader/1.0"})
        self.assertEqual(by_name["Blank"], {}, "whitespace is not an override")

    def test_aggregates_articles_and_status_across_feeds(self):
        feeds = [
            {"name": "Good", "url": "https://x/a"},
            {"name": "Empty", "url": "https://x/b"},
            {"name": "Broken", "url": "https://x/c"},
        ]

        def fake_fetch(name, url, hours, max_summary):
            if name == "Good":
                return ([{"title": "t", "link": "https://x/a/1", "source": name}], None, "2026-07-03T10:00:00+00:00")
            if name == "Empty":
                # Legacy 2-tuple stubs stay accepted; newest defaults to None.
                return ([], None)
            return ([], "HTTP 500", None)

        all_articles, feed_status, feed_newest = fetch_all_feeds(
            feeds, hours=24, max_workers=3, fetch_feed_fn=fake_fetch,
        )

        self.assertEqual(len(all_articles), 1)
        self.assertEqual(feed_status, {"Good": None, "Empty": None, "Broken": "HTTP 500"})
        self.assertEqual(feed_newest["Good"], "2026-07-03T10:00:00+00:00")
        self.assertIsNone(feed_newest["Empty"])
        self.assertIsNone(feed_newest["Broken"])

    def test_worker_exception_is_captured_as_feed_status(self):
        feeds = [{"name": "Explodes", "url": "https://x/a"}]

        def fake_fetch(name, url, hours, max_summary):
            raise RuntimeError("unexpected")

        all_articles, feed_status, feed_newest = fetch_all_feeds(
            feeds, fetch_feed_fn=fake_fetch,
        )
        self.assertEqual(all_articles, [])
        self.assertIn("unexpected", feed_status["Explodes"])
        self.assertIsNone(feed_newest["Explodes"])


class EnrichArticleTextTests(unittest.TestCase):
    def test_backfills_only_missing_article_text(self):
        calls = []

        def fake_fetch(link, max_words):
            calls.append((link, max_words))
            return f"body for {link}"

        articles = [
            {"link": "https://x/1", "article_text": ""},
            {"link": "https://x/2", "article_text": "already extracted"},
        ]
        enrich_article_text(articles, fetch_article_text_fn=fake_fetch)

        self.assertEqual(articles[0]["article_text"], "body for https://x/1")
        self.assertEqual(articles[1]["article_text"], "already extracted")
        self.assertEqual(calls, [("https://x/1", 150)])

    def test_disabled_config_sets_empty_string_without_fetching(self):
        calls = []

        def fake_fetch(link, max_words):
            calls.append(link)
            return "should not run"

        articles = [{"link": "https://x/1"}]
        enrich_article_text(
            articles,
            pipeline_config={"article_text": {"enabled": False}},
            fetch_article_text_fn=fake_fetch,
        )
        self.assertEqual(articles[0]["article_text"], "")
        self.assertEqual(calls, [])

    def test_fetch_error_yields_empty_string_not_none(self):
        def boom(link, max_words):
            raise URLError("down")

        articles = [{"link": "https://x/1", "article_text": ""}]
        enrich_article_text(articles, fetch_article_text_fn=boom)
        self.assertEqual(articles[0]["article_text"], "")

    def test_worker_count_one_and_four_produce_identical_output(self):
        def fake_fetch(link, max_words):
            return f"body {link}"

        base = [{"link": f"https://x/{i}", "article_text": ""} for i in range(6)]

        single = [dict(a) for a in base]
        enrich_article_text(
            single,
            pipeline_config={"article_text": {"max_workers": 1}},
            fetch_article_text_fn=fake_fetch,
        )
        many = [dict(a) for a in base]
        enrich_article_text(
            many,
            pipeline_config={"article_text": {"max_workers": 4}},
            fetch_article_text_fn=fake_fetch,
        )
        self.assertEqual(
            [a["article_text"] for a in single],
            [a["article_text"] for a in many],
        )


class EnrichArticlePagesTests(unittest.TestCase):
    """The merged single-fetch enrichment used on the JSON (production) path."""

    def test_single_fetch_populates_both_summary_and_body(self):
        fetched = []

        def fake_fetch_url(url, **_):
            fetched.append(url)
            return b"<html/>", "utf-8"

        articles = [
            {"link": "https://x/1", "summary_en": "tiny", "article_text": ""},
        ]
        enrich_article_pages(
            articles,
            max_summary=300,
            fetch_url_fn=fake_fetch_url,
            decode_content_fn=lambda raw, charset: "<html/>",
            extract_summary_fn=lambda content, max_chars=0: "A much longer meta summary "
            "pulled straight from the article page that clears the short threshold.",
            extract_main_text_fn=lambda content: "Extracted article body text.",
        )

        # The page was fetched exactly once even though both fields needed work.
        self.assertEqual(fetched, ["https://x/1"])
        self.assertTrue(articles[0]["summary_en"].startswith("A much longer meta summary"))
        self.assertEqual(articles[0]["article_text"], "Extracted article body text.")

    def test_long_summary_is_not_overwritten_but_body_still_filled(self):
        long_summary = (
            "This feed summary is already detailed enough to clear the short-summary "
            "threshold and therefore must be preserved verbatim by the enrichment pass."
        )
        articles = [{"link": "https://x/1", "summary_en": long_summary, "article_text": ""}]
        enrich_article_pages(
            articles,
            fetch_url_fn=lambda url, **_: (b"", "utf-8"),
            decode_content_fn=lambda raw, charset: "",
            extract_summary_fn=lambda content, max_chars=0: "short replacement",
            extract_main_text_fn=lambda content: "Body text from page.",
        )
        self.assertEqual(articles[0]["summary_en"], long_summary)
        self.assertEqual(articles[0]["article_text"], "Body text from page.")

    def test_article_with_both_fields_satisfied_is_not_fetched(self):
        fetched = []
        long_summary = "x" * 200
        articles = [{"link": "https://x/1", "summary_en": long_summary,
                     "article_text": "already have body"}]
        enrich_article_pages(
            articles,
            fetch_url_fn=lambda url, **_: fetched.append(url) or (b"", "utf-8"),
            decode_content_fn=lambda raw, charset: "",
            extract_summary_fn=lambda content, max_chars=0: "ignored",
            extract_main_text_fn=lambda content: "ignored",
        )
        self.assertEqual(fetched, [])
        self.assertEqual(articles[0]["summary_en"], long_summary)
        self.assertEqual(articles[0]["article_text"], "already have body")

    def test_body_disabled_still_runs_summary_fallback(self):
        articles = [{"link": "https://x/1", "summary_en": "tiny"}]
        enrich_article_pages(
            articles,
            pipeline_config={"article_text": {"enabled": False}},
            fetch_url_fn=lambda url, **_: (b"", "utf-8"),
            decode_content_fn=lambda raw, charset: "",
            extract_summary_fn=lambda content, max_chars=0: "A sufficiently long fallback "
            "summary that comfortably clears the configured short-summary threshold value.",
            extract_main_text_fn=lambda content: "should not be stored",
        )
        self.assertTrue(articles[0]["summary_en"].startswith("A sufficiently long fallback"))
        # enabled=false keeps article_text empty (parity with enrich_article_text).
        self.assertEqual(articles[0]["article_text"], "")

    def test_fetch_failure_leaves_fields_unchanged(self):
        def boom(url, **_):
            raise _http_error(url, 503, "Service Unavailable")

        articles = [{"link": "https://x/1", "summary_en": "tiny", "article_text": ""}]
        enrich_article_pages(
            articles,
            fetch_url_fn=boom,
            decode_content_fn=lambda raw, charset: "",
            extract_summary_fn=lambda content, max_chars=0: "unused",
            extract_main_text_fn=lambda content: "unused",
        )
        self.assertEqual(articles[0]["summary_en"], "tiny")
        self.assertEqual(articles[0]["article_text"], "")

    def test_duplicate_links_fetched_once(self):
        fetched = []
        articles = [
            {"link": "https://x/dup", "summary_en": "tiny", "article_text": ""},
            {"link": "https://x/dup", "summary_en": "tiny", "article_text": ""},
        ]
        enrich_article_pages(
            articles,
            fetch_url_fn=lambda url, **_: fetched.append(url) or (b"", "utf-8"),
            decode_content_fn=lambda raw, charset: "",
            extract_summary_fn=lambda content, max_chars=0: "",
            extract_main_text_fn=lambda content: "Shared body.",
        )
        self.assertEqual(fetched, ["https://x/dup"])
        self.assertEqual(articles[0]["article_text"], "Shared body.")
        self.assertEqual(articles[1]["article_text"], "Shared body.")

    def test_equivalent_to_legacy_two_pass_output(self):
        """The merged pass must produce the same summary_en / article_text as
        running the old enrich_missing_summaries + enrich_article_text passes."""
        def make_articles():
            return [
                {"link": "https://x/1", "summary_en": "tiny", "article_text": ""},
                {"link": "https://x/2", "summary_en": "y" * 200, "article_text": ""},
                {"link": "https://x/3", "summary_en": "", "article_text": ""},
            ]

        page_summary = {
            "https://x/1": "A long replacement meta summary that clears the threshold easily.",
            "https://x/2": "Should be ignored because the feed summary is already long.",
            "https://x/3": "Backfilled summary for an article that had none at all here.",
        }
        page_body = {
            "https://x/1": "Body one.",
            "https://x/2": "Body two.",
            "https://x/3": "Body three.",
        }

        # Legacy two-pass path.
        legacy = make_articles()
        enrich_missing_summaries(
            legacy,
            max_summary=300,
            fetch_summary_fn=lambda link, max_summary=0: page_summary[link],
        )
        enrich_article_text(
            legacy,
            fetch_article_text_fn=lambda link, max_words: page_body[link],
        )

        # Merged single-fetch path. The stub encodes the link into the fetched
        # bytes so the stubbed extractors can key off it like a real page.
        merged = make_articles()
        enrich_article_pages(
            merged,
            max_summary=300,
            fetch_url_fn=lambda url, **_: (url.encode("utf-8"), "utf-8"),
            decode_content_fn=lambda raw, charset: raw.decode("utf-8"),
            extract_summary_fn=lambda content, max_chars=0: page_summary[content],
            extract_main_text_fn=lambda content: page_body[content],
        )

        self.assertEqual(
            [(a["summary_en"], a["article_text"]) for a in legacy],
            [(a["summary_en"], a["article_text"]) for a in merged],
        )


if __name__ == "__main__":
    unittest.main()
