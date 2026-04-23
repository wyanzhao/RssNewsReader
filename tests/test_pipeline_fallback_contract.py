"""Focused regression coverage for rss_daily_report fallback validation.json."""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
PIPELINE_SCRIPT = ROOT / "scripts" / "rss_daily_report.py"

EXPECTED_COUNT_KEYS = {
    "configured",
    "results",
    "ok",
    "empty",
    "error",
    "articles",
}

EXPECTED_POLICY_KEYS = {
    "block_on_error_count",
    "block_on_zero_articles",
    "block_on_feed_results_mismatch",
    "empty_is_warning_only",
    "unique_source_count_is_observational",
}


def _import_pipeline_module():
    spec = importlib.util.spec_from_file_location(
        "rss_daily_report_under_test",
        PIPELINE_SCRIPT,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("Failed to import rss_daily_report.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PipelineFallbackContractTests(unittest.TestCase):
    def _write_script(self, path: Path, body: str) -> Path:
        path.write_text(body, encoding="utf-8")
        return path

    def _run_pipeline_with_unreadable_validation(self,
                                                 validate_stdout: str,
                                                 fetch_exit: int = 17,
                                                 validate_exit: int = 40):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            feeds_path = tmp / "feeds.json"
            feeds_path.write_text('{"feeds": []}\n', encoding="utf-8")

            raw_inline = json.dumps({"articles": []}, ensure_ascii=False)
            fetch_script = self._write_script(
                tmp / "stub_fetch.py",
                "import sys\n"
                f"sys.stdout.write({raw_inline!r})\n"
                "sys.stdout.write('\\n')\n"
                f"raise SystemExit({fetch_exit})\n",
            )
            validate_script = self._write_script(
                tmp / "stub_validate.py",
                "import sys\n"
                f"sys.stdout.write({validate_stdout!r})\n"
                f"raise SystemExit({validate_exit})\n",
            )
            llm_context_script = self._write_script(
                tmp / "stub_llm_context.py",
                "import json, sys\n"
                "from pathlib import Path\n"
                "args = sys.argv\n"
                "output = Path(args[args.index('--output') + 1])\n"
                "payload = {\n"
                "    'meta': {},\n"
                "    'validation': {},\n"
                "    'candidate_articles': [],\n"
                "    'all_articles': [],\n"
                "    'source_groups': [],\n"
                "}\n"
                "output.write_text(json.dumps(payload), encoding='utf-8')\n",
            )
            render_script = self._write_script(
                tmp / "stub_render.py",
                "import json, sys\n"
                "from pathlib import Path\n"
                "args = sys.argv\n"
                "validation_path = Path(args[args.index('--validation') + 1])\n"
                "output = Path(args[args.index('--output') + 1])\n"
                "validation = json.loads(validation_path.read_text(encoding='utf-8'))\n"
                "target = output if validation.get('passed') is True else "
                "output.with_name(f'{output.stem}.failed{output.suffix}')\n"
                "target.write_text('# fallback report\\n', encoding='utf-8')\n",
            )

            module = _import_pipeline_module()
            module.ROOT_DIR = tmp
            module.FEEDS_FILE = feeds_path
            module.FETCH_SCRIPT = fetch_script
            module.VALIDATE_SCRIPT = validate_script
            module.LLM_CONTEXT_SCRIPT = llm_context_script
            module.RENDER_SCRIPT = render_script

            stdout = io.StringIO()
            stderr = io.StringIO()
            argv = [
                "rss_daily_report.py",
                "--json-output",
                "--runs-dir", str(tmp / "runs"),
                "--date", "2026-04-10",
                "--no-cleanup",
            ]
            with patch.object(sys, "argv", argv):
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    exit_code = module.main()

            payload = json.loads(stdout.getvalue())
            validation_path = Path(payload["validation_path"])
            validation = json.loads(validation_path.read_text(encoding="utf-8"))
            return exit_code, payload, validation

    def test_fallback_validation_schema_is_stable_for_unreadable_validator_output(self):
        cases = [
            ("", "empty stdout"),
            ("{not-json}\n", "malformed stdout"),
        ]

        for validate_stdout, label in cases:
            with self.subTest(case=label):
                exit_code, payload, validation = self._run_pipeline_with_unreadable_validation(
                    validate_stdout
                )

                self.assertEqual(exit_code, 40)
                self.assertFalse(payload["validation_passed"])
                self.assertEqual(payload["validator_exit_code"], 40)
                self.assertTrue(payload["report_path"].endswith(".failed.md"))

                self.assertFalse(validation["passed"])
                self.assertEqual(
                    validation["blocking_reasons"],
                    [
                        "Validator did not produce readable JSON. "
                        "Fetch exit=17, validate exit=40."
                    ],
                )
                self.assertEqual(validation["warnings"], [])
                self.assertEqual(set(validation["counts"].keys()), EXPECTED_COUNT_KEYS)
                self.assertEqual(
                    validation["counts"],
                    {
                        "configured": 0,
                        "results": 0,
                        "ok": 0,
                        "empty": 0,
                        "error": 0,
                        "articles": 0,
                    },
                )
                self.assertEqual(set(validation["policy"].keys()), EXPECTED_POLICY_KEYS)
                self.assertEqual(
                    validation["policy"],
                    {
                        "block_on_error_count": False,
                        "block_on_zero_articles": True,
                        "block_on_feed_results_mismatch": True,
                        "empty_is_warning_only": True,
                        "unique_source_count_is_observational": True,
                    },
                )
                self.assertNotIn("block_on_error_count_gt", validation["policy"])
                self.assertNotIn("block_on_feed_result_mismatch", validation["policy"])
                self.assertTrue(validation["meta"]["fallback"])
                self.assertEqual(validation["meta"]["fetch_exit_code"], 17)
                self.assertEqual(validation["meta"]["validate_exit_code"], 40)
                self.assertEqual(validation["meta"]["validator_exit_code"], 40)


if __name__ == "__main__":
    unittest.main()
