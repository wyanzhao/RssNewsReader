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
  validator imposed a hard floor. The user subsequently authorized the explicit
  exclusion-accounting policy documented in 0.7.4 below.

## 0.7.4 user-authorized shortlist shortfall policy

- The user explicitly chose fewer than 40 candidates when every excluded article
  is explained and checked against the source pool. Below the normal target,
  selected and excluded references must completely cover the authoritative pool.
- Foreign references, duplicate/overlapping membership, omitted articles without
  reasons, blank/oversized/URL-bearing reasons and an empty selected list reject
  the draft. The normal maximum still applies. No deterministic news scoring was
  introduced; substantive editorial judgment remains with the model.
- Persisted shortlist artifacts and recovery checkpoints include link-keyed
  exclusions; recovery revalidates coverage and reason constraints. A fingerprint
  revision prevents older checkpoints from bypassing the new contract.
- Diagnostics presents each accepted reason with its authoritative article link;
  malformed artifacts show an explicit read error. Structural/source validation
  does not establish that the editorial reason itself is correct.
- Regression cases include 34 selected out of 47 with all 13 exclusions, missing
  exclusions, foreign/duplicate references, invalid reasons, empty selection,
  checkpoint reuse and accepted/corrupt diagnostic-artifact loading.
- Full JVM suite: 439 executions, zero failures/errors/skips; lint and screenshot
  verification passed. Version gate passed against `d8592c5`. Signed 0.7.4 (23)
  installed preserving data; mapping archived beside APK. SHA-256:
  `85ce665ba7ae05c9af8197426a7afca480ace9f252251164f439f2e731c7e1d8`.
- S25 visibly displayed the actual model-shortlisting phase during live generation.
  The live 0.7.4 run succeeded in 39 seconds: 47 source articles, 21 shortlisted,
  26 excluded with individual reasons, final Top 18. The exported ZIP was audited:
  selected/excluded links are disjoint, unique and exactly cover the raw pool;
  every exclusion has a nonblank bounded reason. This verifies the below-target
  path on device, but is not a blinded editorial-quality comparison.

## B1 source-audit groundwork

- Added `tools/editorial_review.py`: prepares one-arm source audits or two-arm
  blinded-label packets from diagnostic ZIPs. Rejects differing source pools,
  non-feedback config/brief drift, history drift, and shared cache drift. Source
  filenames and per-arm feedback are absent from the reviewer evidence; unblinding
  is separate. Runtime provenance still requires independent verification.
- Three offline tooling tests passed. Generated the current 47-article audit packet
  locally and recorded bounded findings in `EDITORIAL_QUALITY_REVIEW.md`.
- Current baseline has no explicit editorial feedback. No preference-improvement
  or completed blind-comparison claim is made. Candidate generation and blind
  judgments remain open.

## B1 controlled execution (0.8.0)

- User selected: prioritize AI infrastructure, chips and compilers; reduce consumer
  electronics reviews. This is the experiment preference, not an implicit change
  to saved app preferences.
- Comparison validates the frozen source pool, regenerates baseline/candidate,
  disables cached summaries in both, pins recent-event history, configured
  provider/model and prompt hashes, and reviews both reports. The runner has no
  report, cache or seen-ledger write port. Artifacts live under the source run's
  `comparisons/<id>/` subtree; complete status requires both reviewed results.
- Foreground work shares the report's unique queue, has an eight-minute timeout,
  explicit failure/cancellation status and no automatic paid rerun. Diagnostics
  exposes preference entry, completion status and export. Incomplete experiments
  are rejected by the offline review tool.
- Production snapshot feeds are serialized as an array. A persistence regression
  verifies that decoding, separate arm writes, source preservation and rejection
  of corrupt snapshots and escaping artifact paths.
- Full JVM execution count: 443, zero failures/errors/skips; lint and screenshot
  verification passed. Four offline review-tool tests passed. Version gate against
  `c9a69ae` passed; signed APK read-back is 0.8.0 (24), v2/v3 verified and installed
  on S25 with `adb install -r`. APK SHA-256:
  `14d6f9370af0419004a76298aecd2512c2780777852d54622570ff4a37135d5c`.
- S25 experiment `comparison-8cd52877-75aa-4882-aac5-7efb1e4f60fa` completed:
  baseline 32 selected/15 excluded/18 events; preference 19/28/13. Both arms had
  zero cache hits and two successful physical calls. Reported charges were
  USD 0.0067850 and 0.00453310. Original exported artifacts stayed byte-identical.
  The offline paired packet passed; independent blind judgments remain pending.
