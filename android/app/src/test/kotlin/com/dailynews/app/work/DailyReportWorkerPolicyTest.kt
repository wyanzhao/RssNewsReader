package com.dailynews.app.work

import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import com.dailynews.pipeline.orchestrate.RetryKind
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyReportWorkerPolicyTest {
    private val date = LocalDate.parse("2026-08-08")
    private val providerNetworkFailure = RunExecutionResult.Failed(
        date,
        "run-network",
        "editorial",
        "Unable to resolve host api.deepseek.com",
        RetryKind.PROVIDER_NETWORK,
    )

    @Test
    fun scheduledProviderNetworkFailureRetriesOnlyWithinTheThreeAttemptBound() {
        assertTrue(shouldRetryScheduledProviderNetwork(true, 0, providerNetworkFailure))
        assertTrue(shouldRetryScheduledProviderNetwork(true, 1, providerNetworkFailure))
        assertFalse(shouldRetryScheduledProviderNetwork(true, 2, providerNetworkFailure))
    }

    /**
     * The Doze regression this guards: an unconstrained request is picked up by
     * WorkManager's in-process greedy scheduler the moment the alarm fires, so it starts
     * inside the Doze window where the app's UID is firewalled and dies at preflight.
     */
    @Suppress("RestrictedApi")
    @Test
    fun theScheduledTriggerWaitsForAUsableNetworkAndAsksToBeExpedited() {
        val spec = DailyReportWorker.request(scheduled = true).workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.expedited)
        // Out of expedited quota the run must still happen, just without the boost.
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
    }

    /**
     * Only SweepWorker's WorkInfo reaches the UI, so a constrained manual request would sit
     * ENQUEUED and silent while the device is offline — the user tapped a button and would
     * get nothing back. Manual keeps failing fast inside the retry bound instead.
     */
    @Suppress("RestrictedApi")
    @Test
    fun theManualTriggerIsNeitherConstrainedNorExpedited() {
        val spec = DailyReportWorker.request(scheduled = false).workSpec
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertFalse(spec.expedited)
    }

    @Test
    fun manualAndNonNetworkFailuresNeverUseTheScheduledRetryPolicy() {
        assertFalse(shouldRetryScheduledProviderNetwork(false, 0, providerNetworkFailure))
        assertFalse(
            shouldRetryScheduledProviderNetwork(
                true,
                0,
                RunExecutionResult.Failed(date, "run-contract", "editorial_contract", "invalid shortfall"),
            ),
        )
    }

    @Test
    fun compactRunsWhenAnyCountedDeleteIsPositiveAndNotWhenAllAreZero() {
        assertFalse(shouldCompactAfterPrune())
        assertFalse(
            shouldCompactAfterPrune(
                articlesDeleted = 0,
                fetchLogsDeleted = 0,
                runArtifactsDeleted = 0,
                runLogsDeleted = 0,
                runsDeleted = 0,
                part2ItemsDeleted = 0,
            ),
        )
        assertTrue(shouldCompactAfterPrune(articlesDeleted = 1))
        assertTrue(shouldCompactAfterPrune(fetchLogsDeleted = 1))
        assertTrue(shouldCompactAfterPrune(runArtifactsDeleted = 1))
        assertTrue(shouldCompactAfterPrune(runLogsDeleted = 1))
        assertTrue(shouldCompactAfterPrune(runsDeleted = 1))
        assertTrue(shouldCompactAfterPrune(part2ItemsDeleted = 1))
    }

    @Test
    fun dailyAndSweepWorkersGateCompactOnTheSharedRule() {
        val daily = java.io.File("src/main/kotlin/com/dailynews/app/work/DailyReportWorker.kt").readText()
        val sweep = java.io.File("src/main/kotlin/com/dailynews/app/work/SweepWorker.kt").readText()
        assertTrue("shouldCompactAfterPrune" in daily)
        assertTrue("shouldCompactAfterPrune" in sweep)
        assertFalse("part2ItemsDeleted ?: 0) > 0" in daily)
    }
}
