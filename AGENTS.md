# DailyNewsAgent Guide

> `AGENTS.md` is the source of truth for the repo contract and maintainer guidance.
> `CLAUDE.md` is a symlink to `AGENTS.md`, so the Claude Code entrypoint reads this same file.
> `.claude/skills/dailynews-report/SKILL.md` is the shared Claude Code / Codex skill file.
> `.agents/skills/dailynews-report/SKILL.md` is a symlink to the same file.

## Scope

This repository builds a daily RSS report in two stages:

1. Deterministic pipeline in code: fetch, validate, artifact generation, failure-report rendering, and zero-article / contract gating.
2. Claude Code post-processing in `skill + subagents`: Chinese summaries, event clustering, Top 30 selection, and content audit.

`AGENTS.md` is the source of truth for contract boundaries and maintainer-facing behavior in this workspace. The step-by-step runtime procedure lives in the orchestrator skill and the subagent files.

Since Epic U the Android app and the Python pipeline deliberately diverge in
report shape: the Android app produces Part 1 only (Top N digest plus a native
RSS reader consumes the full article pool), while `/dailynews-report` and
`scripts/` keep producing Part 1 + Part 2. The contract rules in this document
(link integrity, summary lint, no silent fallbacks, single writer per artifact)
still bind both sides. Do not "fix" either side's report shape to re-align
them; the divergence is a product decision, not a bug.

## Document Roles

- `README.md` is the public-facing entry point: project overview, quick start, and pointers into the rest of the docs.
- `CLAUDE.md` is the Claude Code entrypoint for this repo and must remain a symlink to `AGENTS.md`, so both entrypoints read one file. Task-style work goes to the shared project skill; the version bump rule lives in `Versioning` below.
- `AGENTS.md` defines repo-level contract rules, allowed inputs/outputs, agent boundaries, and maintainer guidance.
- `pipeline_config.json` is the repo-level deterministic pipeline config for fetch defaults (time window, summary cap), summary-enrichment, and renderer truncation thresholds.
- `TASKS.md` is the long-running tracker and planning panel for Claude Code architecture work in this repo. Update it before landing new execution-flow changes.
- `.claude/skills/dailynews-report/SKILL.md` is the project-local orchestrator skill and the canonical runtime procedure file shared by Claude Code and Codex.
- `.agents/skills/dailynews-report/SKILL.md` is the Codex / agent skill path and must remain a symlink to `.claude/skills/dailynews-report/SKILL.md`.
- `.claude/skills/dailynews-report/agents/openai.yaml` is the Codex Skill UI metadata and must keep the workflow manual-only with `policy.allow_implicit_invocation: false`.
- `.agents/skills/dailynews-report/agents` is the Codex / agent metadata path and must remain a symlink to `.claude/skills/dailynews-report/agents`.
- `.claude/agents/*.md` defines the specialized LLM subagents used by the orchestrator skill.

## Versioning

The app version lives in exactly one place — `versionCode` / `versionName` in
`android/app/build.gradle.kts`. There is no second version string in this repo,
and `scripts/` (the Python pipeline) is deliberately versionless.

The bump rule below binds every agent working in this repo, Codex included —
read it before changing anything under `android/`. It lives here, in the single
source-of-truth file, because two copies of a numeric policy drift, and the
drift is silent.

### Version Bump Rule

- **Every iteration bumps `versionName` by 0.01.** Read `0.M.P` as the decimal
  `0.MP`: `0.2.0` → `0.2.1` → `0.2.2` → … The patch component therefore never
  exceeds `9`; `0.2.9 + 0.01` carries into `0.3.0`.
- **A major iteration bumps by 0.1 instead**: `0.2.x` → `0.3.0`, and `0.9.x` →
  `1.0.0`. Major means a whole Epic, a Room schema migration, a new top-level
  screen, or any user-visible change big enough to deserve its own release
  note. When in doubt, ask rather than guessing — a version is a promise to the
  device that already has the old APK installed.
