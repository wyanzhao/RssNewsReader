"""Atomic writes and advisory file locking for shared runtime artifacts.

Concurrent pipeline invocations (duplicate scheduler triggers, a Codex run
racing a Claude Code run) share two cross-run ledgers — the editorial cache
and the seen-links ledger — plus the per-run handoff artifacts. Two rules keep
those safe without a whole-pipeline lock:

- every write goes through :func:`atomic_write_text`, so readers observe either
  the previous complete file or the new complete file, never a torn one;
- read-modify-write cycles on the shared ledgers run under :func:`file_lock`,
  so two concurrent writers cannot silently drop each other's updates.
"""

from __future__ import annotations

import fcntl
import os
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


def atomic_write_text(path: str | Path, text: str) -> None:
    """Write ``text`` to ``path`` via a same-directory temp file + os.replace."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        dir=str(target.parent), prefix=target.name + ".", suffix=".tmp"
    )
    try:
        # mkstemp creates the file 0600; align with the process umask so the
        # replaced file keeps the permissions a plain open() would produce.
        umask = os.umask(0)
        os.umask(umask)
        os.fchmod(fd, 0o666 & ~umask)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(tmp_name, target)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


@contextmanager
def file_lock(path: str | Path) -> Iterator[None]:
    """Exclusive advisory lock on a ``<path>.lock`` sidecar file.

    The sidecar (not the data file) is locked because :func:`atomic_write_text`
    replaces the data file's inode, which would detach any lock held on it.
    Blocking acquisition is fine here: holders only perform a short
    load-update-write cycle.
    """
    lock_path = Path(str(path) + ".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with open(lock_path, "w", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
