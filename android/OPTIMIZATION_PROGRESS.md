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
- After the user configured the model, a real run collected and validated 37
  articles but failed its first shortlist request with HTTP 401 (`Missing
  Authentication header`). The independent in-app connection test also returned
  401. The configured endpoint was the standard OpenRouter endpoint. Request
  construction and the local HTTP contract test verify Bearer authentication;
  they do not prove what reached the remote server on that device. The user has
  been asked to re-save the key in-app. No secret was requested in chat.
- Consequently, successful live generation, standby continuation, network
  recovery and process-interruption acceptance remain unverified. Returning to
  the app did preserve the failed run and its diagnostic evidence.
- The complete measurement and immediate-result UI implementation passed 407 JVM
  test executions, lint and screenshot verification. The signed candidate also
  passed signature and version gates. The configured S25 has not yet been
  upgraded to that candidate, to avoid interrupting in-progress key entry.

## Remaining authorized scope

1. Complete run-baseline acceptance, including explicit queued/network/model/
   review state handling and first-pass/cost comparisons over real successful
   reports; do not infer effectiveness from the existence of instrumentation.
2. Validate standby, network recovery, cancellation and process termination on
   the configured release device, restoring any temporary device settings.
3. Compare preference-aware editing on identical article pools with blinded
   relevance, no-progress duplication and missed-important-event judgments.
4. Implement verified stage resume. Recovery must reuse only accepted artifacts,
   bind inputs/configuration/prompt/schema/provider/model versions, invalidate on
   drift, retain bounded retry budgets and publication gates, and revalidate
   resumed artifacts. An exact-input cache alone is not a complete interrupted-
   run recovery workflow: the original input snapshot needs an explicit recovery
   path, with a separate fresh-generation path when the article pool changes.
5. Separate event-following from long-lived topic subscriptions; identify actual
   developments with source evidence, meaningful notifications and weekly review.
6. Add searchable favorite tags/notes, reading position, font/spacing controls,
   mixed Chinese/English retrieval, and explicit on-demand full-text persistence.
7. Measure large-pool search/scroll, cold startup, database size and model
   latency/truncation/retry/cost; optimize measured bottlenecks and audit the final
   signed deliverable. Never replace this full scope with only completed items.
