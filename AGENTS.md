# DailyNews Agent Guide

> `CLAUDE.md` is the Claude Code entrypoint and imports `AGENTS.md`.
> `AGENTS.md` is the source of truth for the repo contract and maintainer guidance.
> `.claude/skills/dailynews-report/SKILL.md` is the only runtime procedure entry.

## Scope

This repository builds a daily RSS report in two stages:

1. Deterministic pipeline in code: fetch, validate, artifact generation, and zero-article / contract gating.
2. Claude Code post-processing in `skill + subagents`: Chinese summaries, event clustering, Top 30 selection, and content audit.

`AGENTS.md` is the source of truth for contract boundaries and maintainer-facing behavior in this workspace. The step-by-step runtime procedure lives in the orchestrator skill and the subagent files.

## Document Roles

- `README.md` is the public-facing entry point: project overview, quick start, and pointers into the rest of the docs.
- `CLAUDE.md` is the Claude Code entrypoint for this repo. It imports `AGENTS.md` and points task-style work to the project skill.
- `AGENTS.md` defines repo-level contract rules, allowed inputs/outputs, agent boundaries, and maintainer guidance.
- `TASKS.md` is the long-running tracker and planning panel for Claude Code architecture work in this repo. Update it before landing new execution-flow changes.
- `.claude/skills/dailynews-report/SKILL.md` is the project-local Claude Code orchestrator skill and the only runtime procedure entry.
- `.claude/agents/*.md` defines the specialized subagents used by the orchestrator skill.

## Entry Points

- Main pipeline: `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output [--retain-days 90] [--no-cleanup]`
- Fetch only: `python3 scripts/rss_news_monitor.py --json --max-summary 300 --hours 24`
- Validate only: `python3 scripts/qc_validate.py --input runs/<date>/raw.json --feeds feeds.json`
- Build LLM context: `python3 scripts/build_llm_context.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output runs/<date>/llm_context.json --report-path $REPO_ROOT/rss-report-<date>.md`
- Deterministic renderer: `python3 scripts/render_report.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output $REPO_ROOT/rss-report-<date>.md`
- Network diagnostic: `python3 scripts/network_debug.py --limit 5`
- Offline tests: `python3 -m unittest discover -s $REPO_ROOT/tests -p 'test_*.py'`
- End-to-end smoke (real network, ~10s): `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output --no-cleanup`
- Claude Code runtime entry: `/dailynews-report`

## Runtime Outputs

For each report date, the pipeline writes:

- `runs/YYYY-MM-DD/raw.json`
- `runs/YYYY-MM-DD/validation.json`
- `runs/YYYY-MM-DD/llm_context.json`
- `runs/YYYY-MM-DD/fetch.stderr.txt`
- `runs/YYYY-MM-DD/validate.stderr.txt`
- `runs/YYYY-MM-DD/llm_context.stderr.txt`
- `runs/YYYY-MM-DD/render.stderr.txt`

Final reports are written at the repo root:

- Success: `rss-report-YYYY-MM-DD.md`
- Failure: `rss-report-YYYY-MM-DD.failed.md`

## Contract Surface (LLM-Visible And Runtime-Readable Fields)

The Claude Code runtime depends on the following fields. Any change to their
names, types, or semantics is a breaking change and must be coordinated with
`tests/test_contracts_snapshot.py` and the orchestrator skill / subagent docs.

### `rss_daily_report.py --json-output` (8 fields)

```
{
  "report_date":         "YYYY-MM-DD",
  "run_dir":             "<absolute path to runs/YYYY-MM-DD/>",
  "raw_path":            "<absolute path to runs/YYYY-MM-DD/raw.json>",
  "validation_path":     "<absolute path to runs/YYYY-MM-DD/validation.json>",
  "llm_context_path":    "<absolute path to runs/YYYY-MM-DD/llm_context.json>",
  "report_path":         "<absolute path to rss-report-YYYY-MM-DD.md or .failed.md>",
  "validation_passed":   true | false,
  "validator_exit_code": 0 | 10 | 20 | 30 | 40
}
```

### `llm_context.json` top-level keys

```
meta              { date, generated_at_utc, run_id, report_path }
validation        { passed, blocking_reasons, warnings, counts, policy }
candidate_articles  [<article>, ...]   # sorted by heuristic_score desc, capped by --candidate-limit
all_articles        [<article>, ...]   # full list, original time-desc order
source_groups       [{ source, url, status, article_count, articles: [<article>] }]
```

### Per-article object (9 fields)

```
source            string   # feed display name
title             string   # English original
link              string   # full original URL
pub_date_utc      string   # human-readable "YYYY-MM-DD HH:MM UTC"
pub_date_iso      string   # ISO 8601 with +00:00
summary_en        string   # English summary, may be empty
heuristic_score   number   # see render_report.score_article
audit_flags       string[] # subset of: major_company, business_signal,
                           #   security_signal, breakthrough_signal, launch_signal,
                           #   speculation, noise, hard_noise, funding_or_deal_ge_100m
amount_millions   number   # 0.0 when no monetary amount detected
```

