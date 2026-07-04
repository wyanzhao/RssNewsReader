# DailyNews

DailyNews is a two-stage RSS workflow that turns a curated feed list into a
daily Chinese-language Markdown report.

You can use it in two ways:

1. **Deterministic Python pipeline**: fetch RSS feeds, deduplicate links,
   validate the data, build `llm_context.json`, and render failure reports for
   blocked runs.
2. **Optional Claude Code editorial pass**: read compact editorial contexts,
   cluster duplicate events, select a Top 30, write Chinese summaries, and
   assemble the final report through a project-local `skill + subagents`
   runtime.

The default Python command is the control-plane and artifact builder. The
formal success report is written by the Claude Code success path so it can use
the structured editorial handoff artifacts.

## What You Need

- `python3` on your `PATH`
- outbound network access to the RSS feeds in [`feeds.json`](feeds.json)
- write access to the repository working tree

This repo currently uses the Python standard library only. There is no
`requirements.txt` or package-install step.

## Quick Start

From the repository root:

```bash
python3 scripts/rss_daily_report.py --json-output
```

The look-back window and summary cap default from `pipeline_config.json`
(`fetch.hours: 24`, `fetch.max_summary: 300`); pass explicit `--hours` /
`--max-summary` flags to override them for a single run.

This single command runs:

1. fetch
2. validate
3. build `llm_context.json` plus compact `part1_brief.json` and `part2_context.json`
4. resolve the final report path; write `*.failed.md` only when validation blocks

During fetch, the monitor keeps the feed-provided summary when it is usable.
If `summary_en` is empty or too short, it also tries an article-page fallback
from standard HTML meta summary fields. In the standard DailyNews workflow,
page-fallback summaries are capped at 300 characters.
Render-time summary truncation is configured separately and defaults to 200
characters for both the Top 30 section and the per-source section.
Article body snippets are capped separately by `article_text.max_words`, which
defaults to 150 words to keep LLM context size bounded.

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
- `part1_brief.json`
- `part2_context.json`
- `context_budget.json`
- `fetch.stderr.txt`
- `validate.stderr.txt`
- `llm_context.stderr.txt`
- `render.stderr.txt`

It also resolves report paths at the repository root:

- success target: `rss-report-YYYY-MM-DD.md`, written by
  `scripts/editorial_runtime.py assemble` during `/dailynews-report`
- blocked/failure path: `rss-report-YYYY-MM-DD.failed.md`, written by the
  deterministic pipeline

If `/dailynews-report` runs the Claude Code success path, the runtime may also
write intermediate handoff artifacts under the same `runs/YYYY-MM-DD/`
directory:

- `part1_plan.json`
- `part1_shortlist.json`
- `part1_shortlist_context.json`
- `part2_missing_summaries.json`
- `part2_draft.json`
- `top30.md` — persisted copy of the fixed-format Top 30 digest that the
  workflow prints as its final chat reply

`part1_brief.json`, `part2_context.json`, and `context_budget.json` are emitted
by the deterministic pipeline. `part1_shortlist.json`,
`part1_shortlist_context.json`, `part1_plan.json`,
`part2_missing_summaries.json`, `part2_draft.json`, and `top30.md` belong to
the Claude Code success path only.

These runtime outputs are local working files and are gitignored by default.
They are not meant to be committed with the codebase.

## Typical First Run

If you want the shortest path from clone to result:

1. Review or edit [`feeds.json`](feeds.json) and [`pipeline_config.json`](pipeline_config.json).
2. Run `python3 scripts/rss_daily_report.py --json-output`.
3. If the run is blocked, open the emitted `.failed.md` `report_path`.
4. If the run is publishable, use `/dailynews-report` to assemble the formal
   success report through `scripts/editorial_runtime.py assemble`.