- **`versionCode` is +1 on every bump, without exception.** Android refuses to
  install an APK whose `versionCode` is not strictly greater than the installed
  one, so a forgotten bump does not fail the build — it fails silently on the
  user's phone during upgrade testing.
- **The bump ships in the same commit as the work it versions**, never as a
  separate "bump version" commit. Reviewing a diff should show what changed and
  which version carries it, together.
- **What does not bump:** doc-only, test-only, or tooling-only changes, and
  anything confined to `scripts/` (the Python pipeline is versionless and does
  not share this number).
- Before handing over an APK, confirm the built `versionName` matches the
  iteration just completed. The version printed in the delivery message must be
  read back from the build, not from memory.
- **A `git push` to GitHub is incomplete until a signed `app-release.apk` is
  on a GitHub Release for that `versionName`.** Do not push and leave the APK
  for later. The procedure is in `Release Signing And GitHub Publish` below.

## Release Signing And GitHub Publish

**Hard rule for every agent:** every `git push` of this repo to GitHub
(`origin/main`, and any other remote branch the user asked to publish) must
also produce a signed release APK and attach it to the GitHub Release for
the APK's `versionName`. A push without that APK is not done.

This does not change the version bump rule. Doc-only / test-only / `scripts/`
pushes still do not bump `versionName`. They still build and publish: if
`v<versionName>` already exists, replace `app-release.apk` on that release
(`gh release upload --clobber`) instead of inventing a second version string.

Release APKs are signed with the keystore at
`android/.signing/dailynews-release.jks`. The keystore file and the three
signing fields live in 1Password (vault `Development`) and must never be
committed, printed, or written anywhere except the gitignored
`android/.signing/` directory and `android/keystore.properties`.

1Password items:

- Document `DailyNews Android Release Keystore` — the `.jks` file
- Secure note `DailyNews Android Release Signing` — fields `keyAlias`,
  `storePassword`, `keyPassword`

Publishable artifact is always `app-release.apk` (v2/v3 signed).
`app-release-unsigned.apk` must never be tagged, attached, or handed over.

### Sign a release APK

From the repo root, with `op` signed in:

```sh
mkdir -p android/.signing
if [ ! -f android/.signing/dailynews-release.jks ]; then
  op document get 'DailyNews Android Release Keystore' --vault Development \
    --out-file android/.signing/dailynews-release.jks --file-mode 0600
fi
printf 'storeFile=.signing/dailynews-release.jks\nkeyAlias=%s\nstorePassword=%s\nkeyPassword=%s\n' \
  "$(op read 'op://Development/DailyNews Android Release Signing/keyAlias')" \
  "$(op read 'op://Development/DailyNews Android Release Signing/storePassword')" \
  "$(op read 'op://Development/DailyNews Android Release Signing/keyPassword')" \
  > android/keystore.properties && chmod 600 android/keystore.properties
( cd android && ./gradlew :app:assembleRelease )
```

Then verify, reading `versionName` back from the APK rather than from memory:

```sh
APK=android/app/build/outputs/apk/release/app-release.apk
test -f "$APK"
"$ANDROID_SDK/build-tools/35.0.0/apksigner" verify --verbose "$APK"
"$ANDROID_SDK/build-tools/35.0.0/aapt" dump badging "$APK" | grep -E 'package:|application-label'
shasum -a 256 "$APK"
```

`$ANDROID_SDK` is `sdk.dir` in `android/local.properties`. With neither
`keystore.properties` nor the `DAILYNEWS_*` env vars configured, the release
variant stays unsigned — never publish that. A half-filled
`keystore.properties` is a hard build failure.

Archive `android/app/build/outputs/mapping/release/mapping.txt` next to the
APK locally (needed to deobfuscate release crashes). Do not attach the mapping
to the public GitHub release.

### Push the sources and publish the APK on GitHub

These two steps are one unit of work. `git push` without the APK step below
is a contract miss.

