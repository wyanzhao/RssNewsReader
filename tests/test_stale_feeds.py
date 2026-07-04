"""Stale-feed detection: an HTTP-healthy feed whose newest item is old must
surface a warning (never a block). Regression guard for the failure mode where
SemiAnalysis went dormant for ~10 months without any signal."""

from __future__ import annotations

import unittest

from tests.test_qc_offline import materialize_raw, run_validator


def _set_newest(raw, source, iso):
    for item in raw["feed_results"]:
        if item["source"] == source:
            item["newest_item_date"] = iso
            return
    raise AssertionError(f"source not in fixture feed_results: {source}")


class StaleFeedWarningTests(unittest.TestCase):
    # Fixture meta.generated_at_utc is 2026-04-10T22:00:00Z.

    def test_stale_feed_produces_warning_only(self):
        raw = materialize_raw("golden_success.json")
        _set_newest(raw, "OpenAI Blog", "2026-02-01T00:00:00+00:00")  # 68d old
        code, validation = run_validator(raw)
        self.assertEqual(code, 0)
        self.assertTrue(validation["passed"])
        stale = [w for w in validation["warnings"] if w.startswith("1 stale feed(s):")]
        self.assertEqual(len(stale), 1)
        self.assertIn("OpenAI Blog (newest item 68d old)", stale[0])

    def test_fresh_and_missing_dates_do_not_warn(self):
        raw = materialize_raw("golden_success.json")
        _set_newest(raw, "OpenAI Blog", "2026-04-09T00:00:00+00:00")  # 1d old
        # Other fixture feeds carry no newest_item_date at all.
        code, validation = run_validator(raw)
        self.assertEqual(code, 0)
        self.assertFalse(any("stale feed(s)" in w for w in validation["warnings"]))

    def test_error_feeds_are_excluded_from_stale_check(self):
        raw = materialize_raw("golden_success.json")
        for item in raw["feed_results"]:
            if item["source"] == "OpenAI Blog":
                item["status"] = "error"
                item["error"] = "HTTP 500"
                item["newest_item_date"] = "2025-01-01T00:00:00+00:00"
        code, validation = run_validator(raw)
        self.assertEqual(code, 0)
        self.assertFalse(any("stale feed(s)" in w for w in validation["warnings"]))

    def test_threshold_respects_runtime_config_snapshot(self):
        raw = materialize_raw("golden_success.json")
        raw["runtime_config"]["fetch"] = {"stale_feed_warn_days": 100}
        _set_newest(raw, "OpenAI Blog", "2026-02-01T00:00:00+00:00")  # 68d < 100
        code, validation = run_validator(raw)
        self.assertEqual(code, 0)
        self.assertFalse(any("stale feed(s)" in w for w in validation["warnings"]))


if __name__ == "__main__":
    unittest.main()
