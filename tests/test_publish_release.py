"""Process-level tests for scripts/publish_release.py.

Every external command (apksigner, aapt, git, gh) is mocked. These tests never
touch 1Password, never push, and never mutate GitHub.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import publish_release as pub  # noqa: E402

SIGNED_VERBOSE = """\
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
"""

UNSIGNED_VERBOSE = """\
DOES NOT VERIFY
ERROR: no APK Signing Block
"""

BADGING = (
    "package: name='com.dailynews.app' versionCode='14' "
    "versionName='0.5.4' platformBuildVersionName='15'\n"
    "application-label:'DailyNews'\n"
)

ASSETS_JSON = json.dumps({
    "tagName": "v0.5.4",
    "assets": [{"name": "app-release.apk", "size": 12}],
})


def _proc(returncode=0, stdout="", stderr=""):
    return SimpleNamespace(returncode=returncode, stdout=stdout, stderr=stderr)


class ScriptedRunner:
    """Dispatch mocked command results by argv[0] basename + subcommand."""

    def __init__(self) -> None:
        self.calls: list[list[str]] = []
        self.apksigner = _proc(0, SIGNED_VERBOSE)
        self.aapt = _proc(0, BADGING)
        self.git_push = _proc(0, "")
        self.gh_view_exists = _proc(1, "", "release not found")
        self.gh_view_assets = _proc(0, ASSETS_JSON)
        self.gh_create = _proc(0, "")
        self.gh_upload = _proc(0, "")
        self.gh_edit = _proc(0, "")
        self._view_hits = 0

    def __call__(self, argv, **kwargs):
        argv = list(argv)
        self.calls.append(argv)
        name = Path(argv[0]).name
        if name == "apksigner":
            return self.apksigner
        if name == "aapt":
            return self.aapt
        if name == "git":
            return self.git_push
        if name == "gh":
            sub = argv[2] if len(argv) > 2 else ""
            if sub == "view":
                self._view_hits += 1
                # Existence probe (no --json) vs post-upload confirmation.
                if "--json" in argv:
                    return self.gh_view_assets
                return self.gh_view_exists
            if sub == "create":
                return self.gh_create
            if sub == "upload":
                return self.gh_upload
            if sub == "edit":
                return self.gh_edit
        raise AssertionError(f"unexpected command: {argv}")

    def names(self) -> list[str]:
        out = []
        for argv in self.calls:
            base = Path(argv[0]).name
            if base == "gh" and len(argv) > 2:
                out.append(f"gh {argv[2]}")
            elif base == "git":
                out.append("git " + " ".join(argv[1:3]))
            else:
                out.append(base)
        return out


def _layout(tmp: str):
    root = Path(tmp)
    sdk = root / "sdk" / "build-tools" / pub.DEFAULT_BUILD_TOOLS
    sdk.mkdir(parents=True)
    (sdk / "apksigner").write_text("", encoding="utf-8")
    (sdk / "aapt").write_text("", encoding="utf-8")
    apk_dir = root / "android" / "app" / "build" / "outputs" / "apk" / "release"
    apk_dir.mkdir(parents=True)
    apk = apk_dir / pub.SIGNED_ASSET
    apk.write_bytes(b"apk-bytes")
    return root, sdk.parent.parent, apk


class ParseHelpersTests(unittest.TestCase):
    def test_parse_badging(self):
        ident = pub.parse_badging(BADGING)
        self.assertEqual(ident["package"], "com.dailynews.app")
        self.assertEqual(ident["versionCode"], "14")
        self.assertEqual(ident["versionName"], "0.5.4")

    def test_v2_or_v3_detection(self):
        self.assertTrue(pub.has_v2_or_v3(SIGNED_VERBOSE))
        self.assertFalse(pub.has_v2_or_v3(UNSIGNED_VERBOSE))
        self.assertFalse(pub.has_v2_or_v3("Verified using v1 scheme (JAR signing): true\n"))


class PublishProcessTests(unittest.TestCase):
    def test_missing_apk_is_refused_before_any_command(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            apk.unlink()
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("missing APK", str(ctx.exception))
        self.assertEqual(runner.calls, [])

    def test_unsigned_filename_is_refused(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            unsigned = apk.with_name(pub.UNSIGNED_ASSET)
            unsigned.write_bytes(b"nope")
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, unsigned, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("never", str(ctx.exception))
        self.assertEqual(runner.calls, [])

    def test_unsigned_apk_is_refused(self):
        runner = ScriptedRunner()
        runner.apksigner = _proc(1, UNSIGNED_VERBOSE, "DOES NOT VERIFY")
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("not v2/v3 signed", str(ctx.exception))
        self.assertEqual(runner.names(), ["apksigner"])
        self.assertNotIn("git push", " ".join(runner.names()))

    def test_version_mismatch_is_refused_before_push(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(
                    root, apk, sdk_dir=sdk, run=runner, authorize=True,
                    expect_version="0.5.3",
                )
            self.assertIn("versionName", str(ctx.exception))
            self.assertIn("0.5.4", str(ctx.exception))
        self.assertNotIn("git push origin", " ".join(
            " ".join(c) for c in runner.calls
        ))

    def test_authorization_required_before_push_or_github_mutation(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("--authorize", str(ctx.exception))
        self.assertEqual(set(runner.names()), {"apksigner", "aapt"})

    def test_verify_only_does_not_push(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            identity = pub.publish(
                root, apk, sdk_dir=sdk, run=runner, verify_only=True,
            )
        self.assertEqual(identity["versionName"], "0.5.4")
        self.assertEqual(identity["tag"], "v0.5.4")
        self.assertEqual(set(runner.names()), {"apksigner", "aapt"})

    def test_push_failure_does_not_mutate_github(self):
        runner = ScriptedRunner()
        runner.git_push = _proc(1, "", "permission denied")
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("git push", str(ctx.exception))
        self.assertEqual(
            [n for n in runner.names() if n.startswith("gh")], [],
        )

    def test_upload_failure_is_surfaced(self):
        runner = ScriptedRunner()
        runner.gh_view_exists = _proc(0, "v0.5.4")  # release already exists
        runner.gh_upload = _proc(1, "", "upload failed")
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("upload", str(ctx.exception))

    def test_create_failure_is_surfaced(self):
        runner = ScriptedRunner()
        runner.gh_create = _proc(1, "", "create failed")
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("create", str(ctx.exception))

    def test_successful_create_then_post_upload_verification(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            identity = pub.publish(
                root, apk, sdk_dir=sdk, run=runner, authorize=True,
            )
        self.assertEqual(identity["published"], "v0.5.4")
        self.assertEqual(identity["versionCode"], "14")
        names = runner.names()
        self.assertIn("git push", names[2] if len(names) > 2 else "")
        self.assertIn("gh create", names)
        self.assertNotIn("gh upload", names)
        # Last gh view is the JSON confirmation.
        self.assertEqual(names[-1], "gh view")
        json_views = [
            c for c in runner.calls
            if Path(c[0]).name == "gh" and "--json" in c
        ]
        self.assertEqual(len(json_views), 1)

    def test_existing_release_uploads_with_clobber(self):
        runner = ScriptedRunner()
        runner.gh_view_exists = _proc(0, "already there")
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            identity = pub.publish(
                root, apk, sdk_dir=sdk, run=runner, authorize=True,
            )
        self.assertEqual(identity["published"], "v0.5.4")
        self.assertIn("gh upload", runner.names())
        self.assertIn("gh edit", runner.names())
        self.assertNotIn("gh create", runner.names())
        upload = [c for c in runner.calls if Path(c[0]).name == "gh" and "upload" in c][0]
        self.assertIn("--clobber", upload)

    def test_post_upload_missing_asset_fails_the_run(self):
        runner = ScriptedRunner()
        runner.gh_view_assets = _proc(0, json.dumps({
            "tagName": "v0.5.4",
            "assets": [{"name": "notes.txt"}],
        }))
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            with self.assertRaises(pub.PublishError) as ctx:
                pub.publish(root, apk, sdk_dir=sdk, run=runner, authorize=True)
            self.assertIn("app-release.apk", str(ctx.exception))

    def test_cli_verify_only_exit_zero(self):
        runner = ScriptedRunner()
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            buf = io.StringIO()
            with redirect_stdout(buf), redirect_stderr(buf):
                rc = pub.main(
                    [
                        "--repo-root", str(root),
                        "--apk", str(apk),
                        "--sdk-dir", str(sdk),
                        "--verify-only",
                    ],
                    run=runner,
                )
        self.assertEqual(rc, 0)

    def test_cli_refuses_unsigned_with_exit_one(self):
        runner = ScriptedRunner()
        runner.apksigner = _proc(1, UNSIGNED_VERBOSE)
        with tempfile.TemporaryDirectory() as tmp:
            root, sdk, apk = _layout(tmp)
            buf = io.StringIO()
            with redirect_stdout(buf), redirect_stderr(buf):
                rc = pub.main(
                    [
                        "--repo-root", str(root),
                        "--apk", str(apk),
                        "--sdk-dir", str(sdk),
                        "--authorize",
                    ],
                    run=runner,
                )
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
