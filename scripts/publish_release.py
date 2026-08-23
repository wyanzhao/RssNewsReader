#!/usr/bin/env python3
"""Transactional GitHub source-push + signed-APK publish entrypoint.

The AGENTS.md rule is that a ``git push`` of this repo to GitHub is incomplete
until a signed ``app-release.apk`` is attached to the GitHub Release whose tag
matches the APK's ``versionName``. This script is the one repository-local
command that joins those steps:

1. refuse a missing or unsigned APK (and refuse the unsigned filename);
2. read ``versionName`` / ``versionCode`` back from the artifact, never from
   memory or from ``build.gradle.kts``;
3. require the release tag ``v<versionName>`` to match that read-back;
4. push the source branch and mutate the GitHub Release **only** after
   explicit ``--authorize``;
5. confirm ``app-release.apk`` is actually present on the release after upload.

It does not talk to 1Password and does not assemble the APK. Signing stays the
documented ``assembleRelease`` recipe in AGENTS.md; this command consumes the
already-signed artifact.

Usage:
    python3 scripts/publish_release.py --verify-only
    python3 scripts/publish_release.py --authorize

Exit codes: 0 = success, 1 = APK / push / upload / post-check failure,
2 = usage or missing authorization.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence

PACKAGE_NAME = "com.dailynews.app"
SIGNED_ASSET = "app-release.apk"
UNSIGNED_ASSET = "app-release-unsigned.apk"
DEFAULT_APK = Path("android/app/build/outputs/apk/release") / SIGNED_ASSET
DEFAULT_BUILD_TOOLS = "35.0.0"
DEFAULT_REMOTE = "origin"
DEFAULT_BRANCH = "main"

RunFn = Callable[..., subprocess.CompletedProcess]


class PublishError(RuntimeError):
    """A refused or failed publish step. ``exit_code`` is 1 or 2."""

    def __init__(self, message: str, exit_code: int = 1):
        super().__init__(message)
        self.exit_code = exit_code


def default_run(argv: Sequence[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(
        list(argv), capture_output=True, text=True, check=False, **kwargs,
    )


def read_sdk_dir(repo_root: Path) -> Path:
    props = repo_root / "android" / "local.properties"
    if not props.is_file():
        raise PublishError(f"missing {props}; cannot locate Android SDK")
    for line in props.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("sdk.dir="):
            raw = stripped.split("=", 1)[1]
            # Gradle writes Windows paths with escaped backslashes.
            return Path(raw.replace("\\:", ":").replace("\\\\", "\\"))
    raise PublishError(f"no sdk.dir in {props}")


def tool_path(sdk_dir: Path, build_tools: str, name: str) -> Path:
    path = sdk_dir / "build-tools" / build_tools / name
    if not path.is_file():
        raise PublishError(f"missing Android build-tool {path}")
    return path


def parse_badging(text: str) -> Dict[str, str]:
    """Parse ``aapt dump badging`` for package / versionCode / versionName."""
    match = re.search(
        r"package:\s*name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
        text,
    )
    if not match:
        raise PublishError("aapt dump badging did not contain package/version fields")
    return {
        "package": match.group(1),
        "versionCode": match.group(2),
        "versionName": match.group(3),
    }


def has_v2_or_v3(apksigner_verbose: str) -> bool:
    """True when apksigner reported a v2 or v3 scheme as verified."""
    v2 = re.search(r"Verified using v2 scheme[^:]*:\s*true", apksigner_verbose)
    v3 = re.search(r"Verified using v3 scheme[^:]*:\s*true", apksigner_verbose)
    return bool(v2 or v3)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def release_notes(version: str, code: str, sha256: str) -> str:
    return "\n".join([
        "Signed Android release APK for DailyNews.",
        "",
        f"- Package: `{PACKAGE_NAME}`",
        f"- Version: `{version}` (`versionCode` {code})",
        "- Signing: APK Signature Scheme v2 / v3",
        f"- APK SHA-256: `{sha256}`",
        "",
    ])


def _run_or_fail(run: RunFn, argv: Sequence[str], *, what: str,
                 cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
    result = run(list(argv), cwd=str(cwd) if cwd is not None else None)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        extra = f": {detail}" if detail else ""
        raise PublishError(f"{what} failed (exit {result.returncode}){extra}")
    return result


def verify_apk(apk: Path, apksigner: Path, aapt: Path, run: RunFn,
               expect_version: Optional[str] = None) -> Dict[str, str]:
    """Refuse a missing, misnamed, or unsigned APK; return read-back identity."""
    if apk.name == UNSIGNED_ASSET:
        raise PublishError(
            f"{UNSIGNED_ASSET} must never be tagged, attached, or handed over"
        )
    if not apk.is_file():
        raise PublishError(f"missing APK: {apk}")

    verify = run([str(apksigner), "verify", "--verbose", str(apk)])
    if verify.returncode != 0 or not has_v2_or_v3(verify.stdout or ""):
        detail = (verify.stderr or verify.stdout or "").strip()
        extra = f": {detail}" if detail else ""
        raise PublishError(f"APK is missing or not v2/v3 signed{extra}")

    badging = _run_or_fail(
        run, [str(aapt), "dump", "badging", str(apk)], what="aapt dump badging",
    )
    identity = parse_badging(badging.stdout or "")
    if identity["package"] != PACKAGE_NAME:
        raise PublishError(
            f"APK package is {identity['package']!r}, expected {PACKAGE_NAME!r}"
        )
    if expect_version and identity["versionName"] != expect_version:
        raise PublishError(
            f"APK versionName is {identity['versionName']!r}, "
            f"expected {expect_version!r}"
        )
    identity["sha256"] = sha256_file(apk)
    identity["tag"] = f"v{identity['versionName']}"
    return identity


def _asset_names(payload: object) -> List[str]:
    if not isinstance(payload, dict):
        return []
    assets = payload.get("assets") or []
    names: List[str] = []
    if isinstance(assets, list):
        for item in assets:
            if isinstance(item, dict) and item.get("name"):
                names.append(str(item["name"]))
    return names


def confirm_release_asset(tag: str, run: RunFn, cwd: Path) -> None:
    result = _run_or_fail(
        run,
        ["gh", "release", "view", tag, "--json", "assets,tagName"],
        what=f"gh release view {tag}",
        cwd=cwd,
    )
    try:
        payload = json.loads(result.stdout or "{}")
    except json.JSONDecodeError as exc:
        raise PublishError(f"gh release view {tag} returned non-JSON") from exc
    names = _asset_names(payload)
    if SIGNED_ASSET not in names:
        raise PublishError(
            f"GitHub Release {tag} has no {SIGNED_ASSET} asset "
            f"(found: {names or 'none'})"
        )
    if UNSIGNED_ASSET in names:
        raise PublishError(
            f"GitHub Release {tag} must not carry {UNSIGNED_ASSET}"
        )


def publish(
    repo_root: Path,
    apk: Path,
    *,
    remote: str = DEFAULT_REMOTE,
    branch: str = DEFAULT_BRANCH,
    authorize: bool = False,
    verify_only: bool = False,
    expect_version: Optional[str] = None,
    sdk_dir: Optional[Path] = None,
    build_tools: str = DEFAULT_BUILD_TOOLS,
    run: Optional[RunFn] = None,
) -> Dict[str, str]:
    """Verify the APK, then (when authorized) push and publish it.

    Returns the APK identity dict on success. Raises :class:`PublishError`
    for every refused or failed step. Never mutates git or GitHub unless
    ``authorize`` is true.
    """
    if authorize and verify_only:
        raise PublishError("pass only one of --authorize or --verify-only", 2)

    runner = run or default_run
    sdk = sdk_dir if sdk_dir is not None else read_sdk_dir(repo_root)
    apksigner = tool_path(sdk, build_tools, "apksigner")
    aapt = tool_path(sdk, build_tools, "aapt")
    identity = verify_apk(apk, apksigner, aapt, runner, expect_version)
    tag = identity["tag"]

    if verify_only:
        return identity
    if not authorize:
        raise PublishError(
            "refusing to push or mutate GitHub without --authorize; "
            f"re-run with --authorize to push {branch} to {remote} and "
            f"publish {apk.name} as {tag}",
            2,
        )

    _run_or_fail(
        runner, ["git", "push", remote, branch],
        what=f"git push {remote} {branch}",
        cwd=repo_root,
    )

    notes = release_notes(
        identity["versionName"], identity["versionCode"], identity["sha256"],
    )
    view = runner(
        ["gh", "release", "view", tag], cwd=str(repo_root),
    )
    if view.returncode == 0:
        _run_or_fail(
            runner,
            ["gh", "release", "upload", tag, str(apk), "--clobber"],
            what=f"gh release upload {tag}",
            cwd=repo_root,
        )
        _run_or_fail(
            runner,
            ["gh", "release", "edit", tag, "--notes", notes],
            what=f"gh release edit {tag}",
            cwd=repo_root,
        )
    else:
        _run_or_fail(
            runner,
            [
                "gh", "release", "create", tag, str(apk),
                "--title", f"DailyNews {identity['versionName']}",
                "--notes", notes,
            ],
            what=f"gh release create {tag}",
            cwd=repo_root,
        )

    confirm_release_asset(tag, runner, repo_root)
    identity["published"] = tag
    return identity


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify a signed release APK, then push and publish it."
    )
    parser.add_argument("--apk", default=str(DEFAULT_APK),
                        help=f"Path to {SIGNED_ASSET} (default: {DEFAULT_APK})")
    parser.add_argument("--remote", default=DEFAULT_REMOTE)
    parser.add_argument("--branch", default=DEFAULT_BRANCH)
    parser.add_argument("--expect-version",
                        help="Refuse if the APK versionName does not equal this.")
    parser.add_argument("--sdk-dir",
                        help="Android SDK root (default: android/local.properties sdk.dir).")
    parser.add_argument("--build-tools", default=DEFAULT_BUILD_TOOLS)
    parser.add_argument("--authorize", action="store_true",
                        help="Permit git push and GitHub release mutation.")
    parser.add_argument("--verify-only", action="store_true",
                        help="Stop after APK signature and version read-back.")
    parser.add_argument("--repo-root",
                        default=str(Path(__file__).resolve().parents[1]))
    return parser


def main(argv: Optional[List[str]] = None,
         run: Optional[RunFn] = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = Path(args.repo_root).expanduser().resolve()
    apk = Path(args.apk)
    if not apk.is_absolute():
        apk = (repo_root / apk).resolve()
    sdk_dir = Path(args.sdk_dir).expanduser() if args.sdk_dir else None
    try:
        identity = publish(
            repo_root,
            apk,
            remote=args.remote,
            branch=args.branch,
            authorize=args.authorize,
            verify_only=args.verify_only,
            expect_version=args.expect_version,
            sdk_dir=sdk_dir,
            build_tools=args.build_tools,
            run=run,
        )
    except PublishError as exc:
        print(f"[FATAL] {exc}", file=sys.stderr)
        return exc.exit_code

    print(
        f"APK {SIGNED_ASSET}: {identity['package']} "
        f"{identity['versionName']} (versionCode {identity['versionCode']}) "
        f"tag {identity['tag']}"
    )
    print(f"SHA-256: {identity['sha256']}")
    if identity.get("published"):
        print(f"Published {identity['published']} with {SIGNED_ASSET} verified on the release.")
    elif args.verify_only:
        print("Verify-only: no git push and no GitHub mutation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
