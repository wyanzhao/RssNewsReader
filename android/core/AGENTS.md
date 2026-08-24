# Android Core Modules Guide (`android/core`)

> The root `AGENTS.md` is the global authority for the repo contract. This
> file only adds module-local boundaries, contracts, and risks for the four
> `android/core` libraries (`:core:model`, `:core:pipeline`, `:core:llm`,
> `:core:data`). When they disagree, the root file wins.

## Scope And Boundaries

- `:core:model` — pure JVM Kotlin data classes + kotlinx.serialization
  codecs. No business logic, no Android dependencies. The contract surface
  shared with the Python pipeline.
- `:core:pipeline` — the only orchestration layer: `RunOrchestrator` plus
  stage packages (fetch, parse, editorial, context, flow, validate, text).
  Stages communicate only through `ports/` interfaces and `core:model`
  types; they never reference each other directly.
- `:core:llm` — provider abstraction (`OpenAiCompatProvider` for OpenRouter
  and OpenAI-compatible APIs, `AnthropicProvider`), HTTP transport, and
  `StructuredLlm` structured output with auto-fallback. No pipeline
  semantics live here. OpenRouter is a first-class `ProviderType`; routing
  extras and attribution headers are type-gated so official OpenAI /
  Anthropic endpoints never see unknown fields.
- `:core:data` — Room/DataStore/file persistence. Repositories implement
  the ports defined in `:core:pipeline`; storage details never leak above.
- Allowed dependency direction: `pipeline -> model, llm`;
  `data -> pipeline (ports), model`; `llm -> model`. No cycles, no upward
  dependencies. Only `app` may assemble these modules together.

## Key Contracts

- **Artifact parity with Python.** `core:model` mirrors the JSON artifacts
  the Python pipeline writes (`raw.json`, `validation.json`,
  `llm_context.json`, part1/part2 handoffs). The global Json codec is
  centralized in `ArtifactJson.kt` (`ignoreUnknownKeys = true` for additive
  compatibility). Field renames or type changes are cross-implementation
  breaking changes and must stay consistent with the repo-root contract
  surface and the frozen fixtures under pipeline tests.
- **Port boundaries.** External effects enter the pipeline only through
  ports (`FeedSource`, `FetchPort`, `ArtifactSink`, `ReportSink`,
  `SeenLinksStore`, `EditorialCacheStore`, ...). `core:data` repositories
  are the intended production implementations.
- **Run result tri-state.** `RunExecutionResult` has exactly
  Success / ExpectedBlock / Failed semantics with a bounded two-attempt
  retry, mirroring the Python skill's branch flow. Do not add a fourth
  state or silent fallback paths.
- **Room migrations.** `DailyNewsDatabase` is versioned (currently 9) with
  explicit migrations and exported schema in `schemas/`; schema changes
  always ship a migration. Test fixtures are synced from a real run dir via
  the `syncMigrationGuards` Gradle task.
  A version bump touches eight places, and none of the last four fail at
  compile time: `version =` (read from `DAILYNEWS_SCHEMA_VERSION`, which is
  also the backup envelope version), the entity list, the `MIGRATION_X_Y` object,
  **the `addMigrations(...)` argument list**, the version-aware backup fuse,
  the committed `schemas/<n>.json`, `StateBackupRepository` export/import of any
  new table in `DeviceStateBackup`, and the migration instrumented tests — whose
  full-chain case always targets the newest version.
- **Structured-output fallback.** Providers that reject structured mode
  surface `StructuredOutputUnsupportedException` with a `fallbackMode`;
  `StructuredLlm` performs JSON-extract/repair retry. Never assume a
  provider supports structured output unconditionally.
- Link-keyed plan rules from the root contract (at most 30 Part 1 items,
  `shortfall` accounting, no article appearing twice, summary lint caps,
  English titles, unchanged links) are enforced on this side by the
  editorial validate/assemble code in `:core:pipeline` — the same rules,
  not approximations.

## Common Risks

- Changing a `core:model` field name "locally" breaks artifact replay and
  Python parity; always update the fixtures and run the pipeline test suite.
- Adding stage-to-stage imports instead of going through ports erodes the
  orchestration boundary; the replay/snapshot tests are the guard.
- Skipping the Room migration when editing an Entity fails at runtime on
  upgrade paths, not at compile time.
- Swallowing `LlmTransportException.retryable` into a generic failure loses
  the retry classification the orchestrator depends on.
- Reading `runs/_cache/`-equivalent state directly from editorial code
  instead of the injected cache hits violates the root contract; cache reuse
  must stay deterministic and injected, never agent-driven.
