package com.dailynews.app.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsAdvisorTest {
    private fun input(
        hasRuns: Boolean = true,
        status: String = "FAILED",
        classification: String = "UNEXPECTED_ERROR",
        stage: String? = null,
        exit: Int = 40,
        warningCount: Int = 0,
        errorFeedCount: Int = 0,
        evidence: String = "",
    ) = DiagnosticsAdviceInput(hasRuns, status, classification, stage, exit, warningCount, errorFeedCount, evidence)

    /**
     * 每一个已登记的 stage 都必须命中一条**专属**规则，而不是落进最后的兜底。
     *
     * `success_branch` 曾经就在兜底里：它包含全部装配/审校契约失败，而兜底给的建议是
     * "未预期错误，重跑一次"——这类失败重跑必然精确复现。stage 表和规则表分处两地，
     * 加了前者忘了后者不会有任何东西变红，除非有这条断言。
     */
    @Test fun everyKnownStageHasADedicatedRule() {
        val fallback = advise(input(stage = "definitely-not-a-registered-stage"))
        val uncovered = KNOWN_DIAGNOSTIC_STAGES.filter { stage ->
            // classification 保持中性，才不会有别的规则先于 stage 规则命中。
            advise(input(stage = stage, classification = "FAILED")) == fallback
        }

        assertEquals(emptyList(), uncovered, "这些 stage 只能拿到兜底建议，等于没有诊断")
    }

    @Test fun r0NoRunsOffersFirstGeneration() {
        val advice = advise(input(hasRuns = false))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
        assertEquals("还没有运行记录", advice.headline)
    }

    @Test fun r1RunningAsksForPatience() {
        val advice = advise(input(status = "RUNNING", classification = "PENDING"))
        assertEquals(DiagnosticsAction.NONE, advice.action)
        assertEquals("正在运行，稍候", advice.headline)
    }

    @Test fun r2InterruptedReruns() {
        val advice = advise(input(classification = "INTERRUPTED"))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
        assertTrue("被系统中断" in advice.headline)
    }

    @Test fun r3SuccessReportsWarningsWithoutActing() {
        val clean = advise(input(status = "SUCCESS", classification = "SUCCESS", exit = 0))
        assertEquals(DiagnosticsAction.NONE, clean.action)
        assertEquals("这次运行正常", clean.headline)

        val noisy = advise(input(status = "SUCCESS", classification = "SUCCESS", exit = 0, warningCount = 3))
        assertEquals(DiagnosticsAction.NONE, noisy.action)
        assertTrue("3 条警告" in noisy.headline)
    }

    @Test fun r4NetworkPreflightReadsAsADeferralNotAFailure() {
        // A foreground probe would come back green and prove nothing, so the advice is
        // "run it now, where the app is not firewalled" — for the new DEFERRED rows and
        // for pre-0.3.3 rows that still carry UNEXPECTED_ERROR with this stage.
        val deferred = advise(input(status = "SKIPPED", classification = "DEFERRED", stage = "network_preflight", exit = 0))
        assertEquals(DiagnosticsAction.RUN_NOW, deferred.action)
        assertTrue("顺延" in deferred.headline)

        val legacy = advise(input(stage = "network_preflight"))
        assertEquals(DiagnosticsAction.RUN_NOW, legacy.action)
        assertEquals(deferred.headline, legacy.headline)
    }

    @Test fun deferredRunsDoNotLabelTheirStageAsAFailure() {
        assertEquals("阶段", stageLabel("DEFERRED"))
        assertEquals("失败阶段", stageLabel("UNEXPECTED_ERROR"))
        assertEquals("失败阶段", stageLabel(null))
    }

    @Test fun r5WatchdogAndStoppedRerun() {
        assertEquals(DiagnosticsAction.RUN_NOW, advise(input(stage = "watchdog")).action)
        assertEquals(DiagnosticsAction.RUN_NOW, advise(input(stage = "stopped")).action)
    }

    @Test fun r6ContextBudgetOpensPipelineSettings() {
        val advice = advise(input(stage = "context_budget"))
        assertEquals(DiagnosticsAction.OPEN_PIPELINE_SETTINGS, advice.action)
    }

    @Test fun r7EditorialStageAndProviderEvidenceOpenProviderSettings() {
        assertEquals(DiagnosticsAction.OPEN_PROVIDER_SETTINGS, advise(input(stage = "editorial")).action)
        assertEquals(DiagnosticsAction.OPEN_PROVIDER_SETTINGS, advise(input(evidence = "HTTP 401 Unauthorized")).action)
        assertEquals(DiagnosticsAction.OPEN_PROVIDER_SETTINGS, advise(input(evidence = "invalid api_key supplied")).action)
    }

    @Test fun r8AuditAndReviewFailuresExportArtifacts() {
        assertEquals(DiagnosticsAction.EXPORT_ZIP, advise(input(stage = "editorial_contract")).action)
        assertEquals(DiagnosticsAction.EXPORT_ZIP, advise(input(stage = "artifact_audit")).action)
        assertEquals(DiagnosticsAction.EXPORT_ZIP, advise(input(stage = "review")).action)
    }

    @Test fun r9NetworkEvidenceProbes() {
        val advice = advise(input(evidence = "fetch: socket timeout after 30s"))
        assertEquals(DiagnosticsAction.RUN_PROBE, advice.action)
    }

    @Test fun r10UnexpectedErrorFallbackReruns() {
        val advice = advise(input(evidence = "weird local crash"))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
    }

    @Test fun r11ZeroArticlesIsNotAFault() {
        val advice = advise(input(classification = "EXPECTED_BLOCK", exit = 30))
        assertEquals(DiagnosticsAction.NONE, advice.action)
        assertTrue("不是故障" in advice.headline)
    }

    @Test fun r12ErrorFeedsOpenFeedsScreen() {
        val advice = advise(input(classification = "EXPECTED_BLOCK", exit = 20, errorFeedCount = 2))
        assertEquals(DiagnosticsAction.OPEN_FEEDS, advice.action)
        assertTrue("2 个订阅源" in advice.headline)
    }

    @Test fun r13DamagedInputReruns() {
        val advice = advise(input(classification = "EXPECTED_BLOCK", exit = 10))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
    }

    @Test fun r14ExpectedBlockFallbackIsNotAFault() {
        val advice = advise(input(classification = "EXPECTED_BLOCK", exit = 20, errorFeedCount = 0))
        assertEquals(DiagnosticsAction.NONE, advice.action)
        assertTrue("按规则" in advice.headline)
    }

    @Test fun editorialNetworkEvidenceBeatsGenericProviderRule() {
        val advice = advise(input(stage = "editorial", evidence = "HTTP 429 too many requests"))
        assertEquals(DiagnosticsAction.RUN_PROBE, advice.action)
    }

    @Test fun AndroidUnknownHostEvidenceRoutesEditorialFailureToProbe() {
        listOf(
            "Unable to resolve host api.deepseek.com",
            "No address associated with hostname",
            "java.net.UnknownHostException: api.deepseek.com",
        ).forEach { evidence ->
            assertEquals(
                DiagnosticsAction.RUN_PROBE,
                advise(input(stage = "editorial", evidence = evidence)).action,
                evidence,
            )
        }
    }

    @Test fun watchdogStageBeatsNetworkProbeRule() {
        val advice = advise(input(stage = "watchdog", evidence = "fetch: timeout while enriching"))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
    }

    @Test fun interruptedBeatsEveryLaterRule() {
        val advice = advise(input(classification = "INTERRUPTED", stage = "editorial", evidence = "HTTP 401"))
        assertEquals(DiagnosticsAction.RUN_NOW, advice.action)
        assertTrue("被系统中断" in advice.headline)
    }

    @Test fun stageIsOnlyAcceptedForKnownPrefixedReasons() {
        assertEquals("fetch", stageFrom(listOf("fetch: connection reset")))
        assertNull(stageFrom(listOf("process interrupted before completion")))
        assertNull(stageFrom(listOf("alien_stage: unknown prefix")))
        assertNull(stageFrom(emptyList()))
    }

    @Test fun evidenceCollectsReasonsWarningsAndWarnErrorLogs() {
        val logs = listOf(
            com.dailynews.data.db.RunLogEntity(1, "run", "fetch", "INFO", "ok", "2026-08-04T00:00:00Z"),
            com.dailynews.data.db.RunLogEntity(2, "run", "fetch", "WARN", "slow feed", "2026-08-04T00:00:01Z"),
            com.dailynews.data.db.RunLogEntity(3, "run", "editorial", "ERROR", "HTTP 401", "2026-08-04T00:00:02Z"),
        )
        val evidence = evidenceFor(listOf("editorial: HTTP 401"), listOf("1 failed feed(s): A"), logs)
        assertTrue("editorial: HTTP 401" in evidence)
        assertTrue("1 failed feed(s): A" in evidence)
        assertTrue("fetch: slow feed" in evidence)
        assertFalse("ok" in evidence.replace("slow", ""))
    }
}
