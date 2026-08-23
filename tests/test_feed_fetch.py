"""Unit tests for scripts/_common/feed_fetch.py.

These pin the behaviour of the network fetch path (feed fetch, feed-status
aggregation, summary fallback, article-body extraction, the merged
single-fetch enrichment, and the untrusted-URL guard). The fetch path is
otherwise only exercised by the real-network end-to-end smoke, so these tests
are the safety net that makes fetch-path refactors reviewable offline.

All network access is stubbed via injected ``*_fn`` callables, and DNS is an
explicit injectable boundary (``resolve_fn``): the guard tests supply
deterministic public / private / failure / multi-address answers instead of
touching the host resolver, so the suite behaves identically with or without
DNS access.
"""

from __future__ import annotations

import io
import socket
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
        self._pos = 0
        self.headers = _Headers(charset)

    def read(self, size: int = -1) -> bytes:
        # Stateful so chunked reads (the capped fetch path) terminate: the
        # first chunked read yields up to ``size`` bytes, later ones the rest,
        # and the final one the empty bytes that end the loop.
        if size is None or size < 0:
            data = self._body[self._pos:]
            self._pos = len(self._body)
            return data
        data = self._body[self._pos:self._pos + size]
        self._pos += len(data)
        return data

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


class _NoHostDnsMixin:
    """Fail the test if the host resolver is consulted.

    The focused fetch suite must be hermetic: every hostname answer comes from
    an injected ``resolve_fn``, never from ``socket.getaddrinfo``.
    """

    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(
            feed_fetch.socket, "getaddrinfo",
            side_effect=AssertionError(
                "focused fetch tests must not touch the host resolver"
            ),
        )
        patcher.start()
        self.addCleanup(patcher.stop)


class FetchUrlRetryTests(unittest.TestCase):
    """The retry predicate: what counts as transient and what does not.

    ``fetch_url`` is the one place in the pipeline that decides whether a failed
    request is worth repeating. Both directions of that decision are load-bearing
    and neither shows up in the end-to-end smoke, so they are pinned here.
    """

    def _run(self, urlopen_fn, **kwargs):
        attempts: list = []

        def counting(req, timeout=None, resolve_fn=None):
            attempts.append(req)
            return urlopen_fn(len(attempts), req)

        # The retry tests target the retry predicate with the synthetic host
        # "x". The URL-policy pre-check is stubbed out (it is pinned by its own
        # dedicated tests below); the network seam is ``guarded_urlopen``, the
        # guarded-opener entry point ``fetch_url`` uses instead of ``urlopen``.
        with mock.patch.object(feed_fetch, "guarded_urlopen", counting), \
                mock.patch.object(feed_fetch, "validate_fetch_url"), \
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


