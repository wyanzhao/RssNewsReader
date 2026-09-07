# DailyNews Android

Native Kotlin/Jetpack Compose port of DailyNews. It keeps deterministic
validation, artifact schemas, link integrity, summary lint, seen-links, and
editorial cache semantics aligned with the Python reference implementation.

## Product surfaces

- **简报 (brief)** — the AI-edited Top N digest with Chinese summaries for
  **the selected day**, defaulting to today. The top bar carries a date stepper
  that moves between days that actually have a report; a day without one says
  so instead of silently showing an older report. Consumes `reports` /
  `report_items` only. The history entry sits in this screen's top bar.
- **阅读 (reader)** — a native RSS reader over the article pool: timeline,
  per-feed filter chips with unread counts and health dots, unread-only
  filter, read/unread and favorites. The timeline is grouped by UTC day with
  sticky headers whose count is the full day total, not the paging window.
  Consumes `articles` / `feeds` only.
- **订阅 / 收藏 / 设置 (feeds / favorites / settings)** — unchanged surfaces.
- **线索历史 (story)** — every Part 1 item carries a cross-day `event_key`;
  when a story spans two or more days the card's overflow menu opens its
  timeline. Consumes `report_items` only, and is deliberately absent from the
  reader — that screen must not read report tables.
- **周报 / 月报 (periodic)** — a second editorial pass over the published Part 1
  summaries of a week or month, stored in its own `periodic_reports` table and
  reachable from the history screen. Pure LLM: when generation fails the row is
  written as FAILED with a reason and nothing is fabricated to fill the gap.

Part 2 (per-source groups with per-article Chinese summaries) was retired in
Epic U — see `ui/report/ReportSections.kt` for the two-line restore procedure.
Since Epic U the Android report shape intentionally diverges from the Python
`/dailynews-report` pipeline, which still produces Part 1 + Part 2; do not
re-align them.

## Editorial feedback and run measurements

The article menu in a brief offers **选题反馈**: valuable, reduce a named topic,
reduce this source, repetitive coverage, or interest in an event's next development.
Feedback can be edited or removed from the same menu. It affects subsequent
shortlisting and final ranking; it never rewrites an already-published report.
The current implementation retains the latest 100 article decisions in the
backed-up pipeline configuration and sends the latest 10 alongside all 20
manual editorial preferences. Event-follow-up feedback is an editorial preference;
it is not yet a separate alert subscription.

Diagnostics and its shared text summary show monotonic elapsed times for
preparation, validation, editorial work, assembly and review. Each model-operation
measurement includes its internal transport/JSON retries. Editorial total time
already contains model time, so the rows must not be added together. These are
elapsed-time measurements, not provider billing estimates. Older runs have no
stage timing data. A fetch that fails before a run ID is assigned also has no
stage timing record; its error remains in the existing diagnostics.

Each physical LLM request now records its operation, contract round, request/JSON
repair index, accounting coverage and outcome separately. Diagnostics shows whether
a successful run needed retries, including contract rework, and explicitly marks
missing telemetry as unknown. JSON parsing success alone does not count as report
success. Failed or interrupted runs are not labeled first-pass successes.

For OpenRouter, `usage.cost` is preserved as a decimal account charge in USD and
summed across all recorded attempts, including rejected plans and repairs. Missing
charges remain unknown; a partial sum is labeled with coverage and never called a
total. Other OpenAI-compatible services and Anthropic are not assigned OpenRouter
billing semantics. These figures exclude unreported upstream BYOK charges and
credit purchase fees. See [OpenRouter usage accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting).
Connection-test and save results are also shown in a snackbar, so they remain
visible when the inline message is below the fold.

## Recovering an interrupted report

The brief page offers **停止生成** while a run is active. Accepted shortlist and
plan stages are checkpointed separately. In diagnostics, select a failed run and
choose **更多 → 从原始输入恢复**. Recovery creates a new run linked to the source;
it reads that run's frozen articles instead of fetching a new pool. Validation,
artifact audit, assembly and final review still run normally. **立即生成** keeps
its existing meaning: use the current article pool and perform fresh editing.

Recovery verifies the saved pipeline and feed configuration, report date,
checkpoint checksum, authoritative links/summary contracts, prompt/schema and
configured provider/model signature. App version and build type are also bound.
Missing input snapshots, corrupted checkpoints or drift affecting a reused stage stop recovery with a
persisted diagnostic; there is no silent switch to a fresh run. A stage with no
accepted checkpoint is executed within the normal bounded retry/call limits.
Successful source runs are not recovery candidates. Old runs made before input
snapshot support require fresh generation. Configured model identifiers are
bound; undocumented server-side weight changes behind the same identifier cannot
be detected by the app.

Recent-event continuity uses successfully published report rows dated before the
report being generated, not cache-write timestamps. Same-day summaries may still
be reused, but same-day reports do not suppress their own regeneration. If the
editor selects no digest items, the run stops before publication; the repository
also refuses an empty digest before writing files or replacing stored items.

Costs displayed on the recovered run cover its new physical requests; the source
run's already-incurred charges remain in its own diagnostics. A recovery is not a
claim that the entire report cost zero. Full end-to-end recovery-chain accounting
and broader background acceptance remain in the optimization tracker.

