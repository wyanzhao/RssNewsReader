"""Date-scoped run isolation for rss_daily_report.py.

Two overlapping invocations for the same report date must not overwrite or
mismatch each other's raw / validation / context / stderr evidence. The
pipeline serializes each date under an advisory ``runs/<date>/run.lock``; the
loser of that race either waits (bounded) or aborts with exit 40 *before
writing any artifact*, which is exactly what these tests pin.

The mechanism tests use threads (``flock`` is per-open-file-description, so
two threads with separate handles contend just like two processes). The
integration test runs the real ``rss_daily_report.py`` as a subprocess while
this process holds the lock, proving the loser writes nothing.
"""

from __future__ import annotations

import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.fsio import LockTimeoutError, file_lock  # noqa: E402


class _Holder:
    """Hold the date lock on a background thread until told to release."""

    def __init__(self, lock_path: Path) -> None:
        self._lock_path = lock_path
        self.acquired = threading.Event()
        self.release = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def _run(self) -> None:
        with file_lock(self._lock_path):
            self.acquired.set()
            self.release.wait(timeout=30)

    def start(self) -> None:
        self._thread.start()
        self.acquired.wait(timeout=10)

    def stop(self) -> None:
        self.release.set()
        self._thread.join(timeout=10)


class BoundedFileLockTests(unittest.TestCase):
    """fsio.file_lock honours the bounded-wait deadline."""

    def test_waiter_times_out_while_held_then_acquires_after_release(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "runs" / "2026-01-01" / "run"
            holder = _Holder(lock_path)
            holder.start()
            try:
                start = time.monotonic()
                with self.assertRaises(LockTimeoutError):
                    with file_lock(lock_path, timeout=0.3):
                        pass
                elapsed = time.monotonic() - start
                self.assertGreaterEqual(elapsed, 0.25)
                self.assertLess(elapsed, 5.0)
            finally:
                holder.stop()

            # Once released, the same lock is acquirable again.
            with file_lock(lock_path, timeout=1.0):
                pass

    def test_zero_timeout_fails_immediately_when_held(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "run"
            holder = _Holder(lock_path)
            holder.start()
            try:
                start = time.monotonic()
                with self.assertRaises(LockTimeoutError):
                    with file_lock(lock_path, timeout=0):
                        pass
                self.assertLess(time.monotonic() - start, 1.0)
            finally:
                holder.stop()

    def test_unbounded_default_still_blocks_until_release(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "run"
            holder = _Holder(lock_path)
            holder.start()
            # Release shortly; an unbounded waiter must then get the lock.
            timer = threading.Timer(0.3, holder.stop)
            timer.start()
            with file_lock(lock_path):
                pass
            timer.cancel()


class SameDateRunIntegrationTests(unittest.TestCase):
    """A second same-date pipeline invocation loses the lock cleanly.

    The subprocess is pointed at a throwaway ``--runs-dir`` and a short
    ``--lock-wait-seconds``; this process holds the date lock the whole time.
    The loser must exit 40 with a well-formed control-plane JSON and must not
    write raw.json / validation.json / any stderr sidecar.
    """

    def _invoke(self, runs_root: Path, date: str, wait_seconds: float):
        return subprocess.run(
            [
                sys.executable, str(SCRIPTS / "rss_daily_report.py"),
                "--runs-dir", str(runs_root),
                "--date", date,
                "--lock-wait-seconds", str(wait_seconds),
                "--json-output",
                "--no-cleanup",
            ],
            capture_output=True, text=True, timeout=120,
        )

    def test_loser_aborts_before_writing_any_artifact(self):
        import json
        import tempfile
        date = "2026-01-01"
        with tempfile.TemporaryDirectory() as tmp:
            runs_root = Path(tmp) / "runs"
            lock_path = runs_root / date / "run"
            holder = _Holder(lock_path)
            holder.start()
            try:
                proc = self._invoke(runs_root, date, wait_seconds=0.5)
            finally:
                holder.stop()

            self.assertEqual(proc.returncode, 40, proc.stderr)
            payload = json.loads(proc.stdout)
            self.assertFalse(payload["validation_passed"])
            self.assertEqual(payload["validator_exit_code"], 40)
            self.assertEqual(payload["report_date"], date)
            # The loser wrote none of the date's pipeline evidence.
            run_dir = runs_root / date
            for name in ("raw.json", "validation.json", "llm_context.json",
                         "fetch.stderr.txt", "validate.stderr.txt",
                         "llm_context.stderr.txt", "render.stderr.txt"):
                self.assertFalse((run_dir / name).exists(),
                                 f"loser must not write {name}")
            self.assertIn("same-date run", proc.stderr)

    def test_sole_invocation_acquires_lock_and_enters_pipeline(self):
        # Hermetic success path: with no one holding the lock, main() acquires
        # it and hands off to the pipeline body. _run_locked is stubbed so the
        # test never touches the network; the lock sidecar must exist after.
        import tempfile
        from unittest import mock
        import rss_daily_report

        date = "2026-01-02"
        with tempfile.TemporaryDirectory() as tmp:
            runs_root = Path(tmp) / "runs"
            argv = [
                "rss_daily_report.py",
                "--runs-dir", str(runs_root),
                "--date", date,
                "--lock-wait-seconds", "0.5",
                "--no-cleanup",
            ]
            calls = []

            def fake_run_locked(*a, **k):
                calls.append(a)
                return 0

            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(rss_daily_report, "_run_locked",
                                      side_effect=fake_run_locked):
                rc = rss_daily_report.main()

            self.assertEqual(rc, 0)
            self.assertEqual(len(calls), 1, "pipeline body must run exactly once")
            self.assertTrue((runs_root / date / "run.lock").exists())


if __name__ == "__main__":
    unittest.main()
