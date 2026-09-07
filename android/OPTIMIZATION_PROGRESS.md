# Android optimization progress

This is the durable implementation/evidence record for the authorized September 7
roadmap. `TASKS.md` retains the local execution checklist. No GitHub publication
has been requested.

## Completed implementation

- Article-menu editorial feedback: valuable, reduce topic, reduce source,
  repetitive coverage, and follow-up interest; edit/undo and visible persistence
  errors. Atomic updates preserve concurrent settings changes. Feedback reaches
  both shortlist and plan prompts, with the existing title/link/summary contracts.
- Monotonic stage timings, including model operations and their internal retries,
  in diagnostics and export. Timings remain absent for historical runs and fetch
  failures occurring before a run ID exists.
- Physical model-call measurements distinguish editor rework from transport/JSON
  retries. A successful JSON response does not imply a published report. Missing
  measurements keep first-pass status unknown.
- OpenRouter account-charge reporting from `usage.cost`, using decimal arithmetic.
  Accounting coverage is explicit; unknown responses and other providers never
  become zero-cost calls. BYOK upstream charges and credit purchase fees are not
  invented. The existing provider audit remains the call-count authority.
- Model-service save/test results surface immediately, even while the inline
  result is outside the current viewport.

## Verification and device evidence

- Feedback/timing milestone committed locally as `89a2432`; JVM/Compose checks,
  lint, screenshot verification and the Python contract regression passed.
- Samsung S25 SM-S931U1 / Android 16: data/migration suite passed 30 cases;
  application instrumentation had no failures and retained three pre-existing
  skipped Activity-recreation cases. Instrumentation was performed before the
  user configured the release app; do not run uninstalling connected suites
  against the configured device.
- The feedback/timing signed APK was verified with v2/v3 signatures, installed
  using `adb install -r`, and started successfully. Existing application data was
  not cleared.
- The first configured request and connection probe returned HTTP 401. After
  the user re-saved the key, real generation on 0.6.1 succeeded while the app was
  in the background: 39 articles, Top 19, two model calls, no request/contract
  retry, provider-reported cost 0.00501725 USD. This is one observed run, not a
  quality, long-standby or performance benchmark.
- Stage recovery is implemented: frozen raw/config/feed snapshots; validated
  shortlist/plan checkpoints; checksum and per-stage input/prompt/schema/
  configured-provider/model/app-build binding; explicit diagnostic recovery;
  fresh validation/audit/review; and a stop-generation action.
- S25 cancellation test stopped a run after its shortlist was accepted (42
  articles, source duration 19 seconds). Recovery visibly loaded and revalidated
  that shortlist and executed only the missing plan stage.
- That test exposed a pre-existing same-day continuity error: cache updates from
  the same day's earlier report suppressed its regeneration, producing Top 0.
  Fixed continuity to read actual successful report dates strictly before the
  target date. Added empty-digest publication guards in both orchestrator and
  repository, preserving existing reports. Regression tests cover real report
  dates versus UTC cache timestamps, failed/same-day exclusion, and rejected
  empty replacement. This finding was not treated as a successful recovery.
- After the fix, S25 recovered the same interrupted source to a valid Top 30 in
  28 seconds, with one new model call, no request/contract retry, and reported
  incremental cost 0.0065095 USD. The source's cancelled request has unknown
  accounting; this is not a claim about full-chain cost or percentage savings.
- Current full JVM execution count: 417, zero failures/errors/skips. Lint and
  screenshot verification passed; baselines were not replaced. Signed release
  candidate 0.7.0 (19) was verified and installed preserving app data.
- Current 0.7.0 data/migration instrumentation rerun passed all 30 tests on S25.
  Updated the legacy lazy-Part-2 fixture to include its published Part 1 and
  verify that generating Part 2 preserves it.
- Local device evidence is under `android/build/acceptance/2026-09-07/` (ignored
  build output). Screen-off/Doze, network recovery and abrupt process-kill
  acceptance still remain; only ordinary background execution and explicit
  cancellation/recovery have been observed so far.

## 0.7.1 device follow-up

- ADB force-stop during generation, followed by relaunch, marked the original
  run INTERRUPTED (20 seconds). WorkManager automatically reran the manual work;
  that new run succeeded in 48 seconds. The screen was off during part of this
  run and the foreground service remained present. USB power was connected and
  deviceidle remained ACTIVE, so this is not a deep-Doze or long-standby result.
- The test exposed a silent KEEP collision: a recovery request submitted while
  the automatic rerun was active did not start. This was not checkpoint recovery.
