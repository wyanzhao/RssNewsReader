package com.dailynews.app.ui

import androidx.work.WorkInfo
import com.dailynews.app.ui.brief.generationProgressFor
import java.util.UUID
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GenerationProgressTest {
    private fun work(state: WorkInfo.State, tag: String = "report-current-day", attempts: Int = 0) =
        WorkInfo(UUID.randomUUID(), state, setOf(tag), runAttemptCount = attempts)
    private fun progress(vararg work: WorkInfo, date: String = "2026-09-07") =
        generationProgressFor(work.toList(), date, "2026-09-07")

    @Test fun queuedTaskIsVisibleBeforeAnyRunExistsAndDoesNotClaimNetworkFailure() {
        val p = progress(work(WorkInfo.State.ENQUEUED))
        assertTrue(p.active)
        assertTrue(p.queued)
        assertTrue("系统调度" in p.label)
        assertFalse("断网" in p.label)
    }
    @Test fun retryQueueIsDistinguishedFromInitialQueue() {
        assertTrue("等待重试" in progress(work(WorkInfo.State.ENQUEUED, attempts = 1)).label)
    }
    @Test fun runningWinsOverOldQueueButFinishedHistoryDoesNotRemainActive() {
        assertFalse(progress(work(WorkInfo.State.ENQUEUED), work(WorkInfo.State.RUNNING)).queued)
        assertFalse(progress(work(WorkInfo.State.SUCCEEDED), work(WorkInfo.State.CANCELLED)).active)
    }
    @Test fun recoveryDatesStayOnTheirOwnDayAndOrdinaryWorkStaysOnToday() {
        val recovery = work(WorkInfo.State.RUNNING, "report-date:2026-09-06")
        assertFalse(progress(recovery).active)
        assertTrue(progress(recovery, date = "2026-09-06").active)
        assertFalse(progress(work(WorkInfo.State.RUNNING), date = "2026-09-06").active)
    }
    @Test fun legacyUntaggedRequestsAreVisibleOnlyOnToday() {
        assertTrue(progress(work(WorkInfo.State.BLOCKED, "legacy")).active)
        assertFalse(progress(work(WorkInfo.State.BLOCKED, "legacy"), date = "2026-09-06").active)
    }
}
