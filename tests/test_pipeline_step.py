"""Stage-1 unit tests for scripts/_common/pipeline.py."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from _common.pipeline import Step, run_step  # noqa: E402


class StepBuildCmdTests(unittest.TestCase):
    def test_build_cmd_uses_current_python(self):
        step = Step(name="x", script=Path("/tmp/dummy.py"), args=["--flag"])
        cmd = step.build_cmd()
        self.assertEqual(cmd[0], sys.executable)
        self.assertEqual(cmd[1], "/tmp/dummy.py")
        self.assertEqual(cmd[2], "--flag")


class RunStepTests(unittest.TestCase):
    def _write_script(self, body: str, tmp: Path) -> Path:
        path = tmp / "stub.py"
        path.write_text(body, encoding="utf-8")
        return path

    def test_run_step_persists_stdout_and_stderr(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            script = self._write_script(
                "import sys\n"
                "sys.stdout.write('hello-stdout\\n')\n"
                "sys.stderr.write('hello-stderr\\n')\n",
                tmp,
            )
            stdout_path = tmp / "out.txt"
            stderr_path = tmp / "err.txt"
            step = Step(
                name="probe",
                script=script,
                stdout_path=stdout_path,
                stderr_path=stderr_path,
            )
            result = run_step(step)
            self.assertEqual(result.returncode, 0)
            self.assertTrue(result.succeeded)
            self.assertEqual(stdout_path.read_text(encoding="utf-8"), "hello-stdout\n")
            self.assertEqual(stderr_path.read_text(encoding="utf-8"), "hello-stderr\n")
            self.assertEqual(result.stdout, "hello-stdout\n")
            self.assertEqual(result.stderr, "hello-stderr\n")

    def test_run_step_creates_parent_directories(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            script = self._write_script("print('x')\n", tmp)
            stdout_path = tmp / "deep" / "nested" / "out.txt"
            stderr_path = tmp / "deep" / "nested" / "err.txt"
            step = Step(name="p", script=script,
                        stdout_path=stdout_path, stderr_path=stderr_path)
            result = run_step(step)
            self.assertEqual(result.returncode, 0)
            self.assertTrue(stdout_path.exists())
            self.assertTrue(stderr_path.exists())

    def test_run_step_propagates_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            script = self._write_script("import sys; sys.exit(30)\n", tmp)
            step = Step(name="p", script=script)
            result = run_step(step)
            self.assertEqual(result.returncode, 30)
            self.assertFalse(result.succeeded)

    def test_run_step_no_paths_does_not_error(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            script = self._write_script("print('ok')\n", tmp)
            step = Step(name="p", script=script)
            result = run_step(step)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout.strip(), "ok")


class CliHelperTests(unittest.TestCase):
    def test_add_io_args_minimal(self):
        import argparse
        from _common.cli import add_io_args

        parser = argparse.ArgumentParser()
        add_io_args(parser)
        ns = parser.parse_args(["--input", "raw.json"])
        self.assertEqual(ns.input, "raw.json")
        self.assertIsNone(ns.date)

    def test_add_io_args_with_validation_and_output(self):
        import argparse
        from _common.cli import add_io_args

        parser = argparse.ArgumentParser()
        add_io_args(parser, require_validation=True, require_output=True)
        ns = parser.parse_args([
            "--input", "r.json",
            "--validation", "v.json",
            "--output", "o.json",
            "--date", "2026-04-10",
        ])
        self.assertEqual(ns.validation, "v.json")
        self.assertEqual(ns.output, "o.json")
        self.assertEqual(ns.date, "2026-04-10")


if __name__ == "__main__":
    unittest.main()