- Source inspection found ranking inconsistency and an unsupported completed-
  researcher claim in the preference arm's truncated evidence. Findings and next
  correction are in `EDITORIAL_QUALITY_REVIEW.md`; preference quality is not closed.
- An additional failure test passed: second-arm failure propagates and cannot
  return a completed pair even after the baseline report was written.

## B1 final-selection correction (0.8.1)

- Final plan drafts now carry `excluded` with source IDs and reasons; deterministic
  resolution persists canonical links. Every shortlisted article must occur once
  across primary/merged/excluded references. Missing reasons, overlap and foreign
  sources reject the plan, including recovered checkpoints. Older persisted plans
  remain readable; new execution uses a changed fingerprint and current gate.
- Diagnostics exposes final-stage omissions separately from first-stage exclusions.
  Offline review includes merged supporting sources, final plan and exclusions;
  it validates complete final coverage when the new field is present.
- Explicit preferences take precedence over ordinary off-topic business/executive
  news in final ranking. Broad, urgent public events may override only with a
  concrete explanation. Prompt distinguishes truncated text/future targets from
  completed results and requires details to come from primary/merged sources.
  This semantic instruction is not a deterministic fact checker; live evidence
  review remains required.
- Full JVM checks passed 445 executions with zero failures/errors/skips; lint and
  screenshot verification passed without baseline replacement. Five offline
  review-tool tests passed. Version gate against `b328874` passed.
- Signed APK read-back is 0.8.1 (25), v2/v3 verified, installed with `adb install -r`
  preserving S25 data. APK SHA-256:
  `8b0bb25ff520db07131c3a64156877f4a75bf01df2754873ad60c81317456ce4`.
  Mapping archived beside APK locally. This first device trial failed on repeated
  final-plan overlap/omission; eight calls cost USD 0.02917859849. The baseline
  passed after repair, but the candidate exhausted retries, so the paired export
  was correctly refused.
- Retry feedback now identifies missing/duplicate article IDs and includes the
  rejected draft for targeted repair, without silently dropping references or
  inventing reasons. Full checks passed again; focused assertions verify that the
  model receives the missing IDs and prior draft. Revised APK in the same 0.8.1
  iteration has SHA-256
  `ce8bca51c435ebdecd451ae30ca8d67bda88b886cb60c022916f8edce9e1a22b`.
  Installed preserving S25 data. Experiment
  `comparison-50be25f1-951d-4436-add4-82a0c16382c4` completed: baseline 21 events,
  preference 11; both two calls, zero retries. Reported charges USD 0.0055838 and
  0.00502530. All original artifacts unchanged; paired/final coverage audit passed.
- Preference ordering improved in this sample (Kioxia and AI data-center reports
  first/second), but technical company self-reports are still inconsistently
  excluded. Truncated-milestone faithfulness and independent blind evaluation
  remain open; see the source-bounded quality review for the next correction.

## B1 source evidence projection (0.8.2)

- Both prompts distinguish substantive official technical reports/data/releases
  from promotion without new information, applying the same source standard to
  all companies and preserving self-report attribution.
- Before final editorial planning, an explicit trailing-ellipsis excerpt loses
  its last unfinished sentence. `article_text_tail_omitted` makes that projection
  visible; original raw and authority artifacts remain intact. Affected cached
  summaries are withheld, while stable event keys remain available. The persisted
  shortlist context is the exact projected material sent to the model.
- This conservative projection does not detect unmarked truncation or prove
  semantic faithfulness. Forty-five of this 47-article pool's raw excerpts have
  trailing ellipses. The incomplete achieved-goal clause in research acceleration
  is withheld; the complete future aim and RSS summary remain available.
- Regression tests cover the milestone fragment, decimal/version punctuation,
  Chinese ellipses, no-complete-sentence input, intact complete statements,
  provenance flags, cache invalidation, identity preservation and idempotence.
  Full JVM suite passed 447 executions with zero failures/errors/skips; lint,
  screenshot verification and five offline review-tool tests passed. Version gate
  against `0f495f9` passed. Signed 0.8.2 (26) verified v2/v3, installed preserving
  S25 data; mapping archived beside APK. APK SHA-256:
  `4f73a4c57f63866e546853ba1fd0c1743b672f0743a8210ca5102f18a76ce3f9`.