### `validation.json` fields the runtime reads

`passed` (bool), `blocking_reasons` (string[]), `warnings` (string[]),
`counts.articles` (int — Part 2 must equal this), `feed_results[].source` /
`status` / `article_count` (used to render the per-source groups including
`(0 篇)` placeholders), plus optional `feed_results[].error` text when
`status == 'error'` and the final report needs to surface the fetch failure.

## Contract Rules

- `raw.json` is produced only by `rss_news_monitor.py`.
- `validation.json` is produced only by `qc_validate.py`.
- `rss_daily_report.py --json-output` is the control-plane artifact for exit-code branching and output-path resolution.
- `llm_context.json` is the primary artifact for semantic ranking, clustering, Top 30 selection, and summarization.
- `validation.json` may be read only for workflow gating metadata and per-feed status/error details that are not duplicated in `llm_context.json`.
- `validation.passed == true` is required before any formal report can be produced.
- `validation.passed` may still be `true` when `counts.error > 0`, as long as there are articles to report and no other blocking contract or data-quality checks fail.
- `counts.error` is warning-only for publishability; `counts.articles == 0` remains a blocking condition that produces the failure report.
- `validation.passed == false` means agents must not overwrite the failure report with a formal report.
- `feed_results` count must equal the feed count in `feeds.json`.
- `status` values are limited to `ok`, `empty`, or `error`.
- `empty` is warning-only.
- `error` is warning-only for workflow gating and must be surfaced in the final report for the affected source.
- `unique_source_count` is observational only. It is not a blocking integrity rule.
- Titles must remain in English.
- Links must remain complete and unchanged.
- Articles must come only from the script output. No fabrication is allowed.

## Claude Code Runtime Architecture

- `.claude/skills/dailynews-report/SKILL.md` is the only runtime procedure entry. It orchestrates the branch flow but should not absorb every specialized task into one monolithic prompt.
- `pipeline-runner` runs `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output`, parses the 8 control-plane fields, and classifies the result as `success`, `expected-block`, or `unexpected-error`.
- `artifact-auditor` is read-only. It inspects `llm_context.json` and `validation.json` to verify `counts.articles`, source order, source-group consistency, and error-text readiness.
- `network-debugger` is unexpected-error only. It inspects `runs/<date>/` sidecar stderr first and may run `python3 scripts/network_debug.py --limit 5` only when the evidence points to a network or fetch problem.
- `part1-editor` is success-only and read-only. It performs Part 1 clustering, Top 30 selection, and event-summary planning from `candidate_articles`.
- `part2-drafter` is success-only and read-only. It expands `source_groups[]` plus `validation.feed_results[].error` into the full Part 2 source-group draft.
- `report-assembler` is success-only and is the only success-path writer. It assembles the final Chinese report from the Part 1 and Part 2 intermediate outputs and writes the success `report_path` without ever overwriting `*.failed.md`.
- `report-reviewer` is final and read-only. It checks English titles, unchanged links, Part 2 counts, source order, and error-group handling after the write.
- Fixed branch order:
  - success: `pipeline-runner -> artifact-auditor -> part1-editor + part2-drafter -> report-assembler -> report-reviewer`
  - expected-block: `pipeline-runner -> artifact-auditor`
  - unexpected-error: `pipeline-runner -> network-debugger`
- `part1-editor` and `part2-drafter` should stay read-only and must complete before `report-assembler`.
- `report-assembler` and `network-debugger` must never run in parallel.
- `report-reviewer` must always run after the final success-path write.
- Use `rss_daily_report.py --json-output` stdout to decide whether to continue, stop, or diagnose.
- Use `llm_context.json` for article-level semantics and editorial judgment.
- Read `validation.json` only for gating metadata and per-feed error details that are not duplicated in `llm_context.json`.
- Do not infer fetch-error details that are absent from the artifacts.

## Maintainer Notes

The remaining sections are maintainer-facing repository guidance. They are not
the runtime procedure for the scheduled LLM task.

## Shared Modules

`scripts/_common/` is the shared utility surface used by every pipeline
script. Prefer adding to it over duplicating logic. Each module is
import-safe and has dedicated unit tests.

- `_common/text.py` — `strip_html`, `parse_rss_date`, `dedup_link_key`
  (migrated verbatim from `rss_news_monitor.py`; behaviour parity is
  enforced by `tests/test_common_text.py` and the offline suite).
- `_common/pipeline.py` — `Step`, `StepResult`, `run_step` for
  consistent subprocess invocation, stdout/stderr persistence, and parent
  echo. Used by `rss_daily_report.py` to compose fetch → validate →
  llm_context → render.
- `_common/cli.py` — `add_io_args` standard argparse trio
  (`--input` / `--validation` / `--output` / `--date`).
- `_common/paths.py` — `runs_dir_for`, `report_path`, `stale_run_dirs`.
  Owns the `rss-report-YYYY-MM-DD.md` and `runs/YYYY-MM-DD/` templates so
  renames touch one file.
