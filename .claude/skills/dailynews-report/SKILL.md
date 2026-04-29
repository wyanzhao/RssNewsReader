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
- Keep the runtime split as `orchestrator skill + subagents`; do not collapse it back into a single long instruction file.
- Do not bypass `pipeline-runner` by calling fetch / validate / llm_context / render subcommands directly.
- `part1-editor` and `part2-drafter` may write only their own structured handoff artifacts, `part1_plan.json` and `part2_draft.json`, under the emitted `run_dir`. `report-assembler` is the only final report writer.
- `report-assembler` and `network-debugger` must never run in parallel.
- `report-reviewer` must run exactly once after the final success-path write.

## Workflow

1. Work from the repository root.
2. Invoke `pipeline-runner` to run `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output` and classify the result as `success`, `expected-block`, or `unexpected-error`.
3. If the classification is `success`, invoke `artifact-auditor` in read-only mode to verify `llm_context.json`, `validation.json`, `counts.articles`, source order, and error-text readiness before any write.
4. On a clean `success` audit, invoke `part1-editor` to write `runs/<date>/part1_plan.json` and `part2-drafter` to write `runs/<date>/part2_draft.json`. Treat these as independent structured handoff steps; both must finish before assembly.
5. If either success-path handoff artifact is missing, truncated, or schema-invalid, stop the success branch and return an `ERROR:` line instead of assembling.
6. After both Part 1 and Part 2 artifacts are ready, invoke `report-assembler` to read them and write the formal Chinese report into the success `report_path`, then invoke `report-reviewer` once to perform a final read-only check.
7. If the classification is `expected-block`, invoke `artifact-auditor` in read-only mode, keep the existing failure report untouched, and return only the emitted absolute `report_path`.
8. If the classification is `unexpected-error`, invoke `network-debugger`. Do not invoke `part1-editor`, `part2-drafter`, `report-assembler`, or `report-reviewer` in this branch.

## Guardrails

- Only `report-assembler` may write the final success report.
- Use `llm_context.json` for article-level semantics, ranking, clustering, and summaries.
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
- `part1-editor` — success-only Part 1 event clustering, Top 30 selection, and summary planning
- `part2-drafter` — success-only Part 2 source-group drafting and count-safe article coverage
- `report-assembler` — success-only final markdown assembly and write
- `report-reviewer` — final read-only review after the success-path write

## Response Contract

- On normal completion, including `success` and `expected-block`, return only the absolute `report_path`.
- On `unexpected-error`, or when `artifact-auditor` / `report-reviewer` reports a blocking issue, return at most two lines:
  1. `ERROR: <one-line diagnosis>`
  2. the absolute `report_path`, if it is known with confidence