The signed APK is a GitHub Release asset, not a git blob. Tag name is
`v<versionName>` and must match the APK's `versionName` / `versionCode`.

```sh
git push origin main
VERSION="$( "$ANDROID_SDK/build-tools/35.0.0/aapt" dump badging "$APK" \
  | sed -n 's/.*versionName='\''\([^'\'']*\)'\''.*/\1/p' )"
CODE="$( "$ANDROID_SDK/build-tools/35.0.0/aapt" dump badging "$APK" \
  | sed -n 's/.*versionCode='\''\([^'\'']*\)'\''.*/\1/p' )"
SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
NOTES="$(printf '%s\n' \
  "Signed Android release APK for DailyNews." \
  "" \
  "- Package: \`com.dailynews.app\`" \
  "- Version: \`${VERSION}\` (\`versionCode\` ${CODE})" \
  "- Signing: APK Signature Scheme v2 / v3" \
  "- APK SHA-256: \`${SHA}\`")"
if gh release view "v${VERSION}" >/dev/null 2>&1; then
  gh release upload "v${VERSION}" "$APK" --clobber
  gh release edit "v${VERSION}" --notes "$NOTES"
else
  gh release create "v${VERSION}" "$APK" \
    --title "DailyNews ${VERSION}" \
    --notes "$NOTES"
fi
```

The release certificate is not the debug certificate: a device with a debug
install must `adb uninstall com.dailynews.app` before installing this APK.

## Claude Code Usage

- `AGENTS.md` remains the source of truth for the repository contract and maintainer guidance. `CLAUDE.md` is a symlink to this file, so there is nothing left to `@`-import.
- `TASKS.md` is the long-running tracker for Claude Code architecture changes in this repo.
- For the full report workflow, use the project-local orchestrator skill `/dailynews-report` at `.claude/skills/dailynews-report/SKILL.md`; Codex reaches the same file through `.agents/skills/dailynews-report/SKILL.md` (entry: `$dailynews-report`).
- Codex Skill UI metadata lives at `.claude/skills/dailynews-report/agents/openai.yaml`; `.agents/skills/dailynews-report/agents` is a symlink to the same metadata directory.
- `.claude/agents/*.md` defines the subagents used by the orchestrator skill.
- The skill is intentionally manual-only because it runs a heavy, write-producing workflow that can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.

## Entry Points

- Main pipeline: `python3 scripts/rss_daily_report.py --json-output [--hours N] [--max-summary N] [--config pipeline_config.json] [--retain-days 90] [--no-cleanup]` — the time window and summary cap default from `pipeline_config.json.fetch` (`hours: 24`, `max_summary: 300`); explicit CLI flags override the config
- Fetch only: `python3 scripts/rss_news_monitor.py --json --max-summary 300 --hours 24 [--config pipeline_config.json]`
- Validate only: `python3 scripts/qc_validate.py --input runs/<date>/raw.json --feeds feeds.json`
- Build LLM context: `python3 scripts/build_llm_context.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output runs/<date>/llm_context.json --report-path $REPO_ROOT/rss-report-<date>.md`
- Deterministic renderer: `python3 scripts/render_report.py --input runs/<date>/raw.json --validation runs/<date>/validation.json --output $REPO_ROOT/rss-report-<date>.md`
- Network diagnostic: `python3 scripts/network_debug.py --limit 5`
- Offline tests: `python3 -m unittest discover -s $REPO_ROOT/tests -p 'test_*.py'`
- End-to-end smoke (real network, ~10s): `python3 scripts/rss_daily_report.py --json-output --no-cleanup`
- Claude Code runtime entry: `/dailynews-report`
- Codex runtime entry: `$dailynews-report`
- Codex skill entry: `.agents/skills/dailynews-report/SKILL.md`
- Codex skill metadata: `.agents/skills/dailynews-report/agents/openai.yaml`

## Runtime Outputs

