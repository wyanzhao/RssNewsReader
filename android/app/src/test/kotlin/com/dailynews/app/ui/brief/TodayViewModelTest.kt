package com.dailynews.app.ui.brief

import com.dailynews.data.db.ReportSummary
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
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
}
