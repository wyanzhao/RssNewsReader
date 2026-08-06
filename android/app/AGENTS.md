# Android App Module Guide (`android/app`)

> The root `AGENTS.md` is the global authority for the repo contract. This
> file only adds module-local boundaries, contracts, and risks for the
> Android application shell. When they disagree, the root file wins.

## Scope And Boundaries

- `:app` is the application shell and the only dependency-assembly point:
  Application/Activity startup, `AppContainer`, Compose navigation host,
  WorkManager/Glance background wiring.
- It owns **assembly and presentation only**. Pipeline stages, parsing,
  validation, editorial logic, LLM protocol handling, and persistence all
  live in `android/core/*` and must not be reimplemented here.
- Allowed dependency direction: `app -> core:pipeline / core:data /
  core:llm / core:model`. Core modules must never depend on `app`.

## Key Contracts

- `AppContainer` is the single composition root. Every Repository,
  OkHttp client, `LlmProvider`, pipeline step, `EditorialEngine`, and
  `RunOrchestrator` instance is created there; screens and workers receive
  dependencies through it instead of constructing their own.
- Build-time asset sync: the Gradle task `syncSeedFeeds` copies the repo
  root `feeds.json` into `src/main/assets`. The runtime seeds feeds from
  that copy, never from the repo path directly.
- Report shape divergence (deliberate, see root `AGENTS.md`): the Android
  app produces **Part 1 only** (Top N digest) plus a native RSS reader over
  the full article pool. Do not re-align it with the Python Part 1 + Part 2
  shape.
- Background scheduling is split across `work/` (ReportScheduler,
  DailyReportWorker, SweepWorker, AlarmReceiver), `notify/`
  (NotificationHelper), and `widget/` (Glance DailyNewsWidget). All of them
  must be wired through `AppContainer`; none may hold global mutable state.
- UI pages are mounted via `ui/AppNavHost.kt` + `ViewModelFactory.kt` under
  the NavigationSuiteScaffold; theming lives in `ui/theme/`.
- Tests: `test/` uses Robolectric + Roborazzi (screenshot + semantic
  assertions); `androidTest/` uses Espresso + WorkTesting.

## Common Risks

- Editing the repo root `feeds.json` without re-running `syncSeedFeeds`
  (or a Gradle build that triggers it) silently ships stale seed data.
- Constructing pipeline/LLM objects inside a ViewModel, Worker, or
  Activity instead of `AppContainer` forks the dependency graph and breaks
  diagnostics/replay assumptions.
- Notification-channel setup and interrupted-run recovery happen in
  `DailyNewsApplication.onCreate`; changes there can break both the worker
  path and the widget path at once. Test both after touching startup.
- Editor prompt assets live in `src/main/assets/prompts`; they are runtime
  inputs to the editorial engine. Treat changes there as behaviour changes,
  not resource edits.
- Adding a new screen requires touching AppNavHost routes, ViewModelFactory,
  and navigation-suite items together — a partial change compiles but fails
  at navigation time.

- **Prompt assets are a contract surface.** `assets/prompts/*.md` names wire
  fields and numbers as bare literals, so a renamed `@SerialName` or a changed
  Kotlin constant fails silently — the model just receives a description of a
  structure that no longer exists. `AssetPromptContractTest` locks both
  directions: every placeholder must render away, and every `@SerialName` the
  prompt must mention is read from the serializer descriptor rather than
  written out by hand. Prompt edits and the Kotlin change ship in one commit.
- **Screenshot baselines must be deterministic.** Anything that puts
  wall-clock time or asynchronous startup work on a render path needs a test
  injection point alongside it (`DailyNewsApp(clock=)`, `ReaderContent(now=)`,
  explicit `seedIfEmpty()`). A baseline that turns red on its own every day
  degrades the whole screenshot gate into noise.
