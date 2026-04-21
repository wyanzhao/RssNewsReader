"""Offline tests for scripts/network_debug.py."""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import network_debug  # noqa: E402


class LoadFeedsTests(unittest.TestCase):
    def test_missing_feeds_file_returns_empty_list(self):
        missing = Path("/tmp/does-not-exist-feeds.json")
        with mock.patch.object(network_debug, "FEEDS_FILE", missing):
            self.assertEqual(network_debug.load_feeds(), [])

    def test_load_feeds_reads_named_entries(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            feeds_path = Path(tmpdir) / "feeds.json"
            feeds_path.write_text(
                json.dumps({
                    "feeds": [
                        {"name": "Feed A", "url": "https://example.com/a.xml"},
                        {"name": "Feed B", "url": "https://example.com/b.xml"},
                    ],
                }),
                encoding="utf-8",
            )
            with mock.patch.object(network_debug, "FEEDS_FILE", feeds_path):
                self.assertEqual(
                    network_debug.load_feeds(),
                    [
                        {"name": "Feed A", "url": "https://example.com/a.xml"},
                        {"name": "Feed B", "url": "https://example.com/b.xml"},
                    ],
                )


class FeedChecksTests(unittest.TestCase):
    def test_feed_checks_assigns_status_from_dns_and_http_results(self):
        feeds = [
            {"name": "Healthy", "url": "https://ok.example/feed.xml"},
            {"name": "DNS Down", "url": "https://dns.example/feed.xml"},
            {"name": "HTTP Down", "url": "https://http.example/feed.xml"},
        ]
        dns_results = {
            "ok.example": {"hostname": "ok.example", "ok": True, "addresses": ["203.0.113.10"]},
            "dns.example": {"hostname": "dns.example", "ok": False, "error": "dns failed"},
            "http.example": {"hostname": "http.example", "ok": True, "addresses": ["203.0.113.11"]},
        }
        http_results = {
            "https://ok.example/feed.xml": {"url": "https://ok.example/feed.xml", "ok": True, "status": 200},
            "https://dns.example/feed.xml": {"url": "https://dns.example/feed.xml", "ok": True, "status": 200},
            "https://http.example/feed.xml": {"url": "https://http.example/feed.xml", "ok": False, "error": "HTTP 503", "status": 503},
        }

        def fake_resolve(hostname: str):
            return dns_results[hostname]

        def fake_http(url: str, timeout: float):
            self.assertEqual(timeout, 4.0)
            return http_results[url]

        with mock.patch.object(network_debug, "resolve_host", side_effect=fake_resolve), mock.patch.object(
            network_debug, "http_check", side_effect=fake_http
        ):
            results = network_debug.feed_checks(feeds, timeout=4.0)

        self.assertEqual([item["status"] for item in results], ["ok", "dns_error", "http_error"])
        self.assertEqual(results[0]["hostname"], "ok.example")
        self.assertEqual(results[1]["dns"]["error"], "dns failed")
        self.assertEqual(results[2]["http"]["status"], 503)


class BuildReportTests(unittest.TestCase):
    def test_build_report_respects_limit_and_uses_expected_keys(self):
        feeds = [
            {"name": "Feed A", "url": "https://a.example/feed.xml"},
            {"name": "Feed B", "url": "https://b.example/feed.xml"},
        ]

        def fake_resolve(hostname: str):
            return {"hostname": hostname, "ok": True, "addresses": [f"{hostname}-ip"]}

        def fake_tcp(host: str, port: int, timeout: float):
            self.assertEqual(port, 443)
            self.assertEqual(timeout, 2.5)
            return {"target": f"{host}:{port}", "ok": True}

        def fake_http(url: str, timeout: float):
            self.assertEqual(timeout, 2.5)
            return {"url": url, "ok": True, "status": 200, "content_type": "text/xml"}

        with mock.patch.object(network_debug, "load_feeds", return_value=feeds), \
             mock.patch.object(network_debug, "iso_now", return_value="2026-04-21T00:00:00+00:00"), \
             mock.patch.object(network_debug, "resolve_host", side_effect=fake_resolve), \
             mock.patch.object(network_debug, "tcp_check", side_effect=fake_tcp), \
             mock.patch.object(network_debug, "http_check", side_effect=fake_http), \
             mock.patch.object(network_debug.os, "getcwd", return_value="/repo"), \
             mock.patch.dict(network_debug.os.environ, {"CODEX_THREAD_ID": "thread-123", "IGNORED": "x"}, clear=True):
            report = network_debug.build_report(limit=1, timeout=2.5)

        self.assertEqual(
            set(report.keys()),
            {
                "timestamp",
                "cwd",
                "python",
                "platform",
                "env",
                "dns_tests",
                "tcp_tests",
                "http_tests",
                "feeds_checked",
                "feed_checks",
            },
        )
        self.assertEqual(report["timestamp"], "2026-04-21T00:00:00+00:00")
        self.assertEqual(report["cwd"], "/repo")
        self.assertEqual(report["env"], {"CODEX_THREAD_ID": "thread-123"})
        self.assertEqual(report["feeds_checked"], 1)
        self.assertEqual(len(report["feed_checks"]), 1)
        self.assertEqual(report["feed_checks"][0]["source"], "Feed A")
        self.assertEqual([item["hostname"] for item in report["dns_tests"]], ["example.com", "openai.com"])
        self.assertEqual(
            [item["url"] for item in report["http_tests"]],
            network_debug.DEFAULT_TEST_URLS,
        )


class MainTests(unittest.TestCase):
    def test_main_json_outputs_serialized_report(self):
        report = {
            "timestamp": "2026-04-21T00:00:00+00:00",
            "cwd": "/repo",
            "python": "3.12.0",
            "platform": "test-platform",
            "env": {},
            "dns_tests": [],
            "tcp_tests": [],
            "http_tests": [],
            "feeds_checked": 0,
            "feed_checks": [],
        }
        stdout = io.StringIO()
        with mock.patch.object(network_debug, "build_report", return_value=report), \
             mock.patch.object(sys, "argv", ["network_debug.py", "--json"]), \
             contextlib.redirect_stdout(stdout):
            network_debug.main()

        self.assertEqual(json.loads(stdout.getvalue()), report)

    def test_main_text_branch_calls_print_text(self):
        report = {
            "timestamp": "2026-04-21T00:00:00+00:00",
            "cwd": "/repo",
            "python": "3.12.0",
            "platform": "test-platform",
            "env": {},
            "dns_tests": [],
            "tcp_tests": [],
            "http_tests": [],
            "feeds_checked": 0,
            "feed_checks": [],
        }
        with mock.patch.object(network_debug, "build_report", return_value=report), \
             mock.patch.object(network_debug, "print_text") as print_text, \
             mock.patch.object(sys, "argv", ["network_debug.py", "--limit", "3", "--timeout", "1.5"]):
            network_debug.main()

        print_text.assert_called_once_with(report)


if __name__ == "__main__":
    unittest.main()