class ValidateFetchUrlTests(_NoHostDnsMixin, unittest.TestCase):
    """The URL policy guard: what fetch_url is allowed to touch.

    Article links come from feed content — untrusted input — so the guard is
    what keeps a poisoned feed from reading file:// URLs or probing internal
    hosts. DNS is the injectable ``resolve_fn`` boundary: every hostname case
    below supplies a deterministic answer, so the suite never touches the host
    resolver.
    """

    def _resolver(self, addresses):
        def fake(host):
            if isinstance(addresses, Exception):
                raise addresses
            return list(addresses)
        return fake

    def assert_blocked(self, url, resolve_fn=None):
        with self.assertRaises(feed_fetch.BlockedUrlError):
            feed_fetch.validate_fetch_url(url, resolver=resolve_fn)

    def test_non_http_schemes_are_blocked(self):
        self.assert_blocked("file:///etc/passwd")
        self.assert_blocked("ftp://example.com/feed.xml")
        self.assert_blocked("gopher://example.com/")
        self.assert_blocked("javascript:alert(1)")
        self.assert_blocked("")

    def test_urls_without_host_are_blocked(self):
        self.assert_blocked("http:///etc/passwd")
        self.assert_blocked("https://")

    def test_literal_internal_ipv4_hosts_are_blocked(self):
        self.assert_blocked("http://127.0.0.1/feed.xml")
        self.assert_blocked("http://10.1.2.3/feed.xml")
        self.assert_blocked("http://192.168.0.10/feed.xml")
        self.assert_blocked("http://172.16.5.5/feed.xml")
        self.assert_blocked("http://169.254.169.254/latest/meta-data/")
        self.assert_blocked("http://0.0.0.0/feed.xml")

    def test_literal_internal_ipv6_hosts_are_blocked(self):
        self.assert_blocked("http://[::1]/feed.xml")
        self.assert_blocked("http://[fc00::1]/feed.xml")
        self.assert_blocked("http://[fe80::1]/feed.xml")

    def test_literal_public_hosts_are_allowed(self):
        # Literal IPs need no resolution, so these stay valid offline.
        feed_fetch.validate_fetch_url("https://93.184.216.34/feed.xml")
        feed_fetch.validate_fetch_url("https://8.8.8.8/feed.xml")
        feed_fetch.validate_fetch_url("http://[2606:2800:220:1:248:1893:25c8:1946]/x")

    def test_fake_ip_proxy_ranges_are_allowed(self):
        # Local fake-IP proxies (Clash, Surge) answer every DNS lookup from the
        # benchmarking range, and some carriers use CGNAT. is_private covers
        # both, but the guard must not — otherwise no feed fetches at all on
        # machines behind such a proxy.
        feed_fetch.validate_fetch_url("https://198.18.4.200/feed.xml")
        feed_fetch.validate_fetch_url("https://100.64.0.1/feed.xml")

    def test_ipv4_mapped_ipv6_is_checked_as_ipv4(self):
        self.assert_blocked("http://[::ffff:127.0.0.1]/feed.xml")
        self.assert_blocked("http://[::ffff:10.0.0.1]/feed.xml")
        feed_fetch.validate_fetch_url("http://[::ffff:93.184.216.34]/feed.xml")

    def test_hostname_resolving_to_internal_address_is_blocked(self):
        self.assert_blocked(
            "https://internal.example/feed.xml",
            resolve_fn=self._resolver(["127.0.0.1"]),
        )

    def test_mixed_resolution_with_any_internal_address_is_blocked(self):
        self.assert_blocked(
            "https://dual.example/feed.xml",
            resolve_fn=self._resolver(["93.184.216.34", "10.0.0.1"]),
        )

    def test_hostname_resolving_to_public_address_is_allowed(self):
        feed_fetch.validate_fetch_url(
            "https://feed.example/feed.xml",
            resolver=self._resolver(["93.184.216.34"]),
        )

    def test_dns_failure_is_left_to_the_fetch_itself(self):
        feed_fetch.validate_fetch_url(
            "https://unresolvable.example/feed.xml",
            resolver=self._resolver(OSError("name resolution failed")),
        )

    def test_scoped_ipv6_resolution_is_still_checked(self):
        self.assert_blocked(
            "https://linklocal.example/feed.xml",
            resolve_fn=self._resolver(["fe80::1%en0"]),
        )


class FetchUrlSecurityIntegrationTests(unittest.TestCase):
    """The guard and the read cap exercised through the real fetch_url."""

    def test_blocked_url_never_reaches_the_network_seam(self):
        with mock.patch.object(feed_fetch, "guarded_urlopen") as open_mock:
            with self.assertRaises(feed_fetch.BlockedUrlError):
                feed_fetch.fetch_url("file:///etc/passwd")
        open_mock.assert_not_called()

    def test_blocked_url_is_not_retried(self):
        attempts = []

        def counting(req, timeout=None, resolve_fn=None):
            attempts.append(req)
            return _FakeResponse()

        with mock.patch.object(feed_fetch, "guarded_urlopen", counting), \
                mock.patch.object(feed_fetch.time, "sleep"):
            with self.assertRaises(feed_fetch.BlockedUrlError):
                feed_fetch.fetch_url("http://169.254.169.254/latest/meta-data/")
        self.assertEqual(attempts, [])

    def test_oversized_response_is_rejected_without_retry(self):
        attempts = []

        def counting(req, timeout=None, resolve_fn=None):
            attempts.append(req)
            return _FakeResponse(b"x" * 17)

        # Literal public IP passes the URL guard without any DNS.
        with mock.patch.object(feed_fetch, "guarded_urlopen", counting), \
                mock.patch.object(feed_fetch, "MAX_RESPONSE_BYTES", 16), \
                mock.patch.object(feed_fetch.time, "sleep"):
            with self.assertRaises(feed_fetch.ResponseTooLargeError):
                feed_fetch.fetch_url("https://93.184.216.34/feed.xml")
        self.assertEqual(len(attempts), 1, "a size violation cannot shrink on retry")

    def test_response_at_the_cap_is_accepted(self):
        def fake_open(req, timeout=None, resolve_fn=None):
            return _FakeResponse(b"x" * 16)

        with mock.patch.object(feed_fetch, "guarded_urlopen", fake_open), \
                mock.patch.object(feed_fetch, "MAX_RESPONSE_BYTES", 16):
            raw, charset = feed_fetch.fetch_url("https://93.184.216.34/feed.xml")
        self.assertEqual(raw, b"x" * 16)
        self.assertEqual(charset, "utf-8")


