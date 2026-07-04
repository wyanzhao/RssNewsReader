"""Unit tests for scripts/_common/editorial_cache.py.

These lock the editorial summary cache behaviour that the build_llm_context /
editorial_runtime paths depend on: a stable link-based key (so summaries are
actually reused across runs), legacy-key rollover on lookup, and age-based
pruning so the cache file cannot grow without bound.
"""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.editorial_cache import (  # noqa: E402
    DEFAULT_CACHE_MAX_AGE_DAYS,
    cache_key,
    event_key,
    legacy_cache_key,
    load_cache,
    lookup_entry,
    prune_stale_entries,
    update_entries,
)


class CacheKeyTests(unittest.TestCase):
    def test_key_is_deterministic(self):
        article = {"link": "https://x/1", "summary_en": "s", "article_text": "b"}
        self.assertEqual(cache_key(article), cache_key(dict(article)))

    def test_key_independent_of_volatile_summary_and_body(self):
        """The whole point of the v2 key: same link -> same key even when the
        feed summary or extracted body drift between runs."""
        run1 = {"link": "https://x/1", "summary_en": "old summary", "article_text": "old body"}
        run2 = {"link": "https://x/1", "summary_en": "new summary text", "article_text": "different body"}
        self.assertEqual(cache_key(run1), cache_key(run2))

    def test_distinct_links_have_distinct_keys(self):
        self.assertNotEqual(
            cache_key({"link": "https://x/1"}),
            cache_key({"link": "https://x/2"}),
        )

    def test_link_is_whitespace_normalized(self):
        self.assertEqual(
            cache_key({"link": "  https://x/1  "}),
            cache_key({"link": "https://x/1"}),
        )

    def test_v2_key_differs_from_legacy_key(self):
        article = {"link": "https://x/1", "summary_en": "s", "article_text": "b"}
        self.assertNotEqual(cache_key(article), legacy_cache_key(article))


class LookupRolloverTests(unittest.TestCase):
    def test_lookup_finds_v2_entry(self):
        article = {"link": "https://x/1", "summary_en": "s", "article_text": "b"}
        cache = {"version": 1, "entries": {cache_key(article): {"summary_zh": "中文"}}}
        self.assertEqual(lookup_entry(article, cache)["summary_zh"], "中文")

    def test_lookup_falls_back_to_legacy_entry(self):
        article = {"link": "https://x/1", "summary_en": "s", "article_text": "b"}
        cache = {"version": 1, "entries": {legacy_cache_key(article): {"summary_zh": "旧缓存"}}}
        self.assertEqual(lookup_entry(article, cache)["summary_zh"], "旧缓存")

    def test_lookup_prefers_v2_over_legacy(self):
        article = {"link": "https://x/1", "summary_en": "s", "article_text": "b"}
        cache = {
            "version": 1,
            "entries": {
                cache_key(article): {"summary_zh": "新"},
                legacy_cache_key(article): {"summary_zh": "旧"},
            },
        }
        self.assertEqual(lookup_entry(article, cache)["summary_zh"], "新")

    def test_lookup_returns_none_for_empty_or_missing(self):
        self.assertIsNone(lookup_entry({"link": "https://x/1"}, None))
        self.assertIsNone(lookup_entry({"link": "https://x/1"}, {"entries": {}}))


class PruneTests(unittest.TestCase):
    def _entry(self, age_days: float, now: datetime) -> dict:
        ts = (now - timedelta(days=age_days)).isoformat()
        return {"summary_zh": "x", "updated_at_utc": ts}

    def test_drops_old_keeps_recent(self):
        now = datetime(2026, 6, 21, tzinfo=timezone.utc)
        cache = {
            "entries": {
                "fresh": self._entry(1, now),
                "stale": self._entry(DEFAULT_CACHE_MAX_AGE_DAYS + 5, now),
            }
        }
        prune_stale_entries(cache, max_age_days=DEFAULT_CACHE_MAX_AGE_DAYS, now=now)
        self.assertIn("fresh", cache["entries"])
        self.assertNotIn("stale", cache["entries"])

    def test_keeps_entries_with_missing_or_bad_timestamp(self):
        now = datetime(2026, 6, 21, tzinfo=timezone.utc)
        cache = {
            "entries": {
                "no_ts": {"summary_zh": "x"},
                "bad_ts": {"summary_zh": "x", "updated_at_utc": "not-a-date"},
            }
        }
        prune_stale_entries(cache, max_age_days=30, now=now)
        self.assertEqual(set(cache["entries"]), {"no_ts", "bad_ts"})

    def test_negative_age_disables_pruning(self):
        now = datetime(2026, 6, 21, tzinfo=timezone.utc)
        cache = {"entries": {"stale": self._entry(1000, now)}}
        prune_stale_entries(cache, max_age_days=-1, now=now)
        self.assertIn("stale", cache["entries"])


