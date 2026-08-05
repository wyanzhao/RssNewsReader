package com.dailynews.app.ui.today

import androidx.work.WorkInfo
import com.dailynews.data.db.ReportSummary
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayViewModelTest {
    private val now = ZonedDateTime.of(2026, 8, 4, 10, 1, 0, 0, ZoneId.of("America/Los_Angeles"))

    @Test
    fun missedRunAppearsAtAndAfterSchedule() {
        assertTrue(isMissedToday(emptyList(), "10:00", now))
        assertFalse(isMissedToday(emptyList(), "10:02", now))
    }

    @Test
    fun successfulTodayReportSuppressesMakeupPrompt() {
        val reports = listOf(ReportSummary("2026-08-04", "SUCCESS", null, "2026-08-04T17:00:00Z", "2026-08-04T17:00:00Z"))
        assertFalse(isMissedToday(reports, "10:00", now))
    }

    @Test
    fun sweepProgressTracksTheActualWorkerLifetime() {
        val queued = sweepProgressFor(listOf(WorkInfo.State.ENQUEUED))
        val running = sweepProgressFor(listOf(WorkInfo.State.RUNNING))
        val finished = sweepProgressFor(listOf(WorkInfo.State.SUCCEEDED))

        assertTrue(queued.active)
        assertTrue("等待" in queued.label)
        assertTrue(running.active)
        assertTrue("正在抓取" in running.label)
        assertEquals(SweepUiProgress(), finished)
    }
}