## Modules

- `core:model` — serializable artifact and configuration contracts (pure JVM)
- `core:llm` — provider-neutral LLM API plus OpenRouter, OpenAI-compatible, and Anthropic clients (pure JVM)
- `core:pipeline` — deterministic pipeline/editorial state machine behind ports (pure JVM)
- `core:data` — Room, DataStore, encrypted API keys, and artifact files (Android library)
- `app` — Compose UI, WorkManager/AlarmManager scheduling, notifications, and navigation

## Build

JDK 17 is required. JVM parity tests do not require an Android SDK:

```bash
./gradlew :core:pipeline:test
```

Run the complete local verification set with:

```bash
./gradlew test :app:assembleDebug :app:lintDebug
```

The app uses `minSdk 26`, `compileSdk 36`, and `targetSdk 35`. Install Android SDK 36 to
run `./gradlew :app:assembleDebug` or instrumented tests.

`syncFixtures` copies `../tests/fixtures/` and, when present, the ignored local
`../runs/2026-08-03/` replay artifacts into generated JVM test resources.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and is
automatically signed with the local Android debug certificate, so it can be
installed directly with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Canonical credentials live in 1Password (vault `Development`). Every
`git push` to GitHub must also attach a signed `app-release.apk` to the
GitHub Release for that `versionName`. The full procedure — restore the
keystore, materialize `keystore.properties` via `op read`,
`assembleRelease`, `apksigner verify`, `git push`, and `gh release create`
or `gh release upload --clobber` — is in
[`AGENTS.md`](../AGENTS.md#release-signing-and-github-publish).

A local fallback is to copy `keystore.properties.example` to
`keystore.properties` (gitignored) and fill in the passwords, or export the
equivalent `DAILYNEWS_STORE_FILE` / `DAILYNEWS_STORE_PASSWORD` /
`DAILYNEWS_KEY_ALIAS` / `DAILYNEWS_KEY_PASSWORD` env vars. Then:

```bash
./gradlew :app:assembleRelease
```

With credentials configured the output is
`app/build/outputs/apk/release/app-release.apk`, signed v2/v3 and installable.
With nothing configured the build still succeeds and yields
`app-release-unsigned.apk` — as its name states, that artifact carries no
certificate and cannot be installed. A *partially* filled `keystore.properties`
is a hard build failure rather than a silent downgrade to unsigned. Never
publish the unsigned artifact.

Two things the signed APK changes:

- It is signed by a different certificate than the debug APK, so it cannot be
  installed over an existing debug install. `adb uninstall com.dailynews.app`
  first — which wipes the Room database and, because the backup rules exclude
  `provider_keys.xml`, requires re-entering provider keys afterwards.
- It is the only variant that exercises R8. Unit, Roborazzi, and instrumentation
  tests all run against unminified classes, so a missing keep rule surfaces only
  when the signed APK is installed and cold-started on a device. Keep
  `app/build/outputs/mapping/release/mapping.txt` for every APK handed over, or
  release crash traces cannot be deobfuscated.

A full
LLM success run requires configuring at least one provider and mapping both
EDITOR and DRAFTER roles in Settings. Without an API key, deterministic fetch
and validation still run, while the editorial branch fails closed by design.

### Editorial reference contract

Editorial models never echo article URLs. Every article handed to a model
carries a short id (`a1`, `a2`, …), and the model answers with `ref` /
`also_refs` / `refs`; `EditorialRefs` resolves those ids back to the
authoritative links, so `part1_plan.json`, `part2_draft.json`, and the periodic
digest stay link-keyed on disk. Copying an 80-character URL verbatim is a task
cheap models fail at — a two-character id is not — and a rewritten link now has
no path through the contract at all.

### Providers

Settings and onboarding expose three provider types:

- **OpenRouter** — first-class. Prefills `https://openrouter.ai/api/v1`,
  always sends `HTTP-Referer` / `X-Title` / `X-OpenRouter-Title`, and
  defaults routing to `sort=throughput` plus `require_parameters` so cheap
  models land on backends that actually support structured output and are
  not the lowest-TPS replica. Model ids need the org prefix
  (`anthropic/claude-sonnet-4`, `openai/gpt-4o-mini`). Older installs that
  saved OpenRouter as a generic OpenAI-compatible URL are rewritten to this
  type on load.
- **OpenAI** — the official API or any other OpenAI-compatible endpoint
  (DeepSeek, Kimi, …). OpenRouter routing fields are never sent.
- **Anthropic** — the official Messages API.

### OpenRouter routing

The OpenRouter provider form exposes a provider `sort` (`throughput`
directly targets the low tokens-per-second routing that makes cheap models
time out), a model fallback list, and `require_parameters` (route only to
providers that really support `response_format`). These fields are omitted
entirely for OpenAI and Anthropic, whose official APIs reject unknown
top-level keys.

Role token caps default to 65536 for both EDITOR and DRAFTER — the top of the
allowed range, because truncation is a hard failure with no retry and the caps
are estimated generously. Models whose own completion cap is lower may reject
or truncate the request; lower the cap in Settings. Devices that already saved
a different value keep it; change it in Settings.