- Recovery submission now awaits the enqueue transaction and checks its exact
  request ID; an ignored request reports that generation is already running or
  queued. Submission failures are visible; cancellation still propagates.
- On signed 0.7.1 (20), S25 confirmed the collision message during an active run;
  the test generation was then cancelled through the app. The saved Top 30 was
  present after the in-place upgrade. App JVM suite: 139 executions, no failures,
  errors or skips; lint and screenshot verification passed. Version gate passed
  against `def3f47`; signed APK verified and installed with data preserved.
- APK SHA-256: `4568641b226745219928261552d0957886df0c8f99e9911a780a5308eef20eee`.
  Mapping is archived beside the APK. No GitHub publication.

## 0.7.2 queued-generation visibility

- The brief screen now observes DailyReportWorker WorkInfo separately from RSS
  sweeps. Queued, retrying and pre-run preparation remain visible before a run
  record exists, with a stop action; completed history does not stay active.
- Recovery requests carry their explicit report date; ordinary work follows the
  execution day. Historical screens do not inherit today's active generation.
- Added state/date/precedence regression cases and a request-tag policy check.
  The full app suite (144 executions), lint and screenshots passed; the final
  worker policy suite passed seven cases. A pre-existing diagnostics test cleanup
  race was fixed by cancelling and joining ViewModel/collector jobs before
  resetting Dispatchers.Main.
- S25 offline test displayed the retry queue while retaining the saved Top 30.
  Wi-Fi and mobile data were both restored to their original enabled settings.
  The same task subsequently resumed automatically and published successfully
  (72-second run duration, excluding the earlier queue wait).
  The test covers preflight disconnection, not a mid-request transport failure.
- Signed 0.7.2 (21) installed preserving app data; version gate against `ecff8bc`
  passed. APK SHA-256:
  `817f72e739da7749ed5a260f2e6e5b52d45f2216ae8e095268f3ee4392b98d38`.
  Mapping archived beside APK. No GitHub publication.

## 0.7.3 active generation phases

- Added bounded best-effort stage-start events alongside completion timing records.
  The brief screen and diagnostics now identify model shortlisting, event/summary
  drafting, report assembly and publication review using actual active stages.
- Nested model completion returns to the parent editorial phase. Missing, unknown
  or malformed telemetry uses a generic running label rather than claiming a
  model request. Historical terminal status remains authoritative.
- Regression coverage verifies stage-start visibility before completion, nested
  phase transitions and malformed/missing event handling. Telemetry write failures
  still cannot replace an operation's outcome.
- Full JVM suite: 434 executions, zero failures/errors/skips; lint and screenshot
  checks passed. Version gate passed against `380a150`. Signed 0.7.3 (22) installed
  on S25 preserving data; mapping archived beside APK. SHA-256:
  `8a0de4cb3bbd16c0c97999799d17fd0e7057865920ab57d363bdecf4c2f505f4`.

- Forced deep-idle test on S25 observed `mForceIdle=true`, `mState=IDLE` both
  immediately and after 30 seconds. Afterwards `unforce`, battery reset and wake
  restored `mForceIdle=false`, ACTIVE and USB-powered state. After unlock, the
  final run was FAILED at editorial_contract: 47 input articles, shortlist 34,
  required 40–45, three attempts, 41 seconds. This is not successful background
  report acceptance. The prompt excludes noise and duplicate events while the
  validator imposes a hard floor; resolving the quantity/quality conflict requires
  an explicit shortlist policy decision. No constraint has been silently relaxed.

## Remaining authorized scope

1. Complete run-baseline acceptance, including explicit queued/network/model/
   review state handling and first-pass/cost comparisons over real successful
   reports; do not infer effectiveness from the existence of instrumentation.
2. Validate standby, network recovery, cancellation and process termination on
   the configured release device, restoring any temporary device settings.
3. Compare preference-aware editing on identical article pools with blinded
   relevance, no-progress duplication and missed-important-event judgments.
4. Extend stage-recovery acceptance to abrupt process termination and the full
   interruption matrix; implement full-chain accounting without inventing the
   cost of cancelled requests. Frozen-input and fresh-generation paths now exist
   separately; the original snapshot and all publication gates remain required.
5. Separate event-following from long-lived topic subscriptions; identify actual
   developments with source evidence, meaningful notifications and weekly review.
6. Add searchable favorite tags/notes, reading position, font/spacing controls,
   mixed Chinese/English retrieval, and explicit on-demand full-text persistence.
7. Measure large-pool search/scroll, cold startup, database size and model
   latency/truncation/retry/cost; optimize measured bottlenecks and audit the final
   signed deliverable. Never replace this full scope with only completed items.
