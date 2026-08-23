package com.dailynews.app.ui.brief

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Date stepping is a pure function, JVM-testable the same way as ReaderFilters. */
class BriefDateNavigationTest {
    private val available = listOf("2026-08-05", "2026-08-03", "2026-08-02", "2026-07-30")

    @Test
    fun stepsOnlyBetweenDatesThatActuallyHaveReports() {
        // Gap on 8-04: stepping back from 8-05 must land on 8-03, not the empty 8-04.
        assertEquals("2026-08-03", previousReportDate(available, "2026-08-05"))
        assertEquals("2026-08-05", nextReportDate(available, "2026-08-03"))
        // 8-01 has no report either: skip it and land on 7-30.
        assertEquals("2026-07-30", previousReportDate(available, "2026-08-02"))
    }

    @Test
    fun stepsFromDatesNotInTheList() {
        // When today has no report, effectiveDate is not in available, but stepping must still work.
        assertEquals("2026-08-05", previousReportDate(available, "2026-08-06"))
        assertNull(nextReportDate(available, "2026-08-06"))
    }

    @Test
    fun stopsAtBothEnds() {
        assertNull(previousReportDate(available, "2026-07-30"))
        assertNull(nextReportDate(available, "2026-08-05"))
        assertNull(previousReportDate(emptyList(), "2026-08-05"))
    }

    @Test
    fun labelMarksTodayAndCarriesWeekday() {
        assertEquals("2026-08-05 星期三 · 今天", briefDateLabel("2026-08-05", "2026-08-05"))
        assertEquals("2026-08-03 星期一", briefDateLabel("2026-08-03", "2026-08-05"))
        // Dirty dates are echoed as-is, no throw.
        assertEquals("not-a-date", briefDateLabel("not-a-date", "2026-08-05"))
    }

    @Test
    fun emptyStateDistinguishesTodayFromPastDays() {
        val todayText = briefEmptyMessage(isToday = true, date = "2026-08-05", nextScheduledAt = "2026-08-06 10:00", providerConfigured = true)
        val pastText = briefEmptyMessage(isToday = false, date = "2026-08-01", nextScheduledAt = "2026-08-06 10:00", providerConfigured = true)
        assertTrue("立即生成" in todayText || "手动补跑" in todayText)
        // A past empty day must not offer a catch-up run — that day will never generate again.
        assertTrue("补跑" !in pastText)
        assertTrue("2026-08-01" in pastText)
        assertEquals("今天还没有报告", briefEmptyTitle(true))
        assertEquals("这一天没有报告", briefEmptyTitle(false))
    }

    @Test
    fun providerMissingTakesPrecedenceOnToday() {
        val text = briefEmptyMessage(isToday = true, date = "2026-08-05", nextScheduledAt = "2026-08-06 10:00", providerConfigured = false)
        assertTrue("fail closed" in text)
    }

    @Test
    fun normalizeRejectsDirtyDatesInsteadOfCrashing() {
        assertEquals("2026-08-05", normalizeReportDate(" 2026-08-05 "))
        assertNull(normalizeReportDate("2026-13-99"))
        assertNull(normalizeReportDate(""))
        assertNull(normalizeReportDate(null))
    }
}
