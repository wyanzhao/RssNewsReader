# Shared Python Modules Guide (`scripts/_common`)

> The root `AGENTS.md` is the global authority for the repo contract and the
> canonical module inventory. This file only adds module-local boundaries,
> contracts, and risks for `scripts/_common`. When they disagree, the root
> file wins.

## Scope And Boundaries

- `scripts/_common` is the shared utility surface used by every pipeline
  script (`rss_daily_report.py`, `rss_news_monitor.py`, `qc_validate.py`,
  `build_llm_context.py`, `render_report.py`, `editorial_runtime.py`).
  Prefer extending this package over duplicating logic in individual
  scripts.
- Every module must stay **import-safe with no side effects** (see
  `__init__.py`): no network, no file writes, no global state at import
  time, so unit tests can load modules in isolation.
- The package never writes pipeline artifacts on its own; artifact writing
  belongs to the scripts that call into it. The exceptions are ledger
  helpers (`seen_links.py`, `editorial_cache.py`) which own their own
  cross-run files under the locking/atomicity contract below.

## Key Contracts

- **Behaviour parity.** `text.py` and the other migrated helpers are
  verbatim extractions from the original inlined implementations; parity is
  enforced by `tests/test_contracts_snapshot.py` and the offline suite. A
  behaviour change here is a pipeline behaviour change everywhere.
- **Ownership templates.** `paths.py` is the single owner of the
  `runs/YYYY-MM-DD/` and `rss-report-YYYY-MM-DD.md` path templates; no
  other module may hardcode those shapes.
- **Atomic writes + locking.** All runtime artifact writes go through
  `fsio.atomic_write_text` (same-directory temp file + `os.replace`);
  cross-run ledgers additionally serialize read-modify-write cycles with
  `fsio.file_lock` (`<file>.lock` sidecars). Never bypass them for ledger
  files (`runs/_seen_links.json`, `runs/_cache/editorial_cache.json`).
- **Seen-links semantics.** `seen_links.py` entries are recorded only by
  `editorial_runtime.py assemble` (published success reports); entries with
  the same run date never filter, keeping same-day re-runs idempotent.
- **Summary lint is a security boundary.** `editorial.summary_lint_errors`
  enforces the hard caps (400 chars Part 1 / 200 chars Part 2, no URLs or
  markdown links) that contain prompt injection smuggled through scraped
  `article_text`. It is applied at assemble, review, and cache injection;
  do not relax it or add per-call exemptions.
- **Editorial cache injection, not reads.** `editorial_cache.py` supplies
  deterministic cache-hit injection into `part2_context.json` and
  shortlist context; lint-failing cached entries are demoted to misses and
  can never hard-block `assemble`. Editorial agents must never read cache
  files directly.
- **Schemas are documentation-grade.** `schemas.py` TypedDicts describe the
  artifact shapes, but `qc_validate.py` remains the source of truth for
  what is rejected; keep the two in sync when the contract changes.
- **Config snapshots.** `runtime_config.py` loads `pipeline_config.json`
  and supports snapshotting effective values into `raw.json.runtime_config`
  so downstream render steps do not drift with later config edits.

## Common Risks

- Editing a "small" helper in `text.py` / `editorial.py` without running
  `python3 -m unittest discover -s tests -p 'test_*.py'` can silently break
  parity across both pipeline scripts and the snapshot tests.
- Writing a ledger without `atomic_write_text` + `file_lock` allows
  concurrent same-date runs to tear the file or drop updates.
- Adding import-time side effects (e.g. reading `pipeline_config.json` at
  module scope) breaks isolated unit loading and offline tests.
- Duplicating path construction instead of reusing `paths.py` causes report
  and run-dir renames to fork.
- Loosening `summary_lint_errors` to unblock a single run removes the only
  containment line against injected content; demote or truncate instead.
