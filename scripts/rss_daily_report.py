#!/usr/bin/env python3
"""Thin orchestration entrypoint for the RSS daily report pipeline."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

# Make ``scripts/`` importable when this file is launched directly
# (``python3 scripts/rss_daily_report.py``).
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from _common.paths import (  # noqa: E402
    report_path as build_report_path,
    runs_dir_for,
    stale_run_dirs,
)
from _common.pipeline import Step, StepResult, run_step  # noqa: E402


ROOT_DIR = SCRIPT_DIR.parent
FEEDS_FILE = ROOT_DIR / "feeds.json"
FETCH_SCRIPT = SCRIPT_DIR / "rss_news_monitor.py"
VALIDATE_SCRIPT = SCRIPT_DIR / "qc_validate.py"
RENDER_SCRIPT = SCRIPT_DIR / "render_report.py"
LLM_CONTEXT_SCRIPT = SCRIPT_DIR / "build_llm_context.py"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run fetch -> validate -> render for the RSS daily report."
    )
    parser.add_argument("--hours", type=int, default=24,
                        help="Number of hours to look back (default: 24)")
    parser.add_argument("--max-summary", type=int, default=300,
                        help="Max summary length passed through to the fetch step")
    parser.add_argument("--date", metavar="YYYY-MM-DD",
                        help="Override output date (defaults to local date)")
    parser.add_argument("--runs-dir", default=str(ROOT_DIR / "runs"),
                        help="Directory for pipeline artifacts (default: runs/)")
    parser.add_argument("--json-output", action="store_true",
                        help="Print machine-readable pipeline output metadata as JSON")
    parser.add_argument("--retain-days", type=int, default=90,
                        help="Delete runs/<YYYY-MM-DD>/ folders older than this many "
                             "days (default: 90). Pass 0 to keep nothing past today.")
    parser.add_argument("--no-cleanup", action="store_true",
                        help="Skip the runs/ directory cleanup pass.")
    return parser


def load_json_file(path: Path) -> Optional[Dict[str, Any]]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def build_fallback_validation(fetch_code: int, message: str) -> Dict[str, Any]:
    return {
        "passed": False,
        "blocking_reasons": [message],
        "warnings": [],
        "counts": {
            "configured": 0,
            "results": 0,
            "ok": 0,
            "empty": 0,
            "error": 0,
            "articles": 0,
        },
        "policy": {
            "block_on_error_count_gt": 0,
            "block_on_zero_articles": True,
            "block_on_feed_result_mismatch": True,
        },
        "meta": {
            "validator_exit_code": 40,
            "fallback": True,
            "fetch_exit_code": fetch_code,
        },
    }


def infer_report_path(report_path: Path, validation: Optional[Dict[str, Any]]) -> Path:
    if validation and validation.get("passed") is True:
        return report_path
    return report_path.with_name(f"{report_path.stem}.failed{report_path.suffix}")


def main() -> int:
    args = build_parser().parse_args()

    report_date = args.date or datetime.now().date().isoformat()
    runs_root = Path(args.runs_dir).expanduser().resolve()
    runs_dir = runs_dir_for(runs_root, report_date)
    raw_path = runs_dir / "raw.json"
    validation_path = runs_dir / "validation.json"
    llm_context_path = runs_dir / "llm_context.json"
    fetch_stderr_path = runs_dir / "fetch.stderr.txt"
    validate_stderr_path = runs_dir / "validate.stderr.txt"
    llm_context_stderr_path = runs_dir / "llm_context.stderr.txt"
    render_stderr_path = runs_dir / "render.stderr.txt"
    report_path = build_report_path(ROOT_DIR, report_date)

    fetch_step = Step(
        name="fetch",
        script=FETCH_SCRIPT,
        args=[
            "--json",
            "--hours", str(args.hours),
            "--max-summary", str(args.max_summary),
        ],
        stdout_path=raw_path,
        stderr_path=fetch_stderr_path,
    )
    fetch_result = run_step(fetch_step)

    validate_step = Step(
        name="validate",
        script=VALIDATE_SCRIPT,
        args=["--input", str(raw_path), "--feeds", str(FEEDS_FILE)],
        stderr_path=validate_stderr_path,
    )
    validate_result = run_step(validate_step)

    validation: Optional[Dict[str, Any]] = None
    validator_exit_code = 40
    if validate_result.stdout.strip():
        write_text(validation_path, validate_result.stdout)
        validation = load_json_file(validation_path)

    if validation is None:
        fallback_message = (
            f"Validator did not produce readable JSON. Fetch exit={fetch_result.returncode}, "
            f"validate exit={validate_result.returncode}."
        )
        validation = build_fallback_validation(fetch_result.returncode, fallback_message)
        write_text(validation_path, json.dumps(validation, ensure_ascii=False, indent=2))
        validator_exit_code = 40
    else:
        validator_exit_code = validate_result.returncode

    llm_context_step = Step(
        name="llm_context",
        script=LLM_CONTEXT_SCRIPT,
        args=[
            "--input", str(raw_path),
            "--validation", str(validation_path),
            "--output", str(llm_context_path),
            "--date", report_date,
            "--report-path", str(report_path),
        ],
        stderr_path=llm_context_stderr_path,
    )
    llm_context_result = run_step(llm_context_step)
    if llm_context_result.returncode != 0 and validation.get("passed") is True:
        print(
            f"LLM context step failed with exit code {llm_context_result.returncode}. "
            f"See {llm_context_stderr_path}",
            file=sys.stderr,
        )
        return 40

    render_step = Step(
        name="render",
        script=RENDER_SCRIPT,
        args=[
            "--input", str(raw_path),
            "--validation", str(validation_path),
            "--output", str(report_path),
            "--date", report_date,
        ],
        stderr_path=render_stderr_path,
    )
    render_result = run_step(render_step)
    if render_result.returncode != 0:
        print(
            f"Render step failed with exit code {render_result.returncode}. "
            f"See {render_stderr_path}",
            file=sys.stderr,
        )
        return 40

    final_report = infer_report_path(report_path, validation)
    if not final_report.exists():
        print(
            f"Render step completed but expected output is missing: {final_report}",
            file=sys.stderr,
        )
        return 40

    output_payload = {
        "report_date": report_date,
        "run_dir": str(runs_dir),
        "raw_path": str(raw_path),
        "validation_path": str(validation_path),
        "llm_context_path": str(llm_context_path),
        "report_path": str(final_report),
        "validation_passed": validation.get("passed") is True,
        "validator_exit_code": validator_exit_code,
    }
    if args.json_output:
        print(json.dumps(output_payload, ensure_ascii=False, indent=2))
    else:
        print(str(final_report))

    # Echo step stderr to the parent so cron/journald gets a paper trail —
    # the verbatim text is also persisted in the runs/ sidecar files above.
    for result in (fetch_result, validate_result, llm_context_result):
        result.echo_stderr()

    if not args.no_cleanup and args.retain_days >= 0:
        _cleanup_old_runs(runs_root, args.retain_days)

    return validator_exit_code


def _cleanup_old_runs(runs_root: Path, retain_days: int) -> None:
    """Delete `runs/<YYYY-MM-DD>/` folders older than ``retain_days``.

    Failures are logged to stderr but never propagate — cleanup is best
    effort and must not affect the pipeline's exit code.
    """
    import shutil
    try:
        for stale in stale_run_dirs(runs_root, retain_days):
            try:
                shutil.rmtree(stale)
            except OSError as exc:
                print(f"runs cleanup: failed to remove {stale}: {exc}", file=sys.stderr)
    except Exception as exc:  # pragma: no cover - defensive
        print(f"runs cleanup skipped due to error: {exc}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