- S25 experiment `comparison-44c08cc3-19f1-4143-9763-afe5f9b6095a` failed in
  baseline planning: overlap, 31 items, then shortfall 6 instead of 5 for 25 items.
  Four measured calls cost USD 0.01583322; candidate never started. No paired
  quality conclusion is available. The bounded retry gate preserved the existing
  report and all original artifacts, verified byte-for-byte.
- Exported model input proves the research achieved-goal fragment was withheld
  and its omission flag recorded. This verifies input containment, not the
  accuracy or relevance of a generated report. B1 source-policy acceptance remains
  open; next work advances full-chain recovery/cost diagnostics without repeatedly
  spending on unchanged model-contract failures.

## A2/B2 recovery-chain accounting (0.8.3)

- Traverses only `recovery.json.source_run_id` ancestry, with missing/corrupt
  provenance, missing runs, date/identity mismatch, cycle and depth guards.
  Valid decimal provider charges from source and resumed runs are summed, including
  fully measured failed attempts. Conflicting duplicate measurements are excluded
  from the known subtotal; missing accounting never becomes zero.
- Running/interrupted or unfinished model stages, measurement/audit-count gaps,
  unavailable charges and absent historical records keep total cost unknown.
  No ancestry is inferred from report dates or cache timestamps.
- Recovery diagnostics shows the chain immediately under the verdict, including
  known subtotal and charge coverage. Copied diagnostics includes the same text;
  ZIP export writes a fresh `recovery_accounting.json` snapshot. This covers the
  explicit recovery chain, not unrelated experiments or all fresh generations.
- Full JVM evidence: 455 executions, zero failures/errors/skips. Lint and screenshot
  verification passed. Screenshot fixture now waits for the visible diagnostics
  empty state at normal font scale; at 200% that content is below the startup
  failure banner and not composed. Existing image baselines were preserved.
- Signed 0.8.3 (27), v2/v3 verified; version gate against `b57d749` passed. Installed
  on S25 with `adb install -r`; mapping archived beside APK. Final APK SHA-256:
  `7bdeca3afc5c5a367b0a130c512cb76a396bb3820297e249a2485e8747aebabf`.
- S25 existing successful recovery `recovery-87219236-162a-4ea9-a384-240d5fd136cb`
  resolves parent `rss-20260907T103301Z-bb002d29-a1`. UI and exported accounting
  both show two runs, 3/4 charged calls, known subtotal USD 0.00923884 and total
  unknown due to cancellation. No new model request was made for this acceptance.
- Remaining A2/B2 scope includes full retry-chain provenance beyond explicit
  recovery, baseline comparisons and the full interruption matrix. B1 quality
  acceptance and A3/B3/C1/C2 remain open.

## B2 editorial interruption matrix (0.8.4)

- Parameterized fault injection now covers eight editorial boundaries using fresh
  engine instances and persisted checkpoint fixtures. The tests require
  cancellation to propagate and only accepted, committed stages to be reused:

| Interruption boundary | New model calls on recovery |
| --- | ---: |
| Shortlist request | 2 |
| Shortlist artifact write | 2 |
| Before shortlist checkpoint commit | 2 |
| After shortlist checkpoint commit | 1 |
| Shortlist context artifact write | 1 |
| Final plan request | 1 |
| Before plan checkpoint commit | 1 |
| After plan checkpoint commit | 0 |

- Tests first exposed two real failures: cancellation during required artifact
  writes was wrapped as ordinary storage failure. `persistArtifact` now rethrows
  CancellationException; real IOException still blocks and retains its cause.
  No partial report or implicit artifact reconstruction was introduced.
- Full checks passed before the additional storage assertion; final pipeline
  suite rerun passed after that test was added. Combined recorded JVM executions:
  464, zero failures/errors/skips. Lint and screenshot verification passed with
  unchanged baselines. Version gate against `a8e990d` passed.
- Signed APK read-back 0.8.4 (28), v2/v3 verified, installed on S25 with retained
  data. Historical recovery and USD 0.00923884 known chain cost remain visible.
  APK SHA-256 `6794fb7b89e385935981192b8bb823d0707180a3b4fcbe68808b9d4ae9cc6d5b`;
  mapping archived beside APK. No paid model requests used for this acceptance.