For each report date, the pipeline writes:

- `runs/YYYY-MM-DD/raw.json`
- `runs/YYYY-MM-DD/validation.json`
- `runs/YYYY-MM-DD/llm_context.json`
- `runs/YYYY-MM-DD/part1_brief.json`
- `runs/YYYY-MM-DD/part2_context.json`
- `runs/YYYY-MM-DD/context_budget.json`
- `runs/YYYY-MM-DD/fetch.stderr.txt`
- `runs/YYYY-MM-DD/validate.stderr.txt`
- `runs/YYYY-MM-DD/llm_context.stderr.txt`
- `runs/YYYY-MM-DD/render.stderr.txt`

Final report paths are resolved at the repo root:

- Success target: `rss-report-YYYY-MM-DD.md`
- Failure: `rss-report-YYYY-MM-DD.failed.md`

On the default `/dailynews-report` success path, `rss_daily_report.py` only
emits the success `report_path`; it must not prewrite the formal success
report. The success file is written later by `scripts/editorial_runtime.py
assemble` from `part1_plan.json` and `part2_draft.json`. The deterministic
pipeline may write `*.failed.md` directly for blocked or damaged-input runs.

### Claude Code Success-Path Handoff Artifacts

When `/dailynews-report` runs the Claude Code success branch, the runtime may
additionally write:

- `runs/YYYY-MM-DD/part1_plan.json`
- `runs/YYYY-MM-DD/part1_shortlist.json`
- `runs/YYYY-MM-DD/part1_shortlist_context.json`
- `runs/YYYY-MM-DD/part2_missing_summaries.json`
- `runs/YYYY-MM-DD/part2_draft.json`
- `runs/YYYY-MM-DD/top30.md`

These are success-path handoff artifacts for the LLM runtime only. They are
not deterministic pipeline outputs. `top30.md` is the persisted copy of the
fixed-format Top 30 digest that `scripts/editorial_runtime.py top30` prints
for the chat-facing final reply.

### Cross-Run Workspace Files

- `runs/_cache/editorial_cache.json` — Chinese-summary cache, updated only by `scripts/editorial_runtime.py assemble`.
- `runs/_seen_links.json` — cross-run seen-links ledger, written only by `assemble` (so only links that landed in a published success report are marked seen). The fetch step reads it to drop articles already covered by an earlier day's report; the fetch window is wider than 24h precisely so run-time jitter cannot leave coverage gaps, and the ledger prevents the resulting overlap from re-reporting. Same-date entries never filter, keeping same-day re-runs idempotent.
- `runs/_feedback.md` — optional, user-maintained editorial feedback log. The most recent lines are injected into `part1_brief.json.editor_feedback` so Part 1 taste can be tuned without editing agent prompts.

Both ledgers are written via atomic temp-file replace, and their
read-modify-write cycles are serialized with `<file>.lock` sidecar files
(`_common/fsio.py`), so concurrent same-date runs can neither tear a ledger
nor silently drop each other's updates. `rss_daily_report.py` additionally
logs a `WARN` when a same-date run dir already contains success-path handoff
artifacts — the signature of a duplicate scheduler trigger.

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
source_groups       [{ source, url, status, article_count, article_refs: [<article-ref>] }]
```

### LLM sidecar context files

```
part1_brief.json    concise first-pass Part 1 article list; no full article_text;
                    article_text_preview present only when summary_en is missing/short;
                    editor_feedback[] present when runs/_feedback.md has recent lines