class UpdateEntriesTests(unittest.TestCase):
    def test_writes_under_v2_key_and_is_roundtrippable(self):
        article = {"link": "https://x/1", "source": "S", "title": "T",
                   "summary_en": "s", "article_text": "b"}
        cache = {"version": 1, "entries": {}}
        now = datetime(2026, 6, 21, tzinfo=timezone.utc)
        update_entries(
            cache,
            {"https://x/1": article},
            [{"link": "https://x/1", "summary_zh": "中文摘要", "noise_bucket": "selected"}],
            now=now,
        )
        self.assertIn(cache_key(article), cache["entries"])
        # A later run with drifted summary/body still resolves the same entry.
        drifted = {"link": "https://x/1", "summary_en": "changed", "article_text": "changed"}
        self.assertEqual(lookup_entry(drifted, cache)["summary_zh"], "中文摘要")

    def test_update_prunes_stale_but_keeps_new(self):
        now = datetime(2026, 6, 21, tzinfo=timezone.utc)
        old_ts = (now - timedelta(days=DEFAULT_CACHE_MAX_AGE_DAYS + 10)).isoformat()
        cache = {"version": 1, "entries": {"ancient": {"summary_zh": "old", "updated_at_utc": old_ts}}}
        article = {"link": "https://x/1", "source": "S", "title": "T"}
        update_entries(
            cache,
            {"https://x/1": article},
            [{"link": "https://x/1", "summary_zh": "新"}],
            now=now,
        )
        self.assertNotIn("ancient", cache["entries"])
        self.assertIn(cache_key(article), cache["entries"])

    def test_skips_items_without_link_or_article(self):
        cache = {"version": 1, "entries": {}}
        update_entries(
            cache,
            {},  # no articles to resolve against
            [{"link": "https://x/unknown", "summary_zh": "x"}, {"summary_zh": "no link"}],
            now=datetime(2026, 6, 21, tzinfo=timezone.utc),
        )
        self.assertEqual(cache["entries"], {})

    def test_part1_and_part2_summaries_use_separate_slots(self):
        """A Top-30 link carries both styles; the 60-180字 Part 1 event summary
        must never clobber the 40-60字 Part 2 slot (and vice versa)."""
        article = {"link": "https://x/1", "source": "S", "title": "Big Event"}
        cache = {"version": 1, "entries": {}}
        now = datetime(2026, 7, 3, tzinfo=timezone.utc)
        update_entries(
            cache,
            {"https://x/1": article},
            [
                {"link": "https://x/1", "summary_zh": "短摘要", "part": "part2"},
                {"link": "https://x/1", "summary_zh": "长的事件级摘要",
                 "noise_bucket": "major_capital", "event_key": "big-event", "part": "part1"},
            ],
            now=now,
        )
        entry = cache["entries"][cache_key(article)]
        self.assertEqual(entry["summary_zh"], "短摘要")
        self.assertEqual(entry["part1_summary_zh"], "长的事件级摘要")
        self.assertEqual(entry["part1_noise_bucket"], "major_capital")
        self.assertEqual(entry["event_key"], "big-event")

    def test_part1_item_without_title_derives_event_key_from_article(self):
        """Link-keyed part1 items carry no title; the event slug must fall back
        to the authoritative article title, not an empty string."""
        article = {"link": "https://x/1", "source": "S", "title": "OpenAI Ships GPT"}
        cache = {"version": 1, "entries": {}}
        update_entries(
            cache,
            {"https://x/1": article},
            [{"link": "https://x/1", "summary_zh": "摘要", "part": "part1"}],
            now=datetime(2026, 7, 3, tzinfo=timezone.utc),
        )
        entry = cache["entries"][cache_key(article)]
        self.assertEqual(entry["event_key"], "openai-ships-gpt")


class LoadCacheTests(unittest.TestCase):
    def test_missing_file_returns_empty_cache(self):
        cache = load_cache(SCRIPTS / "does_not_exist_cache.json")
        self.assertEqual(cache, {"version": 1, "entries": {}})

    def test_rejects_non_object_entries(self, ):
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "cache.json"
            path.write_text(json.dumps({"version": 1, "entries": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_cache(path)


class EventKeyTests(unittest.TestCase):
    def test_prefers_explicit_event_key(self):
        self.assertEqual(event_key({"event_key": "explicit-event", "title": "T"}), "explicit-event")

    def test_derives_slug_from_title(self):
        self.assertEqual(event_key({"title": "OpenAI Ships GPT!"}), "openai-ships-gpt")

    def test_truncates_long_title_slug(self):
        slug = event_key({"title": "word " * 80})
        self.assertLessEqual(len(slug), 120)


if __name__ == "__main__":
    unittest.main()
