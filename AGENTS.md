# DailyNews Agent Guide

> `CLAUDE.md` is the Claude Code entrypoint and imports `AGENTS.md`.
> `AGENTS.md` is the source of truth for the repo contract and maintainer guidance.
> `.claude/skills/dailynews-report/SKILL.md` is the shared Claude Code / Codex skill file.
> `.agents/skills/dailynews-report/SKILL.md` is a symlink to the same file.

## Scope

This repository builds a daily RSS report in two stages:

1. Deterministic pipeline in code: fetch, validate, artifact generation, failure-report rendering, and zero-article / contract gating.
2. Claude Code post-processing in `skill + subagents`: Chinese summaries, event clustering, Top 30 selection, and content audit.

`AGENTS.md` is the source of truth for contract boundaries and maintainer-facing behavior in this workspace. The step-by-step runtime procedure lives in the orchestrator skill and the subagent files.

## Document Roles

- `README.md` is the public-facing entry point: project overview, quick start, and pointers into the rest of the docs.
- `CLAUDE.md` is the Claude Code entrypoint for this repo. It imports `AGENTS.md` and points task-style work to the shared project skill.
- `AGENTS.md` defines repo-level contract rules, allowed inputs/outputs, agent boundaries, and maintainer guidance.
- `pipeline_config.json` is the repo-level deterministic pipeline config for summary-enrichment and renderer truncation thresholds.
- `TASKS.md` is the long-running tracker and planning panel for Claude Code architecture work in this repo. Update it before landing new execution-flow changes.
- `.claude/skills/dailynews-report/SKILL.md` is the project-local orchestrator skill and the canonical runtime procedure file shared by Claude Code and Codex.
- `.agents/skills/dailynews-report/SKILL.md` is the Codex / agent skill path and must remain a symlink to `.claude/skills/dailynews-report/SKILL.md`.
- `.claude/skills/dailynews-report/agents/openai.yaml` is the Codex Skill UI metadata and must keep the workflow manual-only with `policy.allow_implicit_invocation: false`.
- `.agents/skills/dailynews-report/agents` is the Codex / agent metadata path and must remain a symlink to `.claude/skills/dailynews-report/agents`.
- `.claude/agents/*.md` defines the specialized subagents used by the orchestrator skill.

## Entry Points

- Main pipeline: `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output [--config pipeline_config.json] [--retain-days 90] [--no-cleanup]`
- Fetch only: `python3 scripts/rss_news_monitor.py --json --max-summary 300 --hours 24 [--config pipeline_config.json]`
- Validate only: `python3 scripts/qc_validate.py --input runs/<date>/raw.json --feeds feeds.json`
- Build LLM context: `python3 scripts/build_llm_context.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output runs/<date>/llm_context.json --report-path $REPO_ROOT/rss-report-<date>.md`
- Deterministic renderer: `python3 scripts/render_report.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output $REPO_ROOT/rss-report-<date>.md`
- Network diagnostic: `python3 scripts/network_debug.py --limit 5`
- Offline tests: `python3 -m unittest discover -s $REPO_ROOT/tests -p 'test_*.py'`
- End-to-end smoke (real network, ~10s): `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output --no-cleanup`
- Claude Code runtime entry: `/dailynews-report`
- Codex skill entry: `.agents/skills/dailynews-report/SKILL.md`
- Codex skill metadata: `.agents/skills/dailynews-report/agents/openai.yaml`

## Runtime Outputs

For each report date, the pipeline writes:

- `runs/YYYY-MM-DD/raw.json`
- `runs/YYYY-MM-DD/validation.json`
- `runs/YYYY-MM-DD/llm_context.json`
- `runs/YYYY-MM-DD/fetch.stderr.txt`
- `runs/YYYY-MM-DD/validate.stderr.txt`
- `runs/YYYY-MM-DD/llm_context.stderr.txt`
- `runs/YYYY-MM-DD/render.stderr.txt`

Final report paths are resolved at the repo root:

