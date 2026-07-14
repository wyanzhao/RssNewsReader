---
name: dailynews-report
description: Generate and validate the DailyNews RSS report for this repository. Use when the user explicitly invokes $dailynews-report in Codex or /dailynews-report in Claude Code to run the report workflow, inspect pipeline artifacts, or diagnose a blocked or unexpected run in the RssNewsReader/DailyNews workspace.
---

# DailyNews Report

This is the single shared skill file for both Claude Code and Codex. The
Claude Code path is `.claude/skills/dailynews-report/SKILL.md`; the Codex /
agent path `.agents/skills/dailynews-report/SKILL.md` is a symlink to this
same file.

Invoke this shared workflow explicitly as `$dailynews-report` in Codex or
`/dailynews-report` in Claude Code.

Codex UI metadata lives at
`.claude/skills/dailynews-report/agents/openai.yaml`; the Codex / agent path
`.agents/skills/dailynews-report/agents` is a symlink to the same metadata
directory. Keep `policy.allow_implicit_invocation` set to `false` because this
workflow is write-producing and should be invoked explicitly.

This is a manual, write-producing orchestrator skill. Do not invoke it automatically.

This file is the self-contained runtime procedure. Do not read maintainer
docs as a runtime step: consult [AGENTS.md](../../../AGENTS.md) only when a
contract question arises that this file does not answer, and never read the
maintainer tracker at runtime — both are large and irrelevant to a normal
daily run.

## Architecture Contract

- `$dailynews-report` in Codex and `/dailynews-report` in Claude Code are the
  product-specific invocations of this repository's single runtime procedure.
- Keep the runtime split as `orchestrator skill + editorial subagents + deterministic helper scripts`; do not collapse it back into a single long instruction file.
- Pipeline execution, branch classification, and the artifact audit are deterministic orchestrator steps, not subagents. Run the pipeline only through `python3 scripts/rss_daily_report.py --json-output`; never call the fetch / validate / llm_context / render subcommands directly.
- `part1-editor` writes `part1_shortlist.json`, `part1_shortlist_context.json`, and `part1_plan.json`; `part2-drafter` writes only `part2_missing_summaries.json`. The orchestrator runs `scripts/editorial_runtime.py merge-part2` to produce `part2_draft.json`.
- The orchestrator runs `scripts/editorial_runtime.py assemble` as the only final success-report writer.
- The orchestrator runs `scripts/editorial_runtime.py review` exactly once after the final success-path write.
- The chat-facing Top 30 digest is rendered only by `scripts/editorial_runtime.py top30`; the orchestrator relays its stdout verbatim and never composes its own summary of the news.

## Workflow

1. Work from the repository root.
2. Run the deterministic pipeline directly: `python3 scripts/rss_daily_report.py --json-output`. The time window and summary cap default from `pipeline_config.json` (`fetch.hours` / `fetch.max_summary`); pass explicit `--hours` / `--max-summary` only when the user asks for a different window. Parse stdout JSON and trust only the 8 control-plane fields: `report_date`, `run_dir`, `raw_path`, `validation_path`, `llm_context_path`, `report_path`, `validation_passed`, `validator_exit_code`.
3. Classify the run yourself — no subagent is involved:
   - `success`: `validator_exit_code == 0` and `validation_passed == true`
   - `expected-block`: `validator_exit_code` in `10 / 20 / 30` and `validation_passed == false`
   - `unexpected-error`: any other combination, stdout that is not valid JSON, or any missing control-plane field
