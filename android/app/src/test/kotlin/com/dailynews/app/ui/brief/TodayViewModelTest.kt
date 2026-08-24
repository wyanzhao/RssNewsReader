package com.dailynews.app.ui.brief

import com.dailynews.data.db.ReportSummary
import com.dailynews.data.db.RunSummary
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun latestTodayFailureDoesNotBecomeTheRunForADifferentDisplayedDate() {
        val todayFailed = run("today-fail", "2026-08-23", "FAILED", "2026-08-23T10:05:00Z")
        val todayRunning = run("today-run", "2026-08-23", "RUNNING", "2026-08-23T10:00:00Z")
        val pastSuccess = run("past-ok", "2026-08-20", "SUCCESS", "2026-08-20T10:00:00Z")
        val pastFailed = run("past-fail", "2026-08-19", "FAILED", "2026-08-19T10:00:00Z")
        // Newest-first, the order observeRecent emits.
        val runs = listOf(todayFailed, todayRunning, pastSuccess, pastFailed)

        assertEquals("past-ok", runForDisplayedDate(runs, "2026-08-20")?.runId)
        assertEquals("past-fail", runForDisplayedDate(runs, "2026-08-19")?.runId)
        assertEquals("today-fail", runForDisplayedDate(runs, "2026-08-23")?.runId)
        assertNull(runForDisplayedDate(runs, "2026-08-01"))
    }

    private fun run(id: String, date: String, status: String, started: String) = RunSummary(
        runId = id,
        reportDate = date,
        status = status,
        classification = status,
        validatorExitCode = if (status == "SUCCESS") 0 else 40,
        attempt = 1,
        startedAtUtc = started,
        finishedAtUtc = if (status == "RUNNING") null else started,
    )
}
