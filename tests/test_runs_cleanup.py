"""Stage-2 tests for runs/ retention policy."""

from __future__ import annotations

import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.paths import (  # noqa: E402
    iter_run_date_dirs,
    report_path,
    runs_dir_for,
    stale_run_dirs,
)


class PathTemplateTests(unittest.TestCase):
    def test_runs_dir_for_appends_date(self):
        out = runs_dir_for(Path("/tmp/r"), "2026-04-10")
        self.assertEqual(out.name, "2026-04-10")

    def test_report_path_success_default(self):
        out = report_path(Path("/repo"), "2026-04-10")
        self.assertEqual(out, Path("/repo/rss-report-2026-04-10.md"))

    def test_report_path_failed_suffix(self):
        out = report_path(Path("/repo"), "2026-04-10", failed=True)
        self.assertEqual(out, Path("/repo/rss-report-2026-04-10.failed.md"))


class IterRunDateDirsTests(unittest.TestCase):
    def test_filters_non_date_directories(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "2026-04-10").mkdir()
            (root / "2026-04-09").mkdir()
            (root / "fixtures").mkdir()
            (root / "README").touch()
            names = sorted(p.name for p in iter_run_date_dirs(root))
            self.assertEqual(names, ["2026-04-09", "2026-04-10"])

    def test_missing_root_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            ghost = Path(tmpdir) / "no_such_dir"
            self.assertEqual(list(iter_run_date_dirs(ghost)), [])


class StaleRunDirsTests(unittest.TestCase):
    def _seed(self, root: Path, dates):
        for d in dates:
            (root / d).mkdir()
            (root / d / "raw.json").write_text("{}", encoding="utf-8")

    def test_only_dirs_older_than_retain_days_returned(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            today = date(2026, 4, 14)
            past = (today - timedelta(days=200)).isoformat()
            edge = (today - timedelta(days=90)).isoformat()
            recent = (today - timedelta(days=10)).isoformat()
            current = today.isoformat()
            self._seed(root, [past, edge, recent, current])

            stale = stale_run_dirs(root, retain_days=90, today=today)
            stale_names = sorted(p.name for p in stale)
            # cutoff = today - 90 days = edge date; "<" means older than edge
            self.assertEqual(stale_names, [past])

    def test_zero_retain_keeps_today_only(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            today = date(2026, 4, 14)
            yesterday = (today - timedelta(days=1)).isoformat()
            current = today.isoformat()
            self._seed(root, [yesterday, current])

            stale = stale_run_dirs(root, retain_days=0, today=today)
            self.assertEqual([p.name for p in stale], [yesterday])

    def test_negative_retain_raises(self):
        with self.assertRaises(ValueError):
            stale_run_dirs(Path("/tmp"), retain_days=-1)

    def test_unparseable_directory_names_ignored(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            today = date(2026, 4, 14)
            (root / "2025-01-01").mkdir()
            (root / "fixtures-backup").mkdir()
            stale = stale_run_dirs(root, retain_days=30, today=today)
            self.assertEqual([p.name for p in stale], ["2025-01-01"])


class SchemasImportTests(unittest.TestCase):
    """Smoke check that schemas module imports cleanly and exposes constants."""

    def test_status_constants_match_validator_values(self):
        from _common.schemas import STATUS_OK, STATUS_EMPTY, STATUS_ERROR
        self.assertEqual(STATUS_OK, "ok")
        self.assertEqual(STATUS_EMPTY, "empty")
        self.assertEqual(STATUS_ERROR, "error")

    def test_typed_dicts_importable(self):
        # Ensure the names AGENTS.md and the plan promised actually exist.
        from _common import schemas as s
        for name in ("RawDocument", "ValidationDocument", "LlmContextDocument",
                     "PipelineOutput", "FeedResult", "LlmArticle"):
            self.assertTrue(hasattr(s, name), f"missing TypedDict: {name}")


if __name__ == "__main__":
    unittest.main()