class RedirectGuardTests(_NoHostDnsMixin, unittest.TestCase):
    """Every redirect destination passes through the same URL policy.

    A public feed URL that 302s to loopback / private / link-local / metadata
    hosts must be refused before the follow-up request is even built, so the
    forbidden destination is never connected to and its body is never read.
    """

    def _handler(self, resolve_fn=None):
        return feed_fetch._GuardedRedirectHandler(resolve_fn)

    def _req(self, url="http://feed.example/feed.xml"):
        return feed_fetch.Request(url)

    def test_redirect_to_literal_internal_host_is_blocked(self):
        handler = self._handler()
        with self.assertRaises(feed_fetch.BlockedUrlError):
            handler.redirect_request(
                self._req(), None, 302, "Found", {},
                "http://169.254.169.254/latest/meta-data/",
            )

    def test_redirect_to_internal_hostname_is_blocked_via_resolver(self):
        handler = self._handler(resolve_fn=lambda host: ["10.0.0.1"])
        with self.assertRaises(feed_fetch.BlockedUrlError):
            handler.redirect_request(
                self._req(), None, 302, "Found", {},
                "http://internal.example/secret",
            )

    def test_redirect_to_file_scheme_is_blocked(self):
        handler = self._handler()
        with self.assertRaises(feed_fetch.BlockedUrlError):
            handler.redirect_request(
                self._req(), None, 302, "Found", {},
                "file:///etc/passwd",
            )

    def test_redirect_to_public_host_is_followed(self):
        handler = self._handler(resolve_fn=lambda host: ["93.184.216.34"])
        new_req = handler.redirect_request(
            self._req(), None, 302, "Found", {},
            "http://other.example/feed.xml",
        )
        self.assertEqual(new_req.full_url, "http://other.example/feed.xml")

    def test_redirect_to_unresolvable_host_is_left_to_the_fetch(self):
        def failing(host):
            raise OSError("name resolution failed")

        handler = self._handler(resolve_fn=failing)
        new_req = handler.redirect_request(
            self._req(), None, 302, "Found", {},
            "http://unresolvable.example/feed.xml",
        )
        self.assertEqual(new_req.full_url, "http://unresolvable.example/feed.xml")