- These are JVM boundary injections plus an install/read smoke test, not evidence
  of Android process-death/long-standby behavior. Remaining A3/B2 device matrix,
  B1 quality review and B3/C1/C2 product work remain open.

## B3 independent watch preferences (0.9.0)

- Adds separate event/topic watch lists to existing config storage. Event watches
  bind stable event keys to the report date already visible when following;
  topic subscriptions are independent long-lived subject text. Existing article
  feedback remains separate and old configs default to empty watch lists.
- Story view supports follow/unfollow; a first-day event can enter the story view
  through the report menu even without a multi-day badge. Settings manages topic
  text and existing event watches. Updates use atomic DataStore read/modify/write
  and retain unrelated preferences; limits are 20 topics and 20 events.
- Both editor passes receive bounded structured watch preferences as data.
  Controlled comparisons clear watches in both arms along with article feedback
  so stored follows cannot confound the explicit trial preference.
- New persistence/context tests cover concurrent event/topic/feedback changes,
  reopen, independent unfollow, old-config compatibility and authoritative-source
  preservation. Full verification passed: 467 recorded JVM test executions, zero
  failures/errors/skips, lintDebug, Roborazzi verification and release assembly.
- Signed APK read-back 0.9.0 (29), v2/v3 verified; version gate passed against
  c7f477f. SHA-256:
  `890c7915c39fdaba2f9c9f9e3ff99f72d4974231d22ca31a987a027382278069`.
  APK and mapping are archived under `build/acceptance/2026-09-07/release-090/`.
- S25 retained-data upgrade verified first-day event follow, persistence after
  force-stop/relaunch and unfollow. The temporary watch was removed. The topic
  field is visible under Settings > Plan and background; no topics or provider
  settings were changed during device acceptance. Screenshots are stored as
  `s25-090-event-watch.png` and `s25-090-topic-settings.png` in that date folder.
  Topic persistence is covered by automated tests, not a device save experiment.
- This is the durable watch-preference foundation. Source-backed new-development
  assessment, meaningful-change notifications and personalized weekly review are
  still required for B3 completion; no notification behavior is claimed yet.

## B1/B3 published evidence for progress comparison (0.9.1)

- Inspection found that recent-event context discarded the published summary and
  source link after validating the summary. The plan model could see titles and
  event keys but could not compare previously reported facts against current
  material. `recent_top30` now includes exact `summary_zh` and `link` from the
  published snapshot, keeping latest eligible coverage per event and the existing
  prior-date seven-day window, 150-event cap and 400-character summary lint.
- Historical rows remain outside the candidate ID pool. The prompt explicitly
  treats them as previous coverage, not independent verification or permission to
  introduce facts absent from current source material. Missing legacy fields are
  empty and cannot establish that a story has no new developments. The prompt
  also states that the recent window is not complete event history.
- The final shortlist payload retains byte-size warning diagnostics, not a hard
  byte gate. Added summary material is bounded to 60,000 characters; actual
  provider token/charge effects remain to be measured on matched runs.
- Focused tests cover exact source/summary preservation and serialization, latest
  coverage selection, same-day/expired exclusion, polluted/oversized summaries,
  old JSON compatibility, bounded history and prompt wire-field consistency.
- Full checks passed: 471 JVM test executions, no failures/errors/skips, lintDebug,
  Roborazzi verification and release assembly. An existing cancellation test raced
  dispatcher startup against a fixed 100 ms timeout; it now waits for response
  headers before cancelling and retains the one-request/no-retry assertion.
- Version gate passed against fafa066; signed APK read-back 0.9.1 (30), v2/v3
  verified. SHA-256:
  `174d257e49b0c07da6e617f39c10372657d79554f683ee73b24f1e39247e4001`.
  APK and mapping archived under `build/acceptance/2026-09-07/release-091/`.
  S25 upgrade used install -r; package version and existing story display were
  read back successfully. No provider settings were changed or model calls made.
- This supplies a usable comparison baseline; it does not prove semantic novelty
  detection or complete B3 notifications, older watched-event baselines or weekly
  review. Existing published reports are not rewritten or fact-corrected.

## B3 long-lived watched-event comparison (0.9.2)

- Explicit event watches now travel as typed `watched_events` in the frozen
  brief. The engine passes that run's list to the context builder; it does not
  re-read mutable settings or parse prose feedback to choose historical queries.
