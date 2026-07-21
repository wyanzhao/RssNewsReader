from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from _common.fsio import atomic_write_text, file_lock  # noqa: E402


class AtomicWriteTests(unittest.TestCase):
    def test_creates_parents_and_writes_content(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "nested" / "dir" / "out.json"
            atomic_write_text(target, '{"ok": true}')
            self.assertEqual(json.loads(target.read_text(encoding="utf-8")), {"ok": True})

    def test_replaces_existing_file_and_leaves_no_temp_siblings(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            target = Path(tmpdir) / "out.txt"
            target.write_text("old", encoding="utf-8")
            atomic_write_text(target, "new")
            self.assertEqual(target.read_text(encoding="utf-8"), "new")
            leftovers = [
                path.name
                for path in Path(tmpdir).iterdir()
                if path.name != "out.txt"
            ]
            self.assertEqual(leftovers, [])


class FileLockTests(unittest.TestCase):
    LOCK_PROBE = (
        "import fcntl, sys\n"
        "handle = open(sys.argv[1], 'w')\n"
        "try:\n"
        "    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
        "except OSError:\n"
        "    print('blocked')\n"
        "else:\n"
        "    print('acquired')\n"
    )

    def _probe(self, lock_path: Path) -> str:
        proc = subprocess.run(
            [sys.executable, "-c", self.LOCK_PROBE, str(lock_path)],
            capture_output=True,
            text=True,
            check=True,
        )
        return proc.stdout.strip()

    def test_lock_excludes_other_processes_while_held(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            data_path = Path(tmpdir) / "ledger.json"
            lock_path = Path(str(data_path) + ".lock")
            with file_lock(data_path):
                self.assertTrue(lock_path.exists())
                self.assertEqual(self._probe(lock_path), "blocked")
            self.assertEqual(self._probe(lock_path), "acquired")


if __name__ == "__main__":
    unittest.main()