class ConnectTimeGuardTests(_NoHostDnsMixin, unittest.TestCase):
    """The address actually dialed is validated at connect time.

    ``_resolve_and_pin`` is the gate the connection classes go through; it
    re-resolves, applies the denylist to the real answer, and returns the
    exact address the socket dials — so a DNS answer that changes between the
    pre-flight check and the connection cannot reach a forbidden network.
    """

    def test_public_resolution_returns_the_dialed_address(self):
        target = feed_fetch._resolve_and_pin(
            "feed.example", 80, lambda host: ["93.184.216.34"],
        )
        self.assertEqual(target, "93.184.216.34")

    def test_internal_resolution_is_refused_before_any_dial(self):
        with self.assertRaises(feed_fetch.BlockedUrlError):
            feed_fetch._resolve_and_pin(
                "internal.example", 80, lambda host: ["127.0.0.1"],
            )

    def test_mixed_resolution_is_refused(self):
        with self.assertRaises(feed_fetch.BlockedUrlError):
            feed_fetch._resolve_and_pin(
                "dual.example", 80, lambda host: ["93.184.216.34", "10.0.0.1"],
            )

    def test_empty_resolution_surfaces_as_connection_error(self):
        with self.assertRaises(URLError):
            feed_fetch._resolve_and_pin("empty.example", 80, lambda host: [])

    def test_resolution_failure_propagates_for_retry_handling(self):
        def failing(host):
            raise socket.gaierror("name resolution failed")

        with self.assertRaises(socket.gaierror):
            feed_fetch._resolve_and_pin("nx.example", 80, failing)

    def test_http_connection_dials_only_the_validated_address(self):
        dialed = []

        def fake_dial(address, timeout, source_address):
            dialed.append(address)
            return _FakeSocket(b"")

        conn = feed_fetch._GuardedHTTPConnection(
            "feed.example", resolve_fn=lambda host: ["93.184.216.34"],
        )
        with mock.patch.object(feed_fetch, "_dial", fake_dial):
            conn.connect()
        self.assertEqual(dialed, [("93.184.216.34", 80)])

    def test_http_connection_refuses_internal_resolution_before_dialing(self):
        dialed = []

        def fake_dial(address, timeout, source_address):
            dialed.append(address)
            return _FakeSocket(b"")

        conn = feed_fetch._GuardedHTTPConnection(
            "internal.example", resolve_fn=lambda host: ["10.0.0.1"],
        )
        with mock.patch.object(feed_fetch, "_dial", fake_dial):
            with self.assertRaises(feed_fetch.BlockedUrlError):
                conn.connect()
        self.assertEqual(dialed, [])

    def test_https_connection_uses_the_same_gate(self):
        conn = feed_fetch._GuardedHTTPSConnection(
            "internal.example", resolve_fn=lambda host: ["127.0.0.1"],
        )
        with mock.patch.object(feed_fetch, "_dial") as dial_mock:
            with self.assertRaises(feed_fetch.BlockedUrlError):
                conn.connect()
        dial_mock.assert_not_called()


class RebindingGuardTests(_NoHostDnsMixin, unittest.TestCase):
    """Resolution changes between policy check and connection are caught."""

    def test_answer_that_turns_internal_at_connect_time_is_blocked(self):
        answers = [["93.184.216.34"], ["127.0.0.1"]]

        def rebinding(host):
            return answers.pop(0) if answers else ["127.0.0.1"]

        dialed = []

        def fake_dial(address, timeout, source_address):
            dialed.append(address)
            return _FakeSocket(b"")

        conn = feed_fetch._GuardedHTTPConnection("feed.example", resolve_fn=rebinding)
        # The pre-flight check sees the public answer...
        feed_fetch.validate_fetch_url("http://feed.example/feed.xml", resolver=rebinding)
        # ...but the connect-time gate sees the rebound internal one and blocks.
        with mock.patch.object(feed_fetch, "_dial", fake_dial):
            with self.assertRaises(feed_fetch.BlockedUrlError):
                conn.connect()
        self.assertEqual(dialed, [])


class _FakeSocket:
    """Just enough of a socket for http.client to speak one request/response."""

    def __init__(self, payload: bytes) -> None:
        self.payload = payload
        self.sent = b""
        self.closed = False

    def makefile(self, mode, *args, **kwargs):
        return io.BytesIO(self.payload)

    def sendall(self, data):
        self.sent += data

    def setsockopt(self, *args, **kwargs):
        pass

    def settimeout(self, value):
        pass

    def close(self):
        self.closed = True


def _http_response(body: bytes, status: int = 200, reason: str = "OK",
                   extra_headers: str = "") -> bytes:
    return (
        f"HTTP/1.1 {status} {reason}\r\n"
        "Content-Type: text/xml; charset=utf-8\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"{extra_headers}"
        "Connection: close\r\n"
        "\r\n"
    ).encode("ascii") + body