- Each normalized watch (maximum 20) resolves the latest successful Part 1
  published snapshot strictly before the report date, regardless of the recent
  seven-day window. Room performs a bounded LIMIT 1 query per event; no schema
  migration, article-pool access or cache-derived history is introduced.
- `watched_history` preserves the event key, follow baseline date and latest
  source-linked published summary. Missing or lint-invalid history is explicit
  null, not a claim of no developments. History never joins the candidate ID pool.
- Tests cover old successful coverage, exclusion of failed/same-day/other-event
  reports, invalid summaries, absent history, brief serialization and the actual
  plan-request payload using deterministic model responses. Semantic judgments
  by a real provider and meaningful notification behavior remain unverified.
- Full verification passed: 477 JVM executions, no failures/errors/skips,
  lintDebug, Roborazzi verification and signed release assembly. Version gate
  passed against 94ab6a0. APK read-back 0.9.2 (31), v2/v3 verified; SHA-256:
  `a2f098a10ffc30db6cc27661f35da38fc537fe26831726b145777147b9a44b8e`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-092/`.
- S25 retained-data upgrade passed; installed version, existing story and follow
  action were observed. No provider configuration changes or real LLM calls.
  Device UI evidence does not substitute for semantic novelty validation.

## B3 explicit source-bound development assessments (0.9.3)

- Selected previously covered events require a structured `development` assessment:
  latest baseline report date, Chinese account of new facts, one current selected
  source reference and a verbatim 10–400-character excerpt from that article's
  provided body or feed summary. Source IDs resolve to unchanged authoritative
  links in the persisted Part 1 plan; history links cannot become evidence refs.
- The runtime checks baseline date, missing/oversized/link-bearing descriptions,
  source membership in the selected event and exact current-source quote matching.
  Known cached event IDs cannot be renamed to bypass comparison. Both fresh
  plans and recovered checkpoints pass these checks. Violations use
  existing bounded contract repair with concrete feedback; no synthesized claims.
- New events and events without usable historical evidence use explicit null.
  A structurally valid assessment remains a model judgment: matching a quotation
  does not prove the claimed implication, semantic novelty or notification value.
- Tests cover ID resolution, unknown IDs, missing/wrong historical comparison,
  fabricated excerpts, unrelated sources, merged-source evidence, legacy missing
  history, schema/prompt field parity and the actual engine request/output path.
- The offline review packet displays each assessment as a model claim beside its
  source quotation. Six review-tool tests pass.
- Full checks passed: 484 JVM executions with no failures/errors/skips,
  lintDebug, Roborazzi verification and signed release assembly. Version gate
  passed against 652569b. APK read-back 0.9.3 (32), v2/v3 verified; SHA-256:
  `fd56e06d65cd85a0d3b3bfecdfaa84d940fdf132ccf25339001b5179167a82fc`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-093/`.
- S25 retained-data upgrade, version read-back and existing story/follow action
  checks passed. No real model calls or provider-setting changes were made.
- Assessments persist in exported plan artifacts;
  user-facing change presentation, notification deduplication and real-provider
  semantic evaluation still need implementation/acceptance.

## B3 durable evidence and reader disclosure (1.0.0)

- Room 9→10 adds nullable structured development evidence to permanent report
  items. Assembly and publish retain the assessment; old rows remain null. The
  database converter is registered on the database, schema 10 is exported, the
  production migration list and pre-migration backup fuse include v9, and the
  full-chain migration test now targets the shared current version constant.
- State backup export/import includes typed evidence through report items. The
  envelope/future-version guard uses schema version 10; import validates dates,
  description bounds and selected-source membership before mutating settings or
  tables. Reports retain evidence without article-pool or debug-artifact storage.
- Report cards and story history show new facts labeled as AI judgment relative
  to a named report date. Readers can expand the original excerpt and open its
  exact source URL. Old reports are not retroactively assigned novelty claims.
- Audit found failed reports in story queries; history and multi-day counts now
  join only successful reports, matching the editorial comparison baseline.
- Tests cover assembly/publish/backup roundtrip, legacy null migration, full-chain
  device migration, exact source-opening semantics and light/dark story rendering.
  Final checks passed: 490 JVM executions and 31 S25 database/instrumentation
  tests, no failures/errors/skips; lintDebug, Roborazzi verification and release
  assembly passed. Light/dark UI screenshots were visually inspected.
