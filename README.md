# DailyNews

DailyNews is a two-stage RSS workflow that turns a curated feed list into a
daily Chinese-language Markdown report.

You can use it in two ways:

1. **Deterministic Python pipeline**: fetch RSS feeds, deduplicate links,
   validate the data, build `llm_context.json`, and render a baseline report.
2. **Optional Claude Code editorial pass**: read `llm_context.json`, cluster
   duplicate events, select a Top 30, write Chinese summaries, and rewrite the
   final report through a project-local `skill + subagents` runtime.

If you only want to clone the repo and run it, you only need the deterministic
Python pipeline. Claude Code is optional.

## What You Need

- `python3` on your `PATH`
- outbound network access to the RSS feeds in [`feeds.json`](feeds.json)
- write access to the repository working tree

This repo currently uses the Python standard library only. There is no
`requirements.txt` or package-install step.

## Quick Start

From the repository root:

```bash
python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output
```

This single command runs:

1. fetch
2. validate
3. build `llm_context.json`
4. render a deterministic Markdown report

During fetch, the monitor keeps the feed-provided summary when it is usable.
If `summary_en` is empty or too short, it also tries an article-page fallback
from standard HTML meta summary fields. In the standard DailyNews workflow,
page-fallback summaries are capped at 300 characters.
Render-time summary truncation is configured separately and defaults to 200
characters for both the Top 30 section and the per-source section.

If the run succeeds, stdout prints a JSON object with 8 control-plane fields:

- `report_date`
- `run_dir`
- `raw_path`
- `validation_path`
- `llm_context_path`
- `report_path`
- `validation_passed`
- `validator_exit_code`

See [`AGENTS.md`](AGENTS.md#contract-surface-llm-visible-and-runtime-readable-fields)
for the exact contract surface.

## What Gets Written

For each report date, the pipeline writes runtime artifacts under
`runs/YYYY-MM-DD/`:

- `raw.json`
- `validation.json`
- `llm_context.json`
- `fetch.stderr.txt`
- `validate.stderr.txt`
- `llm_context.stderr.txt`
- `render.stderr.txt`

It also writes a report at the repository root:

- success: `rss-report-YYYY-MM-DD.md`
- blocked/failure path: `rss-report-YYYY-MM-DD.failed.md`

If `/dailynews-report` runs the Claude Code success path, the runtime may also
write intermediate handoff artifacts under the same `runs/YYYY-MM-DD/`
directory:

- `part1_plan.json`
- `part2_draft.json`

These files belong to the Claude Code success path only. They are not emitted
by the deterministic Python pipeline.

These runtime outputs are local working files and are gitignored by default.
They are not meant to be committed with the codebase.

## Typical First Run

If you want the shortest path from clone to result:

1. Review or edit [`feeds.json`](feeds.json) and [`pipeline_config.json`](pipeline_config.json).
2. Run `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output`.
3. Open the emitted `report_path`.
4. If the run is blocked, inspect `validation_path` and the sidecar `*.stderr.txt`
   files under the reported `run_dir`.

## Customize Feeds

Edit [`feeds.json`](feeds.json). Each feed entry looks like:

```json
{
  "name": "Example Feed",
  "url": "https://example.com/rss.xml"
}
```

Optional field:

- `"error_policy": "warn"`: treat that feed's fetch failure as a warning for
  operator visibility instead of a publishability block by itself

The test suite is pinned to [`tests/fixtures/feeds_fixture.json`](tests/fixtures/feeds_fixture.json),
so changing your local `feeds.json` does not break the tests.

## Tune Summary Settings

Edit [`pipeline_config.json`](pipeline_config.json) to control the summary-related
thresholds used by the deterministic pipeline:

```json
{
  "summary_enrichment": {
    "short_summary_threshold": 80,
    "page_fallback_cap": 300
  },
  "render": {
    "part1_summary_max_chars": 200,
    "part2_summary_max_chars": 200
  }
}
```

- `summary_enrichment.short_summary_threshold`: below this length, feed summaries
  are treated as too short and trigger page fallback.
- `summary_enrichment.page_fallback_cap`: hard cap for article-page fallback summaries.
- `render.part1_summary_max_chars`: final report truncation limit for the Top 30 section.
- `render.part2_summary_max_chars`: final report truncation limit for the per-source section.

Each run snapshots the active summary config into `raw.json.runtime_config`, so
later render steps can stay consistent with the fetch-time settings.

## Run Tests

Run the full offline suite:

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
```

This validates the deterministic pipeline, contract snapshots, cleanup logic,
network-debug helper behavior, and the Claude Code repo layout.

## Exit Codes

| Code | Meaning |
|---|---|
| 0  | Publishable run |
| 10 | Damaged input or missing required fields |
| 20 | Contract mismatch |
| 30 | Data-quality block such as zero articles |
| 40 | Unexpected pipeline failure |

## Optional Claude Code Workflow

If you use Claude Code in this repo:

- [`CLAUDE.md`](CLAUDE.md) is the project entrypoint and imports [`AGENTS.md`](AGENTS.md)
- the project-local orchestrator skill lives at [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md)
- the skill is exposed as `/dailynews-report`
- the supported runtime architecture is `skill + subagents`
- on the success path, subagents exchange machine-readable handoff artifacts
  (`part1_plan.json` / `part2_draft.json`) under `runs/YYYY-MM-DD/`
- if a success-path handoff artifact is missing or invalid, the runtime should
  stop instead of silently falling back to raw `summary_en` or partial
  reconstruction

The skill delegates to seven project-level subagents under
[`.claude/agents/`](.claude/agents/):

- `pipeline-runner`
- `artifact-auditor`
- `network-debugger`
- `part1-editor`
- `part2-drafter`
- `report-assembler`
- `report-reviewer`

This Claude Code workflow is intentionally manual-only because it is
write-producing and can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.

## Repository Layout

```text
scripts/
  rss_daily_report.py      orchestrator: fetch -> validate -> context -> render
  rss_news_monitor.py      RSS fetch + dedup
  qc_validate.py           contract + data-quality validator
  build_llm_context.py     shapes llm_context.json
  render_report.py         deterministic Markdown renderer
  network_debug.py         network/fetch diagnostics
  _common/                 shared helpers (text, pipeline, paths, schemas)
tests/
  fixtures/                golden fixtures + fixture feeds.json + fixture pipeline_config.json
  test_*.py                offline unit + contract snapshot suites
feeds.json                 user-editable RSS source list
pipeline_config.json       user-editable summary fallback / render settings
AGENTS.md                  pipeline contract and maintainer guide
CLAUDE.md                  Claude Code entrypoint; imports AGENTS.md
TASKS.md                   maintainer tracker for architecture work
.claude/skills/dailynews-report/
  SKILL.md                 project-local Claude Code orchestrator skill
.claude/agents/
  *.md                     project-level subagents used by the orchestrator
```

## Maintainer Docs

- [`AGENTS.md`](AGENTS.md): repository contract, artifact schemas, runtime boundaries,
  and shared-module guidance
- [`CLAUDE.md`](CLAUDE.md): Claude Code entrypoint for this repo
- [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md):
  manual Claude Code orchestrator workflow
- [`.claude/agents/`](.claude/agents/): subagents for pipeline running, artifact
  auditing, Part 1 editing, Part 2 drafting, final report assembly, debugging,
  and final review
- [`TASKS.md`](TASKS.md): long-running tracker for repository architecture work

## License

This project is licensed under the MIT License. See [`LICENSE`](LICENSE).
