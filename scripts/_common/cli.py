"""Shared argparse helpers used by the pipeline sub-scripts."""

from __future__ import annotations

import argparse


def add_io_args(parser: argparse.ArgumentParser, *,
                require_input: bool = True,
                require_validation: bool = False,
                require_output: bool = False) -> None:
    """Attach the standard --input / --validation / --output / --date trio.

    The flags keep the historical names that ``rss_daily_report.py`` already
    passes to its sub-scripts. ``required`` flags mirror what each consumer
    needs.
    """
    parser.add_argument(
        "--input", required=require_input,
        help="Path to the input JSON artifact (e.g. raw.json).",
    )
    if require_validation:
        parser.add_argument(
            "--validation", required=True,
            help="Path to validation.json produced by qc_validate.",
        )
    if require_output:
        parser.add_argument(
            "--output", required=True,
            help="Path to the output artifact this step will write.",
        )
    parser.add_argument(
        "--date", default=None, metavar="YYYY-MM-DD",
        help="Optional ISO date override (defaults are inferred per-step).",
    )
