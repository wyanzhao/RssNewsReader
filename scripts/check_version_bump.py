#!/usr/bin/env python3
"""Deterministic gate for the AGENTS.md Version Bump Rule.

Every Android iteration must bump ``versionCode`` by exactly 1 and advance
``versionName`` by one allowed iteration step, in the same change as the work
it versions. A forgotten bump does not fail the build — it fails silently on
the user's phone during upgrade testing (Android refuses to install an APK
whose ``versionCode`` is not strictly greater). This gate catches the missing
bump at review time instead.

The gate is diff-aware: given a base revision (or an explicit list of changed
paths plus base/new versions for hermetic fixtures), it rejects a
bump-required Android change when

- ``versionCode`` is not exactly ``base + 1``, or
- ``versionName`` does not make an allowed iteration step from the base.

Doc-only, test-only, and tooling-only changes under ``android/`` — and
anything confined outside ``android/`` (e.g. ``scripts/``) — never require a
bump. Whether a change "deserves" a *major* step rather than a patch step is
an editorial call the gate deliberately does not make: both a patch step and a
major step are accepted, so ambiguous major-iteration classification stays an
explicit human decision instead of a guess.

Usage:
    # Git mode: compare the working tree against a base revision.
    python3 scripts/check_version_bump.py --base-rev origin/main

    # Fixture mode (hermetic; used by tests): supply paths and versions.
    python3 scripts/check_version_bump.py \\
        --changed-paths android/app/src/main/kotlin/Foo.kt \\
        --base-code 13 --base-name 0.5.3 --new-code 14 --new-name 0.5.4

Exit codes: 0 = pass, 1 = bump rule violated, 2 = usage / parse error.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple

GRADLE_RELPATH = "android/app/build.gradle.kts"

# Basenames under android/ that are build tooling, not shipped app source.
_TOOLING_BASENAMES = {
    "gradlew",
    "gradlew.bat",
    "gradle.properties",
    "local.properties",
    "keystore.properties",
    ".gitignore",
    "lint.xml",
    "libs.versions.toml",
}


# ---------------------------------------------------------------------------
# Version arithmetic
# ---------------------------------------------------------------------------

def parse_version(name: str) -> Tuple[int, int, int]:
    """Parse ``A.B.C`` into a numeric triple. Raises ValueError otherwise."""
    parts = str(name).strip().split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        raise ValueError(f"versionName must look like A.B.C, got {name!r}")
    return int(parts[0]), int(parts[1]), int(parts[2])


def format_version(triple: Tuple[int, int, int]) -> str:
    return ".".join(str(x) for x in triple)


def _carry(triple: Tuple[int, int, int]) -> Tuple[int, int, int]:
    """Normalise so patch and minor each stay within a single digit.

    Patch never exceeds 9 (``0.2.9 + 0.01 -> 0.3.0``); when the minor digit
    rolls past 9 it carries into the leading component (``0.9.x -> 1.0.0``).
    """
    major, minor, patch = triple
    if patch > 9:
        patch = 0
        minor += 1
    if minor > 9:
        minor = 0
        major += 1
    return major, minor, patch


def patch_step(name: str) -> str:
    """The normal per-iteration step: ``versionName`` advances by 0.01."""
    major, minor, patch = parse_version(name)
    return format_version(_carry((major, minor, patch + 1)))


def major_step(name: str) -> str:
    """A major-iteration step: minor advances, patch resets to 0.

    ``0.2.x -> 0.3.0`` and ``0.9.x -> 1.0.0``.
    """
    major, minor, _patch = parse_version(name)
    return format_version(_carry((major, minor + 1, 0)))


def allowed_next_versions(name: str) -> List[str]:
    """Both legal iteration steps from ``name`` (patch and major)."""
    return sorted({patch_step(name), major_step(name)})


# ---------------------------------------------------------------------------
# Change classification
# ---------------------------------------------------------------------------

def classify_path(path: str) -> str:
    """Classify one changed path.

    Returns one of ``"source"`` (bump required), ``"doc"``, ``"test"``,
    ``"tooling"`` (the three documented exclusions), or ``"other"`` for
    anything outside ``android/`` (never requires a bump on its own).
    """
    path = path.strip()
    if not path.startswith("android/"):
        return "other"

    parts = path.split("/")
    base = parts[-1]

    if base.endswith(".md"):
        return "doc"

    # Android test source sets: src/test/, src/testDebug/, src/androidTest/,
    # plus conventional *Test.kt file names wherever they live.
    for seg in parts:
        if seg.startswith("androidTest") or seg.startswith("test"):
            return "test"
    if base.endswith(("Test.kt", "Tests.kt", "Test.java", "Tests.java")):
        return "test"

    if base in _TOOLING_BASENAMES:
        return "tooling"
    if base.endswith((".gradle", ".gradle.kts", ".pro")):
        return "tooling"
    if "/gradle/wrapper/" in path:
        return "tooling"

    return "source"


def bump_required(changed_paths: List[str]) -> bool:
    """True when at least one changed path ships real Android source."""
    return any(classify_path(p) == "source" for p in changed_paths)


# ---------------------------------------------------------------------------
# Decision
# ---------------------------------------------------------------------------

def evaluate(changed_paths: List[str], base_code: int, base_name: str,
             new_code: int, new_name: str) -> Tuple[bool, str]:
    """Apply the Version Bump Rule. Returns ``(ok, reason)``."""
    if not bump_required(changed_paths):
        return True, "no bump-required Android source change"

    errors: List[str] = []
    if new_code != base_code + 1:
        errors.append(
            f"versionCode must be {base_code + 1} (base {base_code} + 1), "
            f"got {new_code}"
        )

    try:
        allowed = allowed_next_versions(base_name)
    except ValueError as exc:
        return False, f"cannot parse base versionName: {exc}"

    if new_name not in allowed:
        errors.append(
            f"versionName must step from {base_name} to one of {allowed}, "
            f"got {new_name}"
        )

    if errors:
        return False, "; ".join(errors)
    return True, f"valid bump {base_name}({base_code}) -> {new_name}({new_code})"


# ---------------------------------------------------------------------------
# Gradle parsing
# ---------------------------------------------------------------------------

def read_versions_from_text(text: str) -> Tuple[int, str]:
    """Extract ``(versionCode, versionName)`` from build.gradle.kts text."""
    code_match = re.search(r"\bversionCode\s*=\s*(\d+)", text)
    name_match = re.search(r"\bversionName\s*=\s*\"([^\"]+)\"", text)
    if not code_match or not name_match:
        raise ValueError(
            "could not find versionCode and versionName in build.gradle.kts"
        )
    return int(code_match.group(1)), name_match.group(1)


def _git(repo_root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def gather_from_git(repo_root: Path, base_rev: str) -> Tuple[List[str], int, str, int, str]:
    """Derive changed paths and base/new versions from git."""
    changed = [
        line for line in _git(repo_root, "diff", "--name-only", base_rev).splitlines()
        if line.strip()
    ]
    base_text = _git(repo_root, "show", f"{base_rev}:{GRADLE_RELPATH}")
    base_code, base_name = read_versions_from_text(base_text)
    new_gradle = repo_root / GRADLE_RELPATH
    if not new_gradle.exists():
        raise RuntimeError(f"missing {GRADLE_RELPATH} in working tree")
    new_code, new_name = read_versions_from_text(
        new_gradle.read_text(encoding="utf-8")
    )
    return changed, base_code, base_name, new_code, new_name


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Gate for the AGENTS.md Android Version Bump Rule."
    )
    parser.add_argument("--base-rev",
                        help="Git base revision to diff against (git mode).")
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[1]),
                        help="Repository root for git mode.")
    parser.add_argument("--changed-paths", nargs="*", default=None,
                        help="Explicit changed paths (fixture mode).")
    parser.add_argument("--base-code", type=int, help="Base versionCode (fixture mode).")
    parser.add_argument("--base-name", help="Base versionName (fixture mode).")
    parser.add_argument("--new-code", type=int, help="New versionCode (fixture mode).")
    parser.add_argument("--new-name", help="New versionName (fixture mode).")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    fixture_mode = args.changed_paths is not None
    if fixture_mode:
        if None in (args.base_code, args.new_code) or not args.base_name or not args.new_name:
            print(
                "[FATAL] fixture mode requires --changed-paths plus "
                "--base-code/--base-name/--new-code/--new-name",
                file=sys.stderr,
            )
            return 2
        changed = args.changed_paths
        base_code, base_name = args.base_code, args.base_name
        new_code, new_name = args.new_code, args.new_name
    else:
        if not args.base_rev:
            print(
                "[FATAL] provide --base-rev (git mode) or --changed-paths with "
                "explicit versions (fixture mode)",
                file=sys.stderr,
            )
            return 2
        try:
            changed, base_code, base_name, new_code, new_name = gather_from_git(
                Path(args.repo_root), args.base_rev,
            )
        except (RuntimeError, ValueError) as exc:
            print(f"[FATAL] {exc}", file=sys.stderr)
            return 2

    try:
        ok, reason = evaluate(changed, base_code, base_name, new_code, new_name)
    except ValueError as exc:
        print(f"[FATAL] {exc}", file=sys.stderr)
        return 2

    if ok:
        print(f"PASS: {reason}")
        return 0
    print(f"FAIL: {reason}", file=sys.stderr)
    print(
        "The Android Version Bump Rule requires versionCode +1 and a valid "
        "versionName step in the same change as the work. See AGENTS.md "
        "'Version Bump Rule'.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