class GuardedOpenerEndToEndTests(_NoHostDnsMixin, unittest.TestCase):
    """The real opener machinery (handlers, redirects, pinned connections)
    exercised through a fake socket layer — no host resolver, no real socket.

    ``socket.create_connection`` is replaced by a dispatcher keyed on the
    dialed address, and DNS comes from an injected resolver, so every
    destination the fetch reaches is observable and controllable.
    """

    def _run(self, url, dns, payloads, resolve_state=None):
        dialed = []

        def fake_create_connection(address, timeout=None, source_address=None):
            dialed.append(address)
            host = address[0]
            if host not in payloads:
                raise OSError(f"unexpected connection to {host}")
            return _FakeSocket(payloads[host])

        def resolver(host):
            if resolve_state is not None:
                return resolve_state(host)
            if host not in dns:
                raise socket.gaierror(f"name resolution failed: {host}")
            return list(dns[host])

        with mock.patch.object(feed_fetch.socket, "create_connection",
                               fake_create_connection):
            try:
                result = feed_fetch.fetch_url(url, resolve_fn=resolver, retries=0)
                return result, None, dialed
            except Exception as exc:  # noqa: BLE001 - error is the assertion target
                return None, exc, dialed

    def test_plain_fetch_dials_the_validated_address_and_reads_body(self):
        result, error, dialed = self._run(
            "http://feed.example/feed.xml",
            dns={"feed.example": ["93.184.216.34"]},
            payloads={"93.184.216.34": _http_response(b"<rss/>")},
        )
        self.assertIsNone(error)
        self.assertEqual(result[0], b"<rss/>")
        self.assertEqual(dialed, [("93.184.216.34", 80)])

    def test_redirect_to_loopback_is_rejected_before_its_body_is_read(self):
        internal_payload = _http_response(b"SECRET")
        result, error, dialed = self._run(
            "http://feed.example/feed.xml",
            dns={"feed.example": ["93.184.216.34"]},
            payloads={
                "93.184.216.34": _http_response(
                    b"", status=302, reason="Found",
                    extra_headers="Location: http://127.0.0.1/secret\r\n",
                ),
                "127.0.0.1": internal_payload,
            },
        )
        self.assertIsInstance(error, feed_fetch.BlockedUrlError)
        # Only the public first hop was dialed; the internal target never was.
        self.assertEqual(dialed, [("93.184.216.34", 80)])

    def test_redirect_to_internal_hostname_is_rejected(self):
        result, error, dialed = self._run(
            "http://feed.example/feed.xml",
            dns={"feed.example": ["93.184.216.34"],
                 "internal.example": ["10.0.0.1"]},
            payloads={
                "93.184.216.34": _http_response(
                    b"", status=302, reason="Found",
                    extra_headers="Location: http://internal.example/secret\r\n",
                ),
                "10.0.0.1": _http_response(b"SECRET"),
            },
        )
        self.assertIsInstance(error, feed_fetch.BlockedUrlError)
        self.assertEqual(dialed, [("93.184.216.34", 80)])

    def test_redirect_to_public_host_is_followed_to_completion(self):
        result, error, dialed = self._run(
            "http://feed.example/feed.xml",
            dns={"feed.example": ["93.184.216.34"],
                 "other.example": ["93.184.215.1"]},
            payloads={
                "93.184.216.34": _http_response(
                    b"", status=302, reason="Found",
                    extra_headers="Location: http://other.example/feed.xml\r\n",
                ),
                "93.184.215.1": _http_response(b"<rss>final</rss>"),
            },
        )
        self.assertIsNone(error)
        self.assertEqual(result[0], b"<rss>final</rss>")
        self.assertEqual(dialed, [("93.184.216.34", 80), ("93.184.215.1", 80)])

    def test_rebinding_between_redirect_check_and_connect_is_rejected(self):
        # The redirect-target check sees a public answer; the connection for
        # that same host then resolves to an internal address. The dial must
        # still be refused.
        calls = {"other.example": 0}

        def rebinding(host):
            if host == "feed.example":
                return ["93.184.216.34"]
            calls[host] += 1
            return ["93.184.215.1"] if calls[host] == 1 else ["127.0.0.1"]

        result, error, dialed = self._run(
            "http://feed.example/feed.xml",
            dns={},
            resolve_state=rebinding,
            payloads={
                "93.184.216.34": _http_response(
                    b"", status=302, reason="Found",
                    extra_headers="Location: http://other.example/feed.xml\r\n",
                ),
            },
        )
        self.assertIsInstance(error, feed_fetch.BlockedUrlError)
        self.assertEqual(dialed, [("93.184.216.34", 80)])

    def test_dns_failure_surfaces_as_a_fetch_error_not_a_policy_block(self):
        result, error, dialed = self._run(
            "http://nx.example/feed.xml",
            dns={},
            payloads={},
        )
        self.assertIsInstance(error, URLError)
        self.assertNotIsInstance(error, feed_fetch.BlockedUrlError)
        self.assertEqual(dialed, [])


if __name__ == "__main__":
    unittest.main()
