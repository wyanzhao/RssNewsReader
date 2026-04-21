"""Centralised filesystem layout for the daily pipeline.

All path templates live here so renaming `rss-report-YYYY-MM-DD.md` or moving
the runs/ root only touches one file.
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable, List


REPORT_FILENAME_TEMPLATE = "rss-report-{date}.md"
FAILED_REPORT_SUFFIX = ".failed.md"
RUN_DATE_DIR_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def runs_dir_for(runs_root: Path, report_date: str) -> Path:
    """Return the per-day artifacts directory inside ``runs_root``."""
    return Path(runs_root).expanduser().resolve() / report_date


def report_path(repo_root: Path, report_date: str, *, failed: bool = False) -> Path:
    """Return the success or failure markdown path for a given report date."""
    base = Path(repo_root) / REPORT_FILENAME_TEMPLATE.format(date=report_date)
    if failed:
        return base.with_name(f"{base.stem}{FAILED_REPORT_SUFFIX}")
    return base


def iter_run_date_dirs(runs_root: Path) -> Iterable[Path]:
    """Yield child directories of ``runs_root`` whose name looks like YYYY-MM-DD."""
    runs_root = Path(runs_root)
    if not runs_root.is_dir():
        return []
    return (
        child for child in runs_root.iterdir()
        if child.is_dir() and RUN_DATE_DIR_PATTERN.match(child.name)
    )


def stale_run_dirs(runs_root: Path, retain_days: int, *,
                   today: date | None = None) -> List[Path]:
    """Return run-date directories older than ``retain_days``.

    ``today`` defaults to UTC today. Directories whose name does not parse as
    YYYY-MM-DD are ignored (never touched).
    """
    if retain_days < 0:
        raise ValueError("retain_days must be non-negative")
    today = today or datetime.now(timezone.utc).date()
    cutoff = today - timedelta(days=retain_days)

    stale: List[Path] = []
    for child in iter_run_date_dirs(runs_root):
        try:
            child_date = datetime.strptime(child.name, "%Y-%m-%d").date()
        except ValueError:
            continue
        if child_date < cutoff:
            stale.append(child)
    return sorted(stale)
