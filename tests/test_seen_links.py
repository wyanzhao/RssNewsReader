"""Unit tests for scripts/_common/seen_links.py.

The ledger closes the run-time-jitter coverage gap: the fetch window is wider
than 24h and boundary articles already published in an earlier day's report
are dropped at fetch time. These tests pin the three load-bearing rules:
earlier-date entries filter, same-date entries do NOT (same-day re-runs stay
idempotent), and only assemble-recorded links ever enter the ledger.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.seen_links import (  # noqa: E402
    DEFAULT_SEEN_LINKS_MAX_AGE_DAYS,
    default_seen_links_path,
    filter_previously_reported,
    load_seen_links,
    prune_seen_links,
    record_reported_links,
    write_seen_links,
)
from _common.text import dedup_link_key  # noqa: E402


def _articles(*links: str) -> list:
    return [{"link": link, "title": "T"} for link in links]


class FilterTests(unittest.TestCase):
    def test_earlier_date_filters_same_date_keeps(self):
        entries = {
            dedup_link_key("https://x/old"): "2026-07-02",
            dedup_link_key("https://x/today"): "2026-07-03",
        }
        kept, dropped = filter_previously_reported(
            _articles("https://x/old", "https://x/today", "https://x/new"),
            entries,
            "2026-07-03",
        )
        self.assertEqual([a["link"] for a in kept], ["https://x/today", "https://x/new"])
        self.assertEqual(dropped, 1)

    def test_empty_ledger_is_a_noop(self):
        articles = _articles("https://x/1")
        kept, dropped = filter_previously_reported(articles, {}, "2026-07-03")
        self.assertEqual(kept, articles)
        self.assertEqual(dropped, 0)

    def test_articles_without_links_are_kept(self):
        kept, dropped = filter_previously_reported(
            [{"link": "", "title": "no link"}], {"k": "2026-07-01"}, "2026-07-03"
        )
        self.assertEqual(len(kept), 1)
        self.assertEqual(dropped, 0)


class RecordAndPruneTests(unittest.TestCase):
    def test_record_keeps_newest_date(self):
        entries = {dedup_link_key("https://x/1"): "2026-07-01"}
        record_reported_links(entries, ["https://x/1", "https://x/2"], "2026-07-03")
        self.assertEqual(entries[dedup_link_key("https://x/1")], "2026-07-03")
        self.assertEqual(entries[dedup_link_key("https://x/2")], "2026-07-03")
        # An older re-record never regresses the date.
        record_reported_links(entries, ["https://x/1"], "2026-07-02")
        self.assertEqual(entries[dedup_link_key("https://x/1")], "2026-07-03")

    def test_prune_drops_only_stale_entries(self):
        entries = {
            "fresh": "2026-07-01",
            "stale": "2026-06-01",
            "bad-date": "not-a-date",
        }
        prune_seen_links(entries, "2026-07-03", max_age_days=DEFAULT_SEEN_LINKS_MAX_AGE_DAYS)
        self.assertEqual(set(entries), {"fresh"})


class RoundTripTests(unittest.TestCase):
    def test_write_and_load_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "_seen_links.json"
            write_seen_links(path, {"k1": "2026-07-03"})
            self.assertEqual(load_seen_links(path), {"k1": "2026-07-03"})

    def test_missing_file_is_empty_ledger(self):
        self.assertEqual(load_seen_links(SCRIPTS / "no_such_ledger.json"), {})

    def test_invalid_structure_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "_seen_links.json"
            path.write_text(json.dumps({"entries": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_seen_links(path)

    def test_default_path_sits_next_to_cache(self):
        path = default_seen_links_path("/repo/runs/2026-07-03/llm_context.json")
        self.assertEqual(str(path), "/repo/runs/_seen_links.json")


if __name__ == "__main__":
    unittest.main()
