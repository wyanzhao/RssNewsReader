# RssNewsReader

A daily RSS news pipeline that produces a curated Chinese-language news report.

The pipeline has two stages, by design:

1. **Deterministic stage (code)** — fetch, dedup, validate, and render. Writes structured artifacts (`raw.json`, `validation.json`, `llm_context.json`) under `runs/<YYYY-MM-DD>/` and a baseline Markdown report at the repo root.
2. **Editorial stage (LLM)** — consumes `llm_context.json`, clusters duplicate events across sources, picks a Top 30, writes Chinese summaries, and rewrites the final Markdown report.

Only the deterministic stage lives in this repo. The editorial stage is driven by the prompt in [`PROMPT.md`](PROMPT.md) and runs against your LLM of choice.

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
PROMPT.md                  # LLM editorial prompt for the scheduled task
CLAUDE.md                  # symlink to AGENTS.md
```

## Further reading

- [`AGENTS.md`](AGENTS.md) — pipeline contract, artifact schemas, shared utilities
- [`PROMPT.md`](PROMPT.md) — full LLM execution prompt for the editorial stage