part2_context.json  concise Part 2 source-group drafting context; summary_en first
context_budget.json byte-size counts, per-source counts, within_budget flag, budget violations
```

### Per-article object (7 fields)

```
source            string   # feed display name
title             string   # English original
link              string   # full original URL
pub_date_utc      string   # human-readable "YYYY-MM-DD HH:MM UTC"
pub_date_iso      string   # ISO 8601 with +00:00
summary_en        string   # English summary, may be empty
article_text      string   # extracted article main body (up to ~150 words), may be empty
```

The deterministic pipeline no longer emits scoring metadata
(`heuristic_score`, `audit_flags`, `amount_millions`) or a pre-filtered
`candidate_articles` list. Top 30 selection, clustering, de-noising, and
priority ordering are the sole responsibility of the `part1-editor` subagent.

`source_groups[]` no longer duplicates full article payloads. It carries
`article_refs[]` only:

```
article_refs[]    [{ title, link, pub_date_iso }]
```

`article_text` is a best-effort extraction of the source article's main body
via `_common/article_extract.py`. It is intended as the primary input for
LLM Chinese summarization after shortlist (Part 1) or short-summary fallback
(Part 2). It is empty when extraction
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
- On publishable runs, `rss_daily_report.py --json-output` must not prewrite the success `report_path`; `scripts/editorial_runtime.py assemble` owns that write.
- On blocked or damaged-input runs, `rss_daily_report.py --json-output` must still emit the 8 control-plane fields and write a concrete `*.failed.md` report whenever `report_path` is known.
- `llm_context.json` is the compact authority for article identity, link integrity, source order, and shortlisted article material.
- `part1_brief.json` is the first-pass Part 1 ranking input. First-pass shortlisting works from title, source, and `summary_en`; a short `article_text_preview` is attached only when `summary_en` is missing or shorter than the configured threshold (see the brief's `preview_policy`), so the LLM can create a 40-45 item shortlist without reading every full article body.
- `part2_context.json` is the Part 2 drafting input. It uses `summary_en` first and only falls back to short `article_text` material when the feed summary is too short.
- `context_budget.json` is advisory but must be inspected by the orchestrator. If `within_budget` is false, the orchestrator must surface the violations and keep every LLM read strictly scoped to the shortlist and missing-summary sets.
- `part1_plan.json` is link-keyed: items reference articles by `link` (with `also_links[]` for merged same-event coverage) and carry only editorial fields such as `summary_zh`; rank is implicit in array order, and titles, sources, and timestamps are joined from `llm_context.json` at assemble time. Plans must not echo those authoritative fields.
- `part1_plan.json` carries at most 30 items, `shortfall` must equal `max(0, 30 - len(items))`, and an article may appear only once across all `items[].link` and `also_links[]`. All three are enforced by `validate_part1` at assemble / review / top30.
- Editorial agents must never read `runs/_cache/` files directly. Cache reuse is injected deterministically: `part2_context.json` marks hits with `needs_summary == false`, and `shortlist-context` attaches `cached_summary_zh` / `cached_event_key` to shortlisted articles. Cached summaries are lint-checked at injection time (per-part hard caps, no links); an entry that fails is demoted to a normal cache miss so a poisoned or oversized cache entry can never hard-block `assemble` later.
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
- `feed_results[].newest_item_date` is an optional additive field carrying the feed's newest item timestamp before window filtering. When present on a non-error feed and older than `pipeline_config.json.fetch.stale_feed_warn_days` (default 30), the validator emits a `stale feed(s)` warning — warning-only, never blocking. Older artifacts without the field are unaffected.
- `unique_source_count` is observational only. It is not a blocking integrity rule.
- `part1_shortlist.json`, `part1_shortlist_context.json`, `part1_plan.json`, `part2_missing_summaries.json`, and `part2_draft.json` are success-path handoff artifacts only; they must be machine-readable and complete enough for deterministic merge/assembly without scraping long prose from chat output.
- If a success-path handoff artifact is missing, truncated, or schema-invalid, agents must stop the success branch and return a blocking issue. They must not silently fall back to raw `article_text` / `summary_en` or partial manual reconstruction.
- `article_text` and `summary_en` are source material only. They may inform editorial work, but the final formal report must use the success-path Chinese summaries from `part1_plan.json` / `part2_draft.json`.
- The success-path chat deliverable is the verbatim stdout of `scripts/editorial_runtime.py top30` (persisted at `runs/<date>/top30.md`). Agents must not hand-compose, rephrase, or re-rank the Top 30 in chat output.
- Final `summary_zh` values must not contain URLs or markdown links and are subject to hard length caps (400 chars Part 1 / 200 chars Part 2, enforced at assemble and review, and applied again when cached summaries are injected). This is the containment line against prompt injection smuggled through scraped `article_text`. The shared lint lives in `_common/editorial.py` (`summary_lint_errors`).
- Titles must remain in English.
- Links must remain complete and unchanged.
- Articles must come only from the script output. No fabrication is allowed.

## Claude Code / Codex Runtime Architecture

- `.claude/skills/dailynews-report/SKILL.md` is the canonical runtime procedure entry. `.agents/skills/dailynews-report/SKILL.md` must remain a symlink to the same file so Claude Code and Codex reuse one skill body.
- `.claude/skills/dailynews-report/agents/openai.yaml` is the canonical Codex Skill metadata file. `.agents/skills/dailynews-report/agents` must remain a symlink to the same directory so Codex sees the same metadata at its skill path.
- The shared skill orchestrates the branch flow but should not absorb every specialized task into one monolithic prompt.
- Pipeline execution and branch classification are deterministic orchestrator steps, not subagents. The orchestrator runs `python3 scripts/rss_daily_report.py --json-output` directly, parses the 8 control-plane fields, and classifies the result as `success`, `expected-block`, or `unexpected-error` using the three fixed rules in the skill.
- The artifact audit is a deterministic orchestrator step, not a subagent. On `success` the orchestrator runs `python3 scripts/editorial_runtime.py audit --llm-context ... --validation ...` before any editorial work; it verifies `counts.articles`, source order, source-group consistency, and error-text readiness, and a non-zero exit blocks the success branch.
- `network-debugger` is unexpected-error only. It inspects `runs/<date>/` sidecar stderr first and may run `python3 scripts/network_debug.py --limit 5` only when the evidence points to a network or fetch problem.
- `part1-editor` is success-only. It performs Part 1 in two LLM passes: first read `part1_brief.json` to write `part1_shortlist.json`, then use `scripts/editorial_runtime.py shortlist-context` (which injects deterministic cache hits) to generate `part1_shortlist_context.json` and write the link-keyed `runs/<date>/part1_plan.json`. The deterministic pipeline contributes no scoring or filtering signals; editorial judgment lives entirely in the agent prompt at `.claude/agents/part1-editor.md`.
- `part2-drafter` is success-only. It reads compact cache-aware `part2_context.json` and writes only `runs/<date>/part2_missing_summaries.json` for articles whose `needs_summary` is true. The orchestrator then runs `scripts/editorial_runtime.py merge-part2` to build the full `part2_draft.json`.
- `items[]` stays the one container name `part2-drafter` is told to emit, but `merge-part2` also accepts `missing[]`, `articles[]`, `summaries[]`, and `groups[].articles[]`. `missing[]` is listed first because the file is named `part2_missing_summaries.json` and that is the name agents and third-party integrations reach for. When nothing matches and the payload holds exactly one unrecognized list, the error names that key — a container-name mismatch must not present as a wall of lost links.
- `part1-editor` and `part2-drafter` are independent and should be launched in parallel; both must complete before deterministic merge/assembly.
- `scripts/editorial_runtime.py assemble` is the only success-path writer of the final `report_path`. It validates handoff schemas, assembles the final Chinese report by joining `part1_plan.json` / `part2_draft.json` with the authoritative titles, sources, and timestamps from `llm_context.json`, and updates `runs/_cache/editorial_cache.json` without ever overwriting `*.failed.md`. Post-write cache and seen-links bookkeeping is best-effort: a corrupt or unwritable ledger logs a `WARN` to stderr and never fails a run whose report was already written.
- `scripts/editorial_runtime.py review` runs after the write. It checks English titles, unchanged links, Part 2 counts, source order, error-group handling, and that no raw `article_text` / `summary_en` leaks into the final report.
- `scripts/editorial_runtime.py top30` runs after review. It re-validates `part1_plan.json`, renders the fixed-format Top 30 digest (shared item renderer with `assemble`, so chat output and the report's Part 1 can never diverge), writes `runs/<date>/top30.md`, and prints the digest to stdout. The orchestrator's final success reply is that stdout verbatim — the LLM never composes or reformats the digest.
- Fixed branch order:
  - success: `run pipeline -> editorial_runtime audit -> part1-editor + part2-drafter (parallel) -> editorial_runtime merge-part2 -> editorial_runtime assemble -> editorial_runtime review -> editorial_runtime top30`
  - expected-block: `run pipeline -> keep failure report; return report_path`
  - unexpected-error: `run pipeline -> retry pipeline once -> network-debugger`
- The unexpected-error retry is single and bounded: one re-run of the pipeline command, then diagnosis. A retry that classifies as `success` or `expected-block` continues on that branch normally.
- `part1-editor` and `part2-drafter` may write only their own handoff artifacts (`part1_shortlist.json` / `part1_shortlist_context.json` / `part1_plan.json` / `part2_missing_summaries.json`) and must complete before deterministic merge/assembly.
- `scripts/editorial_runtime.py assemble` and `network-debugger` must never run in parallel.
- `scripts/editorial_runtime.py review` must always run after the final success-path write.
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
  construction, and defensive source-group consistency checks used by both
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
- `_common/fsio.py` — `atomic_write_text` (same-directory temp file +
  `os.replace`) and `file_lock` (advisory `<path>.lock` sidecar locking) used
  by runtime artifact writes and the cross-run ledgers.
- `_common/article_extract.py` — stdlib-only main-text extractor that
  prefers `<article>` / `<main>` / `role='main'` containers, drops
  script / style / nav / aside / footer / header / form regions, and
  truncates to ``article_text.max_words`` whitespace tokens. Populates the
  ``article_text`` field that editorial agents consume.
- `_common/schemas.py` — `TypedDict` shapes for `RawDocument`,
  `ValidationDocument`, `LlmContextDocument`, `PipelineOutput`, plus
  `STATUS_OK / STATUS_EMPTY / STATUS_ERROR` constants. Documentation-grade;
  the validator stays the source of truth for what is rejected.
- `scripts/editorial_runtime.py` — deterministic runtime helper for artifact
  audit, Part 1 shortlist context slicing, final assembly, final review, and
  `runs/_cache/editorial_cache.json` updates.

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
- On the JSON output path, the summary fallback and the `article_text`
  extraction below share a single page fetch per article
  (`_common/feed_fetch.py:enrich_article_pages`): each article page is fetched
  at most once and the same response feeds both the meta-summary extractor and
  the main-body extractor. Non-JSON modes (`--summary`, default grouped text)
  do not need `article_text`, so they keep the summary-only single fetch
  (`enrich_missing_summaries`).

## Article Body Extraction

- On the JSON output path, `rss_news_monitor.py` enriches each article page in a
  single fetch: `enrich_article_pages` extracts both the HTML meta-summary
  fallback and the `article_text` main body (via `_common/article_extract.py`)
  from one response, rather than fetching the page once per enrichment.
- Extraction prefers `<article>`, `<main>`, or `role='main'` containers,
  strips `<script>`, `<style>`, and obvious chrome (`<nav>`, `<aside>`,
  `<footer>`, `<header>`, `<form>`, etc.), and falls back to the union of
  all `<p>` / `<li>` / `<h*>` blocks when no container is present.
- Output is truncated to `pipeline_config.json.article_text.max_words`
  whitespace tokens (default 150). A trailing `"..."` marks truncation.
- `pipeline_config.json.article_text.enabled` toggles the pass globally.
  When disabled or when extraction fails, `article_text` is an empty
  string and editorial agents fall back to `summary_en`.
- `pipeline_config.json.article_text.max_workers` (default 4) caps fetch
  concurrency for the merged single-fetch enrichment pass.
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

- Read `part1_brief.json` first and produce `part1_shortlist.json` before reading full shortlisted article context
- Select Top 30 autonomously; the deterministic pipeline emits no scoring, flags, or pre-filtered candidate list — filtering, clustering, and ordering are the agent's responsibility
- Prefer shortlisted `article_text` as the source of truth for Part 1 Chinese summarization; use `part2_context.json.summary_material` for Part 2. Never fabricate body text that is not in either field.
- Cluster duplicate or near-duplicate coverage of the same event
- Prioritize major industry events such as financing `>=100M`, acquisitions, and major regulation.
- Then prioritize major product launches from Apple, Google, NVIDIA, OpenAI, and similar companies.
- Then prioritize security or compliance events that reach platform or institutional scale — actively exploited 0days, platform-wide vulnerabilities, government / major-enterprise breaches, poisoned public distribution channels, and substantive regulatory rulings.
- Then prioritize important technical breakthroughs.
- Treat routine security advisories as lowest-tier filler: ordinary CVE / patch notices with no in-the-wild evidence and no platform-wide blast radius, single-target intrusions, region-scoped incidents, and routine threat-intel or APT activity updates rank at the bottom of the catch-all tier and are dropped first when slots are short.
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
- A feed may carry an optional `"user_agent"` string. When present and
  non-blank it replaces the default `User-Agent` for that feed's own fetch;
  blank or absent means the shared default. It exists for servers that reject
  the default UA — it is a per-feed escape hatch, not a knob to set casually,
  and each use should say in the commit which server needed it and why.
- **`user_agent` is honored by the Python pipeline only.** The Android app
  reads its feed list from Room, so carrying the field to the device needs a
  schema migration; until that lands, `FeedFetcher` uses its fixed UA and the
  field is dropped at seed time. This is a known, deliberate divergence in a
  shared config file: setting `user_agent` changes Python behavior and does
  nothing on Android. Do not treat the Android side as broken; treat the field
  as unavailable there.
- `user_agent` applies to the feed request only. Article-page enrichment
  (`article_text` and the meta-summary fallback) always uses the default UA,
  because that pass is keyed by article link rather than by feed.

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
- If the runtime procedure changes, keep `TASKS.md`, `README.md`, `AGENTS.md`, `.claude/skills/dailynews-report/SKILL.md`, `.claude/skills/dailynews-report/agents/openai.yaml`, the `.agents/skills/dailynews-report/SKILL.md` / `.agents/skills/dailynews-report/agents` symlinks, and the relevant `.claude/agents/*.md` files aligned. `CLAUDE.md` is a symlink to `AGENTS.md` — edit `AGENTS.md` only.
- If tests change, update the fixture set in `tests/fixtures/` — including
  `feeds_fixture.json`, `pipeline_config_fixture.json`, and the two golden artifacts
  (`markdown_render_golden.md`, `llm_context_golden.json`). Never make
  tests depend on the user's real `feeds.json` or `pipeline_config.json`.
- Runtime outputs (`rss-report-*.md` and `runs/`) are gitignored. Fetched
  content lives in the user's local clone, not the repo.
- When refactoring `rss_news_monitor.py` or any fetch-path code, run a real
  end-to-end smoke (`python3 scripts/rss_daily_report.py --json-output`)
  before declaring done. Unit tests bypass `parse_feed` and
  cannot catch missing imports on that path.
- Prefer extending `scripts/_common/*` over duplicating helpers; the
  contract-snapshot tests will catch behavioural drift in raw.json /
  validation.json / llm_context.json.
- Do not re-couple `build_llm_context.py` to private helpers inside
  `render_report.py`; shared editorial-domain behavior belongs in
  `scripts/_common/editorial.py`.
