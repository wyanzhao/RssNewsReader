---
name: dailynews-report
description: Run the DailyNews RSS reporting workflow for this repository. Use when you explicitly invoke /dailynews-report to generate the report, inspect pipeline artifacts, or diagnose a blocked run.
disable-model-invocation: true
---

# DailyNews Report

This is a manual, write-producing project skill. Do not invoke it automatically.

Read these files before acting:

- [AGENTS.md](../../../AGENTS.md) for the repo contract, artifact schema, and guardrails.
- [PROMPT.md](../../../PROMPT.md) for the exact runtime procedure, branching rules, report format, and reply protocol.

## Workflow

1. Work from the repository root.
2. Run `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output`.
3. Parse stdout only if it is valid JSON and use the emitted `report_path`, `llm_context_path`, `validation_path`, `validation_passed`, and `validator_exit_code` fields.
4. If `validation_passed == true` and `validator_exit_code == 0`, follow `PROMPT.md` to render the formal Chinese report into the success `report_path`.
5. If `validator_exit_code` is `10`, `20`, or `30` with `validation_passed == false`, treat it as an expected block and do not overwrite the failure report.
6. For any other combination, inspect the `runs/<date>/` stderr sidecars and diagnose before writing anything. Only treat it as a network problem when the stderr evidence says so.

## Guardrails

- Use `llm_context.json` for article-level semantics, ranking, clustering, and summaries.
- Use `validation.json` only for gating metadata and per-feed error text that is not duplicated in `llm_context.json`.
- Never fabricate titles, links, counts, or source groups.
- Never hand-edit `raw.json`, `validation.json`, or `llm_context.json`.
- Never overwrite a `*.failed.md` file with a formal report.
- Keep titles in English and links unchanged.

## Output

Follow the response contract in `PROMPT.md`. On normal completion, return only the absolute `report_path`.
