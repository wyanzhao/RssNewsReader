"""Subprocess step orchestration for the daily pipeline.

Encapsulates the four near-identical subprocess invocations that
``rss_daily_report.py`` previously inlined: build the command, run it,
write stdout to a target file (when applicable), capture stderr to a
sidecar log, and surface the result.
"""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional


@dataclass(frozen=True)
class Step:
    """A single subprocess step in the pipeline."""

    name: str
    script: Path
    args: List[str] = field(default_factory=list)
    # When set, stdout is written verbatim to this path (e.g. raw.json).
    # When None, stdout is captured but not persisted (sub-scripts that write
    # their own --output file follow this pattern).
    stdout_path: Optional[Path] = None
    stderr_path: Optional[Path] = None

    def build_cmd(self) -> List[str]:
        return [sys.executable, str(self.script), *self.args]


@dataclass
class StepResult:
    step: Step
    returncode: int
    stdout: str
    stderr: str

    @property
    def succeeded(self) -> bool:
        return self.returncode == 0

    def echo_stderr(self) -> None:
        """Mirror the captured stderr to the parent process's stderr.

        Behaviour parity with the inlined ``print(... .stderr.strip(), file=sys.stderr)``
        block at the bottom of the original ``rss_daily_report.main``.
        """
        if self.stderr.strip():
            print(self.stderr.strip(), file=sys.stderr)


def _ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def run_step(step: Step) -> StepResult:
    """Run a Step, persist stdout/stderr to disk if requested, return result.

    Behaviour parity guarantees:
    - ``check=False`` so the caller controls exit-code policy.
    - ``capture_output=True, text=True`` matches the historical contract.
    - If ``stdout_path`` is set, stdout is written there (UTF-8) regardless
      of return code (raw.json may be empty/invalid on failure; that is
      validate's job to flag).
    - If ``stderr_path`` is set, stderr is always written, even when empty.
    """
    proc = subprocess.run(step.build_cmd(), capture_output=True,
                          text=True, check=False)

    if step.stdout_path is not None:
        _ensure_parent(step.stdout_path)
        step.stdout_path.write_text(proc.stdout, encoding="utf-8")

    if step.stderr_path is not None:
        _ensure_parent(step.stderr_path)
        step.stderr_path.write_text(proc.stderr, encoding="utf-8")

    return StepResult(
        step=step,
        returncode=proc.returncode,
        stdout=proc.stdout,
        stderr=proc.stderr,
    )