- Version gate passed against 79e8744. Signed APK read-back 1.0.0 (33), v2/v3
  verified; SHA-256:
  `2552ff9a835fd86b6f114ec5ba3fb35a715ece71f1586c8a25f5498892d0dc7d`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-100/`.
- Retained-data S25 upgrade succeeded. The existing story and follow action
  remained visible after Room opened the migrated database; old reports show no
  invented development label. Screenshot: `s25-100-migrated-story.png`. No new
  model call or provider-setting changes were made.
- Notification delivery, real-model novelty quality and personalized weekly review
  remain open. UI screenshots use labeled test fixtures, not real model output.

## B1/A2 real same-pool acceptance on 1.0.0

- S25 experiment `comparison-b77617be-c945-4d83-bbe8-f348c2494691`
  completed default and preference arms on the original 47-article snapshot.
  Full evidence and targeted findings are recorded in `EDITORIAL_QUALITY_REVIEW.md`;
  export and label-hidden review packet are under `build/acceptance/2026-09-07/`.
- Default: 17 events, three measured calls, USD 0.0076426; a duplicate
  selected/excluded source was repaired on the second plan attempt. Preference:
  19 events, two calls, USD 0.00602020; first-attempt acceptance in both stages.
  Total observed provider charge USD 0.01366280. These are one-run measurements,
  not a general efficiency claim.
- Kioxia AI memory and AI data-center reporting move from ranks 9/8 to 1/2.
  Both arms retain OpenAI research acceleration and preserve its goal status,
  addressing the previously observed omission/unsupported achievement claim.
- Targeted source audit still finds two preference-arm fidelity errors: Athlon
  becomes Athron, and GPU power becomes whole-machine power. These are recorded
  as failures, not corrected inside immutable trial outputs. User ranking
  feedback is pending; independent blind quality acceptance remains open.
- Both history inputs are empty and all development fields null, so this trial
  does not establish positive novelty detection or notification quality. It was
  an awake-device comparison, not Doze/process-death acceptance.
- Offline review now checks watched-history equality, shared projected source
  material and complete source accounting when exclusions are present. Eight
  tests and revalidation of the actual pair pass. No Android code/version change
  or GitHub publication was made in this tooling/acceptance iteration.

## B1 source-name spelling and numeric-subject fidelity (1.0.1)

- Part 1 validation now flags a narrow near-spelling case: a two-word capitalized
  phrase in the output is absent from the selected-event material, but a
  unique provided phrase differs by one character within an equal-length word.
  This catches the observed Athron/Athlon Blockchain error without rewriting
  the output. Validation covers both summary and development-description text.
- Case differences, whitespace, length-changing forms and exact alternate names
  present in merged sources are not rejected. Ambiguous near matches are left
  unresolved. This is not general named-entity recognition or semantic proof;
  broader linguistic coverage and possible false positives still need observation.
- The plan prompt explicitly preserves English entity spelling and binds each
  number to its object, metric, unit, range and attribution. GPU power cannot be
  rewritten as whole-system power. Measurement-object semantics remain a model
  responsibility and require real-output review.
- 494 JVM tests, lintDebug, Roborazzi verification and signed release assembly
  passed; version gate passed against d217300. Signed APK read-back 1.0.1 (34),
  v2/v3 verified. SHA-256:
  `a3cc6228bc3f97d830b88791f0c43d50e3b2e18cbbf25aad3d4ddaab48fb70eb`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-101/`.
- Retained-data installation targeted S25 RFCY30B296K explicitly after a second
  device appeared; the other device was untouched. Same-pool real-model
  acceptance failed before plan parsing: the provider reported 65,536 output
  tokens and truncation. Two calls cost USD 0.0376236; no candidate arm ran.
  No semantic improvement is claimed from unit tests or this failed trial.
  Existing direct role-cap behavior is intentional and remains unchanged;
  lower output limits are not assumed to fix this observed truncation.

## 1.0.2 comparison truncation diagnostics

- Comparison cards now disclose the measured arm, operation and reported output
  count for truncated responses, including JSON-repair truncation. They explain
  that incomplete output is not a comparison result and identical-cap automatic
  retries were suppressed. No model, key, output-cap or budget setting changes.
- Typed telemetry parsing excludes other experiments and raw provider error text.
  Legacy absent measurements do not become a diagnosis or zero-token count;
  malformed records produce an explicit incomplete-evidence notice. Artifact-read
  cancellation propagates instead of being rendered as unreadable data.