5. If the run is blocked, inspect `validation_path` and the sidecar `*.stderr.txt`
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
  "fetch": {
    "hours": 24,
    "max_summary": 300
  },
  "summary_enrichment": {
    "short_summary_threshold": 80,
    "page_fallback_cap": 300
  },
  "article_text": {
    "enabled": true,
    "max_words": 150,
    "max_workers": 4
  },
  "render": {
    "part1_summary_max_chars": 200,
    "part2_summary_max_chars": 200
  },
  "context_budget": {
    "llm_context_max_bytes": 200000,
    "part1_brief_max_bytes": 100000,
    "part2_context_max_bytes": 100000,
    "total_context_max_bytes": 360000
  }
}
```

- `fetch.hours` / `fetch.max_summary`: default look-back window and summary cap
  for `rss_daily_report.py`; explicit CLI flags override them per run.
- `summary_enrichment.short_summary_threshold`: below this length, feed summaries
  are treated as too short and trigger page fallback.
- `summary_enrichment.page_fallback_cap`: hard cap for article-page fallback summaries.
- `article_text.enabled`: toggles article-body extraction for LLM editorial context.
- `article_text.max_words`: word cap for each extracted body snippet.
- `article_text.max_workers`: fetch concurrency for body extraction.
- `render.part1_summary_max_chars`: final report truncation limit for the Top 30 section.
- `render.part2_summary_max_chars`: final report truncation limit for the per-source section.
- `context_budget.*_max_bytes`: advisory context budget thresholds written to
  `context_budget.json`; when a size exceeds its limit, the orchestrator keeps
  LLM reads strictly scoped to the shortlist and missing-summary sets.

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

## Optional Claude Code / Codex Workflow

If you use Claude Code or Codex in this repo:

- [`CLAUDE.md`](CLAUDE.md) is the project entrypoint and imports [`AGENTS.md`](AGENTS.md)
- the shared project-local orchestrator skill lives at [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md)
- the Codex / agent skill path [`.agents/skills/dailynews-report/SKILL.md`](.agents/skills/dailynews-report/SKILL.md) is a symlink to the same file
- Codex Skill UI metadata lives at [`.claude/skills/dailynews-report/agents/openai.yaml`](.claude/skills/dailynews-report/agents/openai.yaml), with [`.agents/skills/dailynews-report/agents`](.agents/skills/dailynews-report/agents) symlinked to the same directory
- the skill is exposed as `/dailynews-report`
- the supported runtime architecture is `skill + subagents`
- on the success path, subagents exchange machine-readable handoff artifacts
  (`part1_shortlist.json` / `part1_shortlist_context.json` /
  `part1_plan.json` / `part2_missing_summaries.json` /
  `part2_draft.json`) under `runs/YYYY-MM-DD/`
- deterministic runtime checks, final assembly, final review, and cache updates
  are handled by `scripts/editorial_runtime.py`
- if a success-path handoff artifact is missing or invalid, the runtime should
  stop instead of silently falling back to raw `summary_en` or partial
  reconstruction

The skill delegates to three project-level subagents under
[`.claude/agents/`](.claude/agents/):

- `part1-editor`
- `part2-drafter`
- `network-debugger`

Pipeline execution, branch classification, the artifact audit
(`editorial_runtime.py audit`), Part 2 merging, final assembly, final review,
and the Top 30 digest (`editorial_runtime.py top30`) are direct deterministic
script steps run by the orchestrator, not LLM subagents. On success, the
workflow's final chat reply is the digest's stdout verbatim, so the Top 30
lands directly in the conversation without opening the full report.

This Claude Code / Codex workflow is intentionally manual-only because it is
write-producing and can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.
The Codex metadata keeps `policy.allow_implicit_invocation: false` for the
same reason.

## Repository Layout

```text
scripts/
  rss_daily_report.py      orchestrator: fetch -> validate -> context -> failure render
  rss_news_monitor.py      RSS fetch + dedup
  qc_validate.py           contract + data-quality validator
  build_llm_context.py     shapes llm_context.json
  editorial_runtime.py     audit / shortlist / assemble / review / cache helper
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
  SKILL.md                 shared Claude Code / Codex orchestrator skill
  agents/openai.yaml       Codex Skill UI metadata, manual-only policy
.agents/skills/dailynews-report/
  SKILL.md                 symlink to .claude/skills/dailynews-report/SKILL.md
  agents                   symlink to .claude/skills/dailynews-report/agents
.claude/agents/
  *.md                     project-level subagents used by the orchestrator
```

## Maintainer Docs

- [`AGENTS.md`](AGENTS.md): repository contract, artifact schemas, runtime boundaries,
  and shared-module guidance
- [`CLAUDE.md`](CLAUDE.md): Claude Code entrypoint for this repo
- [`.claude/skills/dailynews-report/SKILL.md`](.claude/skills/dailynews-report/SKILL.md):
  shared manual Claude Code / Codex orchestrator workflow
- [`.agents/skills/dailynews-report/SKILL.md`](.agents/skills/dailynews-report/SKILL.md):
  symlinked Codex / agent skill entrypoint
- [`.claude/skills/dailynews-report/agents/openai.yaml`](.claude/skills/dailynews-report/agents/openai.yaml):
  Codex Skill UI metadata for the shared orchestrator
- [`.claude/agents/`](.claude/agents/): subagents for Part 1 editing, Part 2
  drafting, and unexpected-error debugging
- [`TASKS.md`](TASKS.md): long-running tracker for repository architecture work

## License

This project is licensed under the MIT License. See [`LICENSE`](LICENSE).
