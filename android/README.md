# DailyNews Android

Native Kotlin/Jetpack Compose port of DailyNews. It keeps deterministic
validation, artifact schemas, link integrity, summary lint, seen-links, and
editorial cache semantics aligned with the Python reference implementation.

## Modules

- `core:model` — serializable artifact and configuration contracts (pure JVM)
- `core:llm` — provider-neutral LLM API plus OpenAI-compatible and Anthropic clients (pure JVM)
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
Do not select `app/build/outputs/apk/release/app-release-unsigned.apk` for local
installation: as its name states, that release artifact contains no certificate.

A full
LLM success run requires configuring at least one provider and mapping both
EDITOR and DRAFTER roles in Settings. Without an API key, deterministic fetch
and validation still run, while the editorial branch fails closed by design.
