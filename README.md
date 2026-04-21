# RssNewsReader

A daily RSS news pipeline that produces a curated Chinese-language news report.

The pipeline has two stages, by design:

1. **Deterministic stage (code)** — fetch, dedup, validate, and render. Writes structured artifacts (`raw.json`, `validation.json`, `llm_context.json`) under `runs/<YYYY-MM-DD>/` and a baseline Markdown report at the repo root.
2. **Editorial stage (Claude Code runtime)** — consumes `llm_context.json`, clusters duplicate events across sources, picks a Top 30, writes Chinese summaries, and rewrites the final Markdown report through a project-local orchestrator skill plus subagents.

The Python pipeline and the Claude Code runtime layout both live in this repo.
The supported Claude Code architecture in this repo is `skill + subagents`.

## Claude Code

If you use Claude Code in this repo, [`CLAUDE.md`](CLAUDE.md) is the project entrypoint and imports [`AGENTS.md`](AGENTS.md).

The repo ships a project-local orchestrator skill at [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md), available in Claude Code as `/dailynews-report`.

That skill delegates to seven project-level subagents under [`.claude/agents/`](.claude/agents/):

- `pipeline-runner`
- `artifact-auditor`
- `network-debugger`
- `part1-editor`
- `part2-drafter`
- `report-assembler`
- `report-reviewer`

This runtime is intentionally manual-only because it can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.

Long-running architecture work is tracked in [`TASKS.md`](TASKS.md).

## Quick start

```bash
# Fetch -> validate -> build LLM context -> render a deterministic baseline report
python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output
```

On success the pipeline prints a JSON payload with 8 fields — `report_date`, `run_dir`, `raw_path`, `validation_path`, `llm_context_path`, `report_path`, `validation_passed`, `validator_exit_code`. See [`AGENTS.md`](AGENTS.md#contract-surface-llm-visible-and-runtime-readable-fields) for the full contract.

Reports land at:

- Success: `rss-report-YYYY-MM-DD.md`
- Failure: `rss-report-YYYY-MM-DD.failed.md`

Both paths are gitignored by default — the runtime output is yours, not the repo's.

## Customize feeds

Edit [`feeds.json`](feeds.json). Each entry is `{ "name": "...", "url": "..." }`. An optional `"error_policy": "warn"` downgrades that feed's fetch failures to warnings instead of blocking errors.

The test suite pins to a fixture feeds list (`tests/fixtures/feeds_fixture.json`), so editing `feeds.json` never breaks the tests.

## Run tests

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
```

## Exit codes

| Code | Meaning |
|---|---|
| 0  | Publishable run |
| 10 | Damaged input or missing required fields |
| 20 | Contract mismatch |
| 30 | Data-quality block (e.g. zero articles) |
| 40 | Unexpected pipeline failure |

## Project layout

```
scripts/
  rss_daily_report.py      # orchestrator: fetch -> validate -> context -> render
  rss_news_monitor.py      # RSS fetch + dedup
  qc_validate.py           # contract + data-quality validator
  build_llm_context.py     # shapes llm_context.json
  render_report.py         # deterministic Markdown renderer
  _common/                 # shared helpers (text, pipeline, paths, schemas)
tests/
  fixtures/                # golden fixtures + fixture feeds.json
  test_*.py                # offline unit + contract snapshot suites
feeds.json                 # user-editable RSS source list
AGENTS.md                  # pipeline contract and maintainer guide
TASKS.md                   # long-running Claude Code tracker and validation board
CLAUDE.md                  # Claude Code entrypoint; imports AGENTS.md and points to /dailynews-report
.claude/skills/dailynews-report/
  SKILL.md                 # project-local Claude Code orchestrator skill (/dailynews-report)
.claude/agents/
  *.md                     # project-level subagents used by the orchestrator skill
```

## Further reading

- [`AGENTS.md`](AGENTS.md) — pipeline contract, artifact schemas, agent boundaries, shared utilities
- [`TASKS.md`](TASKS.md) — long-running Claude Code tracker and validation checklist
- [`CLAUDE.md`](CLAUDE.md) — Claude Code entrypoint for this repo
- [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md) — manual DailyNews orchestrator workflow for Claude Code
- [`.claude/agents/`](.claude/agents/) — subagents for pipeline running, artifact auditing, Part 1 editing, Part 2 drafting, final report assembly, debugging, and final review