4. If the classification is `success`, run the deterministic audit directly: `python3 scripts/editorial_runtime.py audit --llm-context <llm_context_path> --validation <validation_path>`. Exit 0 continues; any other exit stops the success branch with an `ERROR:` line. Also inspect `<run_dir>/context_budget.json`: when `within_budget` is false, surface the violations and keep every LLM read strictly scoped to the shortlist and missing-summary sets.
5. On a clean audit, invoke `part1-editor` and `part2-drafter` **in parallel — a single message containing both subagent invocations**; their inputs and outputs are independent. `part1-editor` reads `part1_brief.json`, writes `part1_shortlist.json`, builds `part1_shortlist_context.json` via `scripts/editorial_runtime.py shortlist-context`, and writes `part1_plan.json`. `part2-drafter` reads `part2_context.json` and writes only `part2_missing_summaries.json`.
6. After both subagents return, run `python3 scripts/editorial_runtime.py merge-part2 --part2-context <run_dir>/part2_context.json --missing <run_dir>/part2_missing_summaries.json --output <run_dir>/part2_draft.json`.
7. If any success-path handoff artifact is missing, truncated, or schema-invalid, stop the success branch and return an `ERROR:` line instead of assembling.
8. After both Part 1 and Part 2 artifacts are ready, run `python3 scripts/editorial_runtime.py assemble --llm-context <run_dir>/llm_context.json --validation <run_dir>/validation.json --part1 <run_dir>/part1_plan.json --part2 <run_dir>/part2_draft.json --output <report_path>` to write the formal Chinese report into the success `report_path`, then run `python3 scripts/editorial_runtime.py review --llm-context <run_dir>/llm_context.json --validation <run_dir>/validation.json --part1 <run_dir>/part1_plan.json --part2 <run_dir>/part2_draft.json --report <report_path>` once.
9. After review passes, run `python3 scripts/editorial_runtime.py top30 --llm-context <run_dir>/llm_context.json --part1 <run_dir>/part1_plan.json --report-path <report_path> --output <run_dir>/top30.md` and capture its stdout — this fixed-format digest is the final success reply.
10. If the classification is `expected-block`, keep the existing failure report untouched and return only the emitted absolute `report_path`.
11. If the classification is `unexpected-error`, re-run the pipeline command once — transient network flakes are common and a missed window is unrecoverable because RSS is a rolling window. If the retry classifies as `success` or `expected-block`, continue on that branch as normal. Only when the retry is also `unexpected-error`, invoke `network-debugger`. Do not invoke `part1-editor`, `part2-drafter`, assemble, review, or top30 while unresolved.

## Guardrails

- Only `scripts/editorial_runtime.py assemble` may write the final success report.
- Use `part1_brief.json` for first-pass Part 1 ranking, `part1_shortlist_context.json` for final Part 1 summaries, and `part2_context.json` for Part 2 summaries. Use full `llm_context.json` only when a deterministic check or shortlisted article requires it.
- Never read `runs/_cache/` files into LLM context — cache reuse is injected deterministically (`shortlist-context` adds `cached_summary_zh`; `part2_context.json` marks `needs_summary == false`).
- Use `validation.json` only for gating metadata, `counts.articles`, source order cross-checks, and per-feed error text that is not duplicated in `llm_context.json`.
- Do not silently reconstruct Part 1 / Part 2 from `article_text`, `summary_en`, or partially copied chat text when a subagent handoff is incomplete.
- Treat oversize, truncated, or schema-invalid success-path handoffs as blocking errors.
- Never fabricate titles, links, counts, source groups, or error text.
- Never hand-edit `raw.json`, `validation.json`, or `llm_context.json`.
- Never overwrite a `*.failed.md` file with a formal report.
- Keep titles in English and links unchanged.

## Subagents

- `part1-editor` — success-only Part 1 brief-first shortlist, event clustering, Top 30 selection, and link-keyed summary planning
- `part2-drafter` — success-only Part 2 missing-summary drafting from compact `part2_context.json`
- `network-debugger` — unexpected-error diagnosis using sidecar stderr and `python3 scripts/network_debug.py --limit 5` only when warranted

Deterministic steps, run directly by the orchestrator:

- `python3 scripts/rss_daily_report.py --json-output` — pipeline run; classification follows the three rules above
- `scripts/editorial_runtime.py audit` — read-only artifact audit before any editorial work
- `scripts/editorial_runtime.py merge-part2` — merges cached Part 2 summaries plus missing summaries into `part2_draft.json`
- `scripts/editorial_runtime.py assemble` — validates handoffs and writes the final success report
- `scripts/editorial_runtime.py review` — validates the final report after the write
- `scripts/editorial_runtime.py top30` — renders the fixed-format Top 30 digest (stdout + `runs/<date>/top30.md`) that the orchestrator returns verbatim

(`scripts/editorial_runtime.py shortlist-context` is run inside `part1-editor` between its two passes.)

## Response Contract

- On `success`, respond with the **verbatim stdout of the `top30` step** — it is the fixed-format Top 30 digest and already ends with the full `report_path` line. Do not rephrase, reorder, re-rank, summarize, translate, truncate, or append any commentary before or after it.
- On `expected-block`, return only the absolute `report_path`.
- On `unexpected-error`, or when the audit / `editorial_runtime.py review` reports a blocking issue, return at most two lines:
  1. `ERROR: <one-line diagnosis>`
  2. the absolute `report_path`, if it is known with confidence