- `_common/schemas.py` — `TypedDict` shapes for `RawDocument`,
  `ValidationDocument`, `LlmContextDocument`, `PipelineOutput`, plus
  `STATUS_OK / STATUS_EMPTY / STATUS_ERROR` constants. Documentation-grade;
  the validator stays the source of truth for what is rejected.

## Division Of Responsibility

Code handles:

- RSS fetching
- Deduplication by link
- Feed-level status accounting
- Validation and exit codes
- Zero-article / contract gating
- Artifact paths and file writing

The LLM handles:

- Chinese summaries
- Event clustering across sources
- Top 30 editorial selection
- Content audit and de-noising

## Editorial Policy For Runtime Runs

The detailed runtime behavior lives in the orchestrator skill plus the
subagent files. The summary below is an editorial-policy excerpt, not the full
runtime procedure:

When `validation.passed` is true, the LLM should:

- Read `llm_context.json`
- Cluster duplicate or near-duplicate coverage of the same event
- Prioritize major industry events such as financing `>=100M`, acquisitions, and major regulation.
- Then prioritize major product launches from Apple, Google, NVIDIA, OpenAI, and similar companies.
- Then prioritize significant security or compliance events.
- Then prioritize important technical breakthroughs.
- Preserve source diversity when priorities tie
- Filter or sharply down-rank PR, promotions, giveaways, `how to watch`, pure rumor, and recap content
- Write Part 1 and Part 2 in the required Markdown format
- Ensure Part 2 covers every configured feed, including `(0篇)` groups
- Ensure the total article count in Part 2 matches `validation.counts.articles`

## Exit Codes

- `0`: publishable run
- `10`: damaged input or missing required fields
- `20`: contract mismatch
- `30`: data-quality block
- `40`: unexpected pipeline failure

## Feed Policy

- Feed-specific soft failures may be annotated in `feeds.json` with `"error_policy": "warn"`.
- `error_policy: "warn"` now affects warning classification and operator expectations, not publishability by itself.
- Marked `warn` feeds should appear under `warn-only error feed(s)` warnings; unmarked fetch errors should appear under the general failed-feed warnings. Neither kind of fetch error should by itself block a publishable run when the workflow still has reportable articles.
- Do not silently change a feed from `block` to `warn` without documenting the reason in the commit or change note.

## Tests

All tests pin to `tests/fixtures/feeds_fixture.json` rather than the real
`feeds.json` at the repo root. Users can add, remove, or reorder feeds in
their own `feeds.json` without breaking the suite. When a test asserts
anything about feed count or the rendered Markdown shape, it derives it
from the fixture — never hard-coded.

- `tests/test_qc_offline.py` — validator + renderer + dedup parity, fixture-driven.
- `tests/test_contracts_snapshot.py` — locks the LLM-visible surface
  (top-level keys, per-article fields, exit-code translation table,
  `--json-output` schema). If this fails after a refactor, you changed a
  contract; update both the golden fixture and the Claude Code runtime docs deliberately.
- `tests/test_common_text.py` — `_common.text` byte-level behaviour plus a
  `parse_feed` smoke that guards the fetch path against missing imports.
- `tests/test_pipeline_step.py` — `_common.pipeline` subprocess wrapper.
- `tests/test_network_debug.py` — offline coverage for the network diagnostic helper.
- `tests/test_runs_cleanup.py` — `_common.paths` + `--retain-days`
  retention policy.
- `tests/test_claude_skill_layout.py` — repo-level checks for the Claude Code entrypoint, orchestrator skill, tracker, and runtime-layout packaging.
- `tests/test_claude_agent_layout.py` — repo-level checks for `.claude/agents/`, the runtime agent files, and the documented `skill + subagents` architecture.

## Maintenance Notes

- Keep deterministic rules in code and semantic judgment in the Claude Code runtime layer.
- Do not move validation logic back into the orchestrator skill or subagents.
- Do not hand-edit `raw.json`, `validation.json`, or `llm_context.json`.
- If the runtime procedure changes, keep `TASKS.md`, `README.md`, `AGENTS.md`, `CLAUDE.md`, `.claude/skills/dailynews-report/SKILL.md`, and the relevant `.claude/agents/*.md` files aligned.
- If tests change, update the fixture set in `tests/fixtures/` — including
  `feeds_fixture.json` and the two golden artifacts
  (`markdown_render_golden.md`, `llm_context_golden.json`). Never make
  tests depend on the user's real `feeds.json`.
- Runtime outputs (`rss-report-*.md` and `runs/`) are gitignored. Fetched
  content lives in the user's local clone, not the repo.
- When refactoring `rss_news_monitor.py` or any fetch-path code, run a real
  end-to-end smoke (`python3 scripts/rss_daily_report.py --hours 24
  --json-output`) before declaring done. Unit tests bypass `parse_feed` and
  cannot catch missing imports on that path.
- Prefer extending `scripts/_common/*` over duplicating helpers; the
  contract-snapshot tests will catch behavioural drift in raw.json /
  validation.json / llm_context.json.