- 499 JVM executions, zero failures/errors/skips; lintDebug, screenshot verification
  and signed release assembly passed. Version gate against 3cf060a passed.
  APK read-back 1.0.2 (35), v2/v3 verified; SHA-256
  `e5f80062b333d66452168808eb46f12000faf6ecdf12a71ff2d1327227c1202b`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-102/`.
- S25 retained-data install succeeded, installed version read back as 1.0.2 (35).
  Historical failed-comparison UI acceptance passed: the actual 1.0.1 trial
  displays default-arm final-plan truncation and 65,536 reported output tokens,
  with the incomplete-result and retry guidance. Screenshot/XML archived beside
  the APK. No new paid trial. The long diagnostic list required substantial
  scrolling; section navigation remains a usability follow-up.

## 1.0.3 watched developments in report notifications

- Successful report notifications prioritize explicit watched events with typed
  source-bound development assessments. This reuses the existing report-ready
  notification/channel/date identity; it does not create additional event alerts.
- Selection requires a Part 1 item, a report date after the watch start, a
  development baseline at or after that start and before the new report, a
  non-empty change/quote, and an evidence link among that event's selected links.
  Repeated mentions with no assessment and topic-only preferences do not qualify.
  At most three developments are previewed; the title reports the total count
  and explicitly identifies the content as an AI assessment.
- One qualifying event opens its story; multiple events open the report. Original
  Top N sharing remains intact. Current watches are read at notification time so
  an unfollow during generation takes effect. Existing permission and channel
  controls still apply.
- Targeted selection and actual notification/PendingIntent tests passed. Full
  regression passed: 504 JVM executions, zero failures/errors/skips, lintDebug
  and screenshot verification. Version gate against 3e8bb81 passed. Signed APK
  read-back 1.0.3 (36), v2/v3 verified, SHA-256
  `1bd12af5efa5ff8e915f54f5bfca7dd4d8c66b08668162584233a129b3de68d2`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-103/`.
  S25 retained-data install and existing story deep-link smoke passed; installed
  version read back 1.0.3 (36). Existing historical summaries were not regenerated
  or claimed factually repaired. This is not real-device positive novelty
  acceptance: the last same-pool trial had no historical baseline.
- The model's source-bound assessment remains a judgment, not semantic proof.
  Same-report event keys are deduplicated; cross-run repeated-change suppression
  beyond the existing report notification identity remains a follow-up.

## 1.0.4 personalized periodic review (local, not installed)

- Weekly/monthly generation now freezes normalized current watch preferences in
  its model input. Explicit event watches receive priority when material exceeds
  the capacity limit; topics guide model editing without being treated as facts.
- The previous newest-row-per-event cut contradicted the prompt's trajectory
  request. Material selection now preserves breadth first, then an earliest and
  preceding distinct article per retained event, with at most three per event
  and the same 120-article total cap. Repeated publication of a single link keeps
  only its latest supplied summary and cannot count as multiple developments.
- Input and rendered output disclose the number of different source articles and
  the number actually provided. The prompt forbids inventing a trajectory from a
  single item, acknowledges omitted intermediate material, and requires citing
  every referenced source. Previously published summaries remain secondary
  editorial material, not independent fact-checking evidence.
- Actual Room repository tests exclude failed and out-of-period reports and
  verify preference normalization/source counts. Selection and rendering tests
  cover crowded-period watch retention, trajectory bounds and duplicate links.
- Full validation: 510 JVM executions, zero failures/errors/skips; lintDebug,
  Roborazzi verification and signed release assembly passed. Version gate against
  ba047a2 passed. APK read-back 1.0.4 (37), v2/v3 verified. SHA-256
  `1a1d168cbab50ef6b86505d77a705dc8959249484ea70020241a85d629098a8c`.
  APK/mapping archived under `build/acceptance/2026-09-07/release-104/`.
- S25 remains on usable 1.0.3 (36). This local iteration was not installed and no
  paid model trial was run. Personalized multi-day content quality and device
  acceptance remain open. Group subsequent reading changes before device handoff.

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

## Next measurement boundary

Recovery ancestry already persists as `recovery.json.source_run_id` in
RunOrchestrator. Full-chain cost work should traverse that explicit artifact,
check cycles/missing ancestors, and aggregate measured attempts with unknown
cancelled calls retained. Do not infer ancestry from dates or cache timestamps.
Explicit recovery-chain accounting is now implemented and verified above; other
retry-chain and full-matrix acceptance remains open.
