"""Focused fixtures for the AGENTS.md Android Version Bump Rule.

The gate lives in ``scripts/check_version_bump.py``. These tests supply
explicit path lists and version pairs (no git, no Gradle) so a bump-required
Android change cannot pass without the matching ``versionCode`` / ``versionName``
update, while the documented doc / test / tooling / scripts exclusions stay
bump-free.
"""

from __future__ import annotations

import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import check_version_bump as bump  # noqa: E402

SOURCE = "android/app/src/main/kotlin/com/dailynews/app/ui/Foo.kt"
DOC = "android/README.md"
TEST = "android/app/src/test/kotlin/com/dailynews/app/FooTest.kt"
TEST_DEBUG = "android/app/src/testDebug/kotlin/com/dailynews/app/ui/ScreenShotTest.kt"
ANDROID_TEST = "android/app/src/androidTest/kotlin/com/dailynews/app/FooTest.kt"
TOOLING_GRADLEW = "android/gradlew"
TOOLING_GRADLE = "android/app/build.gradle.kts"
TOOLING_WRAPPER = "android/gradle/wrapper/gradle-wrapper.properties"
SCRIPTS_PATH = "scripts/rss_daily_report.py"
AGENTS = "AGENTS.md"

GRADLE_TEXT = """\
android {
    defaultConfig {
        applicationId = "com.dailynews.app"
        versionCode = 13
        versionName = "0.5.3"
    }
}
"""


class VersionArithmeticTests(unittest.TestCase):
    def test_patch_step_is_plus_one_hundredth(self):
        self.assertEqual(bump.patch_step("0.2.0"), "0.2.1")
        self.assertEqual(bump.patch_step("0.5.3"), "0.5.4")
        self.assertEqual(bump.patch_step("1.0.0"), "1.0.1")

    def test_carry_bump_rolls_patch_into_minor(self):
        self.assertEqual(bump.patch_step("0.2.9"), "0.3.0")
        self.assertEqual(bump.patch_step("0.5.9"), "0.6.0")

    def test_major_step_advances_minor_and_resets_patch(self):
        self.assertEqual(bump.major_step("0.2.0"), "0.3.0")
        self.assertEqual(bump.major_step("0.2.7"), "0.3.0")
        self.assertEqual(bump.major_step("0.9.5"), "1.0.0")

    def test_nine_dot_nine_carry_and_major_agree(self):
        self.assertEqual(bump.patch_step("0.9.9"), "1.0.0")
        self.assertEqual(bump.major_step("0.9.9"), "1.0.0")

    def test_allowed_next_versions_include_both_legal_steps(self):
        self.assertEqual(bump.allowed_next_versions("0.5.3"), ["0.5.4", "0.6.0"])
        # From 0.2.9 the patch carry and the major step coincide.
        self.assertEqual(bump.allowed_next_versions("0.2.9"), ["0.3.0"])


class ClassifyPathTests(unittest.TestCase):
    def test_shipped_kotlin_is_source(self):
        self.assertEqual(bump.classify_path(SOURCE), "source")
        self.assertEqual(
            bump.classify_path("android/core/llm/src/main/kotlin/com/dailynews/llm/X.kt"),
            "source",
        )
        self.assertEqual(
            bump.classify_path("android/app/src/main/res/xml/backup_rules.xml"),
            "source",
        )

    def test_documented_exclusions(self):
        self.assertEqual(bump.classify_path(DOC), "doc")
        self.assertEqual(bump.classify_path(TEST), "test")
        self.assertEqual(bump.classify_path(TEST_DEBUG), "test")
        self.assertEqual(bump.classify_path(ANDROID_TEST), "test")
        self.assertEqual(bump.classify_path(TOOLING_GRADLEW), "tooling")
        self.assertEqual(bump.classify_path(TOOLING_GRADLE), "tooling")
        self.assertEqual(bump.classify_path(TOOLING_WRAPPER), "tooling")

    def test_outside_android_is_other(self):
        self.assertEqual(bump.classify_path(SCRIPTS_PATH), "other")
        self.assertEqual(bump.classify_path(AGENTS), "other")
        self.assertEqual(bump.classify_path("tests/test_feed_fetch.py"), "other")


def _eval(paths, *, new_code=14, new_name="0.5.4",
          base_code=13, base_name="0.5.3"):
    return bump.evaluate(list(paths), base_code, base_name, new_code, new_name)


