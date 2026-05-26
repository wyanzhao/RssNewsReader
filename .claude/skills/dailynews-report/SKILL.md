---
name: dailynews-report
description: Run the DailyNews orchestrator skill for this repository. Use when the user explicitly invokes /dailynews-report, asks Codex or Claude Code to generate the DailyNews RSS report, inspect pipeline artifacts, or diagnose a blocked or unexpected run in the RssNewsReader/DailyNews workspace.
---

# DailyNews Report

This is the single shared skill file for both Claude Code and Codex. The
Claude Code path is `.claude/skills/dailynews-report/SKILL.md`; the Codex /
agent path `.agents/skills/dailynews-report/SKILL.md` is a symlink to this
same file.

Codex UI metadata lives at
`.claude/skills/dailynews-report/agents/openai.yaml`; the Codex / agent path
`.agents/skills/dailynews-report/agents` is a symlink to the same metadata
directory. Keep `policy.allow_implicit_invocation` set to `false` because this
workflow is write-producing and should be invoked explicitly.

This is a manual, write-producing orchestrator skill. Do not invoke it automatically.

Read these files before acting:

- [AGENTS.md](../../../AGENTS.md) for the repo contract, artifact schema, and guardrails.
- [TASKS.md](../../../TASKS.md) for the long-running tracker, current epics, and validation checklist.

## Architecture Contract

- `/dailynews-report` is the only runtime procedure entry in this repository.
- Keep the runtime split as `orchestrator skill + editorial subagents + deterministic helper scripts`; do not collapse it back into a single long instruction file.
- Do not bypass `pipeline-runner` by calling fetch / validate / llm_context / render subcommands directly.
- `part1-editor` writes `part1_shortlist.json`, `part1_shortlist_context.json`, and `part1_plan.json`; `part2-drafter` writes `part2_missing_summaries.json` and then uses `scripts/editorial_runtime.py merge-part2` to produce `part2_draft.json`.
- The orchestrator runs `scripts/editorial_runtime.py assemble` as the only final success-report writer.
- The orchestrator runs `scripts/editorial_runtime.py review` exactly once after the final success-path write.

## Workflow

1. Work from the repository root.
2. Invoke `pipeline-runner` to run `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output` and classify the result as `success`, `expected-block`, or `unexpected-error`.
3. If the classification is `success`, invoke `artifact-auditor` in read-only mode to verify `llm_context.json`, `validation.json`, `counts.articles`, source order, and error-text readiness before any write.
4. On a clean `success` audit, inspect `context_budget.json` and use its `recommended_strategy` to keep LLM reads scoped. Invoke `part1-editor` to read `part1_brief.json`, write `part1_shortlist.json`, build `part1_shortlist_context.json`, and then write `part1_plan.json`. Invoke `part2-drafter` to read `part2_context.json`, write only `part2_missing_summaries.json`, then run `python3 scripts/editorial_runtime.py merge-part2 --part2-context <run_dir>/part2_context.json --missing <run_dir>/part2_missing_summaries.json --output <run_dir>/part2_draft.json`. Treat final Part 1 and Part 2 handoffs as independent structured steps; both must finish before assembly.
5. If either success-path handoff artifact is missing, truncated, or schema-invalid, stop the success branch and return an `ERROR:` line instead of assembling.
6. After both Part 1 and Part 2 artifacts are ready, run `python3 scripts/editorial_runtime.py assemble --llm-context <run_dir>/llm_context.json --validation <run_dir>/validation.json --part1 <run_dir>/part1_plan.json --part2 <run_dir>/part2_draft.json --output <report_path>` to write the formal Chinese report into the success `report_path`, then run `python3 scripts/editorial_runtime.py review --llm-context <run_dir>/llm_context.json --validation <run_dir>/validation.json --part1 <run_dir>/part1_plan.json --part2 <run_dir>/part2_draft.json --report <report_path>` once.
7. If the classification is `expected-block`, invoke `artifact-auditor` in read-only mode, keep the existing failure report untouched, and return only the emitted absolute `report_path`.
8. If the classification is `unexpected-error`, invoke `network-debugger`. Do not invoke `part1-editor`, `part2-drafter`, assemble, or review in this branch.

## Guardrails

- Only `scripts/editorial_runtime.py assemble` may write the final success report.
- Use `part1_brief.json` for first-pass Part 1 ranking, `part1_shortlist_context.json` for final Part 1 summaries, and `part2_context.json` for Part 2 summaries. Use full `llm_context.json` only when a deterministic check or shortlisted article requires it.
- Use `context_budget.json` to decide whether to stay on normal context reads or force brief-first/cache-first behavior.
- Use `validation.json` only for gating metadata, `counts.articles`, source order cross-checks, and per-feed error text that is not duplicated in `llm_context.json`.
- Do not silently reconstruct Part 1 / Part 2 from `article_text`, `summary_en`, or partially copied chat text when a subagent handoff is incomplete.
- Treat oversize, truncated, or schema-invalid success-path handoffs as blocking errors.
- Never fabricate titles, links, counts, source groups, or error text.
- Never hand-edit `raw.json`, `validation.json`, or `llm_context.json`.
- Never overwrite a `*.failed.md` file with a formal report.
- Keep titles in English and links unchanged.

## Subagents

- `pipeline-runner` — runs the pipeline, parses the 8 control-plane fields, and returns the branch classification
- `artifact-auditor` — read-only audit of `llm_context.json` and `validation.json`
- `network-debugger` — unexpected-error diagnosis using sidecar stderr and `python3 scripts/network_debug.py --limit 5` only when warranted
- `part1-editor` — success-only Part 1 brief-first shortlist, event clustering, Top 30 selection, and summary planning
- `part2-drafter` — success-only Part 2 missing-summary drafting from compact `part2_context.json`

Deterministic helper steps, run directly by the orchestrator:

- `scripts/editorial_runtime.py merge-part2` — merges cached Part 2 summaries plus missing summaries into `part2_draft.json`
- `scripts/editorial_runtime.py assemble` — validates handoffs and writes the final success report
- `scripts/editorial_runtime.py review` — validates the final report after the write

## Response Contract

- On normal completion, including `success` and `expected-block`, return only the absolute `report_path`.
- On `unexpected-error`, or when `artifact-auditor` / `editorial_runtime.py review` reports a blocking issue, return at most two lines:
  1. `ERROR: <one-line diagnosis>`
  2. the absolute `report_path`, if it is known with confidence