- Success target: `rss-report-YYYY-MM-DD.md`
- Failure: `rss-report-YYYY-MM-DD.failed.md`

On the default `/dailynews-report` success path, `rss_daily_report.py` only
emits the success `report_path`; it must not prewrite the formal success
report. The success file is written later by `report-assembler` from
`part1_plan.json` and `part2_draft.json`. The deterministic pipeline may write
`*.failed.md` directly for blocked or damaged-input runs.

### Claude Code Success-Path Handoff Artifacts

When `/dailynews-report` runs the Claude Code success branch, the runtime may
additionally write:

- `runs/YYYY-MM-DD/part1_plan.json`
- `runs/YYYY-MM-DD/part2_draft.json`

These are success-path handoff artifacts for the LLM runtime only. They are
not deterministic pipeline outputs.

`raw.json` may additionally carry a top-level `runtime_config` snapshot with the
effective summary-enrichment and render-threshold values used for that run.

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
all_articles        [<article>, ...]   # authoritative pool for part1-editor; full list, original time-desc order
source_groups       [{ source, url, status, article_count, articles: [<article>] }]
```

### Per-article object (7 fields)

```
source            string   # feed display name
title             string   # English original
link              string   # full original URL
pub_date_utc      string   # human-readable "YYYY-MM-DD HH:MM UTC"
pub_date_iso      string   # ISO 8601 with +00:00
summary_en        string   # English summary, may be empty
article_text      string   # extracted article main body (up to ~300 words), may be empty
```

The deterministic pipeline no longer emits scoring metadata
(`heuristic_score`, `audit_flags`, `amount_millions`) or a pre-filtered
`candidate_articles` list. Top 30 selection, clustering, de-noising, and
priority ordering are the sole responsibility of the `part1-editor` subagent.

`article_text` is a best-effort extraction of the source article's main body
via `_common/article_extract.py`. It is intended as the primary input for
LLM Chinese summarization (Part 1 and Part 2). It is empty when extraction
fails, when the page blocks scraping, when the article has no link, or when
the `article_text` enrichment pass is disabled in `pipeline_config.json`.
Editorial agents must fall back to `summary_en` when `article_text` is empty
and must never fabricate body text that is not in either field.

### `validation.json` fields the runtime reads

`passed` (bool), `blocking_reasons` (string[]), `warnings` (string[]),
`counts.articles` (int — Part 2 must equal this), `feed_results[].source` /
`status` / `article_count` (used to render the per-source groups including
`(0 篇)` placeholders), plus optional `feed_results[].error` text when
`status == 'error'` and the final report needs to surface the fetch failure.
When `rss_daily_report.py` has to synthesize a fallback `validation.json` for an
unexpected-error branch, that fallback artifact must keep the same top-level
shape and policy key names as the normal validator output.

## Contract Rules

- `raw.json` is produced only by `rss_news_monitor.py`.
- `validation.json` is produced only by `qc_validate.py`.
- `rss_daily_report.py --json-output` is the control-plane artifact for exit-code branching and output-path resolution.
- On publishable runs, `rss_daily_report.py --json-output` must not prewrite the success `report_path`; `report-assembler` owns that write.
- On blocked or damaged-input runs, `rss_daily_report.py --json-output` must still emit the 8 control-plane fields and write a concrete `*.failed.md` report whenever `report_path` is known.
- `llm_context.json` is the primary artifact for semantic ranking, clustering, Top 30 selection, and summarization.
- `validation.json` may be read only for workflow gating metadata and per-feed status/error details that are not duplicated in `llm_context.json`.
- `validation.passed == true` is required before any formal report can be produced.
- `validation.passed` may still be `true` when `counts.error > 0`, as long as there are articles to report and no other blocking contract or data-quality checks fail.
- `counts.error` is warning-only for publishability; `counts.articles == 0` remains a blocking condition that produces the failure report.
- `validation.passed == false` means agents must not overwrite the failure report with a formal report.
- `feed_results` count must equal the feed count in `feeds.json`.
- Each configured feed must appear exactly once in `feed_results[]`.
- Each `feed_results[].article_count` must match the actual count of `raw.json.articles` for that `source`.
- `status` values are limited to `ok`, `empty`, or `error`.
- `status == 'ok'` requires `article_count > 0` and no `error` text.
- `empty` is warning-only.
- `status == 'empty'` requires `article_count == 0` and no `error` text.
- `error` is warning-only for workflow gating and must be surfaced in the final report for the affected source.
- `status == 'error'` requires non-empty `error` text.
- `unique_source_count` is observational only. It is not a blocking integrity rule.
- `part1_plan.json` and `part2_draft.json` are success-path handoff artifacts only; they must be machine-readable and complete enough for `report-assembler` to consume without scraping long prose from chat output.
- If a success-path handoff artifact is missing, truncated, or schema-invalid, agents must stop the success branch and return a blocking issue. They must not silently fall back to raw `article_text` / `summary_en` or partial manual reconstruction.
- `article_text` and `summary_en` are source material only. They may inform editorial work, but the final formal report must use the success-path Chinese summaries from `part1_plan.json` / `part2_draft.json`.
- Titles must remain in English.
- Links must remain complete and unchanged.
- Articles must come only from the script output. No fabrication is allowed.

## Claude Code / Codex Runtime Architecture

- `.claude/skills/dailynews-report/SKILL.md` is the canonical runtime procedure entry. `.agents/skills/dailynews-report/SKILL.md` must remain a symlink to the same file so Claude Code and Codex reuse one skill body.
- `.claude/skills/dailynews-report/agents/openai.yaml` is the canonical Codex Skill metadata file. `.agents/skills/dailynews-report/agents` must remain a symlink to the same directory so Codex sees the same metadata at its skill path.
- The shared skill orchestrates the branch flow but should not absorb every specialized task into one monolithic prompt.
- `pipeline-runner` runs `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output`, parses the 8 control-plane fields, and classifies the result as `success`, `expected-block`, or `unexpected-error`.
- `artifact-auditor` is read-only. It inspects `llm_context.json` and `validation.json` to verify `counts.articles`, source order, source-group consistency, and error-text readiness.
- `network-debugger` is unexpected-error only. It inspects `runs/<date>/` sidecar stderr first and may run `python3 scripts/network_debug.py --limit 5` only when the evidence points to a network or fetch problem.
- `part1-editor` is success-only. It performs all Part 1 work — de-noising, clustering, Top 30 selection, priority ordering, and Chinese summarization — autonomously from `all_articles`, and writes `runs/<date>/part1_plan.json` as its structured handoff artifact. The deterministic pipeline contributes no scoring or filtering signals; editorial judgment lives entirely in the agent prompt at `.claude/agents/part1-editor.md`.
- `part2-drafter` is success-only. It expands `source_groups[]` plus `validation.feed_results[].error` into the full Part 2 source-group draft, then writes `runs/<date>/part2_draft.json` as its structured handoff artifact.
- `report-assembler` is success-only and is the only success-path writer of the final `report_path`. It assembles the final Chinese report from `part1_plan.json` and `part2_draft.json` without ever overwriting `*.failed.md`.
- `report-reviewer` is final and read-only. It checks English titles, unchanged links, Part 2 counts, source order, error-group handling, and that no raw `article_text` / `summary_en` leaks into the final report after the write.
- Fixed branch order:
  - success: `pipeline-runner -> artifact-auditor -> part1-editor + part2-drafter -> report-assembler -> report-reviewer`
  - expected-block: `pipeline-runner -> artifact-auditor`
  - unexpected-error: `pipeline-runner -> network-debugger`
- `part1-editor` and `part2-drafter` may write only their own handoff artifact (`part1_plan.json` / `part2_draft.json`) and must complete before `report-assembler`.
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
  llm_context, plus failure-report rendering when validation blocks.
- `_common/cli.py` — `add_io_args` standard argparse trio
  (`--input` / `--validation` / `--output` / `--date`).
- `_common/paths.py` — `runs_dir_for`, `report_path`, `stale_run_dirs`.
  Owns the `rss-report-YYYY-MM-DD.md` and `runs/YYYY-MM-DD/` templates so
  renames touch one file.
- `_common/editorial.py` — shared article normalization, source-group roster
  construction, heuristic scoring, audit-flag derivation, Top 30 selection,
  and defensive source-group consistency checks used by both
  `build_llm_context.py` and `render_report.py`.
- `_common/feed_config.py` — `feeds.json` CRUD and OPML import extracted from
  `rss_news_monitor.py`.
- `_common/feed_parse.py` — RSS/Atom XML parsing plus HTML meta-summary
  fallback extraction.
- `_common/feed_fetch.py` — network fetch, decode, summary backfill, and
  concurrent feed retrieval helpers.
- `_common/feed_output.py` — monitor-side dedup plus JSON / grouped text /
  summary output formatters.
- `_common/runtime_config.py` — repo-level `pipeline_config.json` loader plus
  raw-artifact config snapshot helpers for fetch and render settings.
- `_common/article_extract.py` — stdlib-only main-text extractor that
  prefers `<article>` / `<main>` / `role='main'` containers, drops
  script / style / nav / aside / footer / header / form regions, and
  truncates to ``article_text.max_words`` whitespace tokens. Populates the
  ``article_text`` field that editorial agents consume.
- `_common/schemas.py` — `TypedDict` shapes for `RawDocument`,
  `ValidationDocument`, `LlmContextDocument`, `PipelineOutput`, plus
  `STATUS_OK / STATUS_EMPTY / STATUS_ERROR` constants. Documentation-grade;
  the validator stays the source of truth for what is rejected.

## Division Of Responsibility

Code handles:

- RSS fetching
- Deduplication by link
- Feed-level status accounting
- Summary extraction and fallback backfill
- Article main-body extraction (``article_text``) from linked pages
- Validation and exit codes
- Zero-article / contract gating
- Artifact paths and file writing

The LLM handles:

- Chinese summaries
- Event clustering across sources
- Top 30 editorial selection
- Content audit and de-noising

## Summary Fallback Behavior

- `rss_news_monitor.py` keeps feed-level `summary_en` when it is usable.
- If `summary_en` is empty or too short, the fetch path also attempts an
  article-page fallback instead of treating feed summaries as all-or-nothing.
- Article-page fallback reads standard HTML meta summary fields such as
  `description`, `og:description`, and `twitter:description`.
- `pipeline_config.json.summary_enrichment.short_summary_threshold` controls
  what counts as “too short”.
- `pipeline_config.json.summary_enrichment.page_fallback_cap` controls the hard
  cap for page-fallback summaries even if `--max-summary` is set higher.
- The fetch step snapshots the effective values into `raw.json.runtime_config`
  so downstream render steps do not silently drift with later config edits.

## Article Body Extraction

- After summary enrichment, `rss_news_monitor.py` runs an `article_text`
  enrichment pass that fetches each article page and extracts its main
  body via `_common/article_extract.py`.
- Extraction prefers `<article>`, `<main>`, or `role='main'` containers,
  strips `<script>`, `<style>`, and obvious chrome (`<nav>`, `<aside>`,
  `<footer>`, `<header>`, `<form>`, etc.), and falls back to the union of
  all `<p>` / `<li>` / `<h*>` blocks when no container is present.
- Output is truncated to `pipeline_config.json.article_text.max_words`
  whitespace tokens (default 300). A trailing `"..."` marks truncation.
- `pipeline_config.json.article_text.enabled` toggles the pass globally.
  When disabled or when extraction fails, `article_text` is an empty
  string and editorial agents fall back to `summary_en`.
- `pipeline_config.json.article_text.max_workers` (default 4) caps fetch
  concurrency for this pass.
- The fetch step snapshots effective values into
  `raw.json.runtime_config.article_text` for later reference.
- `article_text` is best-effort. It is never used by the deterministic
  renderer; both the failure report and the success markdown continue to
  display `summary_en`. `article_text` is exclusively an LLM editorial
  input surfaced via `llm_context.json`.

## Render Summary Limits

- `pipeline_config.json.render.part1_summary_max_chars` controls final summary
  truncation in the Top 30 section.
- `pipeline_config.json.render.part2_summary_max_chars` controls final summary
  truncation in the per-source section.
- `render_report.py` must prefer `raw.json.runtime_config.render` when present.
  If an older `raw.json` lacks that snapshot, it may fall back to the explicit
  `--config` file or the repo default `pipeline_config.json`.

## Editorial Policy For Runtime Runs

The detailed runtime behavior lives in the orchestrator skill plus the
subagent files. The summary below is an editorial-policy excerpt, not the full
runtime procedure:

When `validation.passed` is true, the LLM should:

- Read `llm_context.json`
- Select Top 30 autonomously from `all_articles`; the deterministic pipeline emits no scoring, flags, or pre-filtered candidate list — filtering, clustering, and ordering are the agent's responsibility
- Prefer `article_text` as the source of truth for Chinese summarization; when `article_text` is empty, fall back to `summary_en`. Never fabricate body text that is not in either field.
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

All tests pin to `tests/fixtures/feeds_fixture.json` and
`tests/fixtures/pipeline_config_fixture.json` rather than the real repo-root
config files. Users can add, remove, or reorder feeds in `feeds.json`, or tune
their own `pipeline_config.json`, without breaking the suite. When a test
asserts anything about feed count, render thresholds, or the rendered Markdown
shape, it derives it from fixtures — never hard-coded against the user's local
config files.

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
- `tests/test_claude_skill_layout.py` — repo-level checks for the Claude Code entrypoint, shared Claude/Codex skill file, tracker, and runtime-layout packaging.
- `tests/test_claude_agent_layout.py` — repo-level checks for `.claude/agents/`, the runtime agent files, and the documented `skill + subagents` architecture.

## Maintenance Notes

- Keep deterministic rules in code and semantic judgment in the Claude Code runtime layer.
- Do not move validation logic back into the orchestrator skill or subagents.
- Do not hand-edit `raw.json`, `validation.json`, or `llm_context.json`.
- If the runtime procedure changes, keep `TASKS.md`, `README.md`, `AGENTS.md`, `CLAUDE.md`, `.claude/skills/dailynews-report/SKILL.md`, `.claude/skills/dailynews-report/agents/openai.yaml`, the `.agents/skills/dailynews-report/SKILL.md` / `.agents/skills/dailynews-report/agents` symlinks, and the relevant `.claude/agents/*.md` files aligned.
- If tests change, update the fixture set in `tests/fixtures/` — including
  `feeds_fixture.json`, `pipeline_config_fixture.json`, and the two golden artifacts
  (`markdown_render_golden.md`, `llm_context_golden.json`). Never make
  tests depend on the user's real `feeds.json` or `pipeline_config.json`.
- Runtime outputs (`rss-report-*.md` and `runs/`) are gitignored. Fetched
  content lives in the user's local clone, not the repo.
- When refactoring `rss_news_monitor.py` or any fetch-path code, run a real
  end-to-end smoke (`python3 scripts/rss_daily_report.py --hours 24
  --json-output`) before declaring done. Unit tests bypass `parse_feed` and
  cannot catch missing imports on that path.
- Prefer extending `scripts/_common/*` over duplicating helpers; the
  contract-snapshot tests will catch behavioural drift in raw.json /
  validation.json / llm_context.json.
- Do not re-couple `build_llm_context.py` to private helpers inside
  `render_report.py`; shared editorial-domain behavior belongs in
  `scripts/_common/editorial.py`.