class EvaluateRuleTests(unittest.TestCase):
    def test_normal_patch_bump_passes(self):
        ok, reason = _eval([SOURCE])
        self.assertTrue(ok, reason)
        self.assertIn("0.5.3", reason)
        self.assertIn("0.5.4", reason)

    def test_carry_bump_passes(self):
        ok, reason = _eval(
            [SOURCE], base_code=9, base_name="0.2.9",
            new_code=10, new_name="0.3.0",
        )
        self.assertTrue(ok, reason)

    def test_major_bump_is_accepted_not_guessed(self):
        # 0.5.3 -> 0.6.0 is a legal major step; the gate does not decide
        # whether the change "deserved" it.
        ok, reason = _eval([SOURCE], new_code=14, new_name="0.6.0")
        self.assertTrue(ok, reason)

    def test_missing_version_code_bump_fails(self):
        ok, reason = _eval([SOURCE], new_code=13, new_name="0.5.4")
        self.assertFalse(ok)
        self.assertIn("versionCode", reason)

    def test_missing_version_name_bump_fails(self):
        ok, reason = _eval([SOURCE], new_code=14, new_name="0.5.3")
        self.assertFalse(ok)
        self.assertIn("versionName", reason)

    def test_version_code_plus_two_is_rejected(self):
        ok, reason = _eval([SOURCE], new_code=15, new_name="0.5.4")
        self.assertFalse(ok)
        self.assertIn("versionCode", reason)

    def test_skipped_version_name_is_rejected(self):
        ok, reason = _eval([SOURCE], new_code=14, new_name="0.5.5")
        self.assertFalse(ok)
        self.assertIn("versionName", reason)

    def test_doc_only_exclusion(self):
        ok, reason = _eval([DOC], new_code=13, new_name="0.5.3")
        self.assertTrue(ok, reason)

    def test_test_only_exclusion(self):
        ok, reason = _eval(
            [TEST, TEST_DEBUG, ANDROID_TEST],
            new_code=13, new_name="0.5.3",
        )
        self.assertTrue(ok, reason)

    def test_tooling_only_exclusion(self):
        ok, reason = _eval(
            [TOOLING_GRADLEW, TOOLING_GRADLE, TOOLING_WRAPPER],
            new_code=13, new_name="0.5.3",
        )
        self.assertTrue(ok, reason)

    def test_scripts_only_exclusion(self):
        ok, reason = _eval(
            [SCRIPTS_PATH, AGENTS, "tests/test_version_bump.py"],
            new_code=13, new_name="0.5.3",
        )
        self.assertTrue(ok, reason)

    def test_source_plus_exclusion_still_requires_the_bump(self):
        ok, reason = _eval(
            [SOURCE, DOC, TEST, SCRIPTS_PATH],
            new_code=13, new_name="0.5.3",
        )
        self.assertFalse(ok)
        self.assertIn("versionCode", reason)


class ReadGradleTests(unittest.TestCase):
    def test_reads_version_pair_from_gradle_text(self):
        code, name = bump.read_versions_from_text(GRADLE_TEXT)
        self.assertEqual((code, name), (13, "0.5.3"))

    def test_missing_fields_raise(self):
        with self.assertRaises(ValueError):
            bump.read_versions_from_text("android { }\n")


class CliFixtureModeTests(unittest.TestCase):
    def _run(self, extra):
        buf = io.StringIO()
        with redirect_stdout(buf), redirect_stderr(buf):
            return bump.main([
                "--changed-paths", *extra["paths"],
                "--base-code", str(extra.get("base_code", 13)),
                "--base-name", extra.get("base_name", "0.5.3"),
                "--new-code", str(extra["new_code"]),
                "--new-name", extra["new_name"],
            ])

    def test_cli_passes_a_normal_bump(self):
        self.assertEqual(self._run({
            "paths": [SOURCE], "new_code": 14, "new_name": "0.5.4",
        }), 0)

    def test_cli_fails_before_handoff_when_the_bump_is_absent(self):
        self.assertEqual(self._run({
            "paths": [SOURCE], "new_code": 13, "new_name": "0.5.3",
        }), 1)

    def test_cli_passes_a_doc_only_change_without_a_bump(self):
        self.assertEqual(self._run({
            "paths": [DOC], "new_code": 13, "new_name": "0.5.3",
        }), 0)

    def test_usage_error_without_mode_args(self):
        buf = io.StringIO()
        with redirect_stdout(buf), redirect_stderr(buf):
            self.assertEqual(bump.main([]), 2)


class GitModeTests(unittest.TestCase):
    """End-to-end against a throwaway git repo, not this working tree."""

    def _git(self, repo: Path, *args: str) -> None:
        subprocess.run(
            [
                "git",
                "-c", "user.email=t@t",
                "-c", "user.name=t",
                "-c", "commit.gpgsign=false",
                *args,
            ],
            cwd=repo, check=True, capture_output=True, text=True,
        )

    def _write(self, repo: Path, rel: str, text: str) -> None:
        path = repo / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_source_change_without_bump_fails_against_base_rev(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._git(repo, "init")
            self._write(repo, bump.GRADLE_RELPATH, GRADLE_TEXT)
            self._write(repo, SOURCE, "fun a() {}\n")
            self._git(repo, "add", "-A")
            self._git(repo, "commit", "-m", "base")

            self._write(repo, SOURCE, "fun a() { /* changed */ }\n")

            buf = io.StringIO()
            with redirect_stdout(buf), redirect_stderr(buf):
                rc = bump.main(["--repo-root", str(repo), "--base-rev", "HEAD"])
            self.assertEqual(rc, 1)

            bumped = GRADLE_TEXT.replace("versionCode = 13", "versionCode = 14")
            bumped = bumped.replace('versionName = "0.5.3"', 'versionName = "0.5.4"')
            self._write(repo, bump.GRADLE_RELPATH, bumped)
            with redirect_stdout(buf), redirect_stderr(buf):
                rc = bump.main(["--repo-root", str(repo), "--base-rev", "HEAD"])
            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
