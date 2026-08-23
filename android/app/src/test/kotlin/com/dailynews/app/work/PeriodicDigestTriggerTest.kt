package com.dailynews.app.work

import com.dailynews.data.repo.PeriodKind
import com.dailynews.data.repo.PeriodicReportRepository
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Periodic-digest trigger timing and period bounds are pure functions, JVM-tested directly. */
class PeriodicDigestTriggerTest {
    @Test
    fun weeklyFiresOnConfiguredWeekdayOnly() {
        // 2026-08-03 is a Monday.
        assertEquals(listOf(PeriodKind.WEEKLY), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-03"), true, true, 1))
        assertEquals(emptyList(), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-04"), true, true, 1))
        assertEquals(listOf(PeriodKind.WEEKLY), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-04"), true, true, 2))
    }

    @Test
    fun monthlyFiresOnTheFirstAndCanCoincideWithWeekly() {
        // 2026-06-01 is a Monday; both kinds come due together.
        assertEquals(
            listOf(PeriodKind.WEEKLY, PeriodKind.MONTHLY),
            PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-06-01"), true, true, 1),
        )
        assertEquals(listOf(PeriodKind.MONTHLY), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-09-01"), false, true, 1))
    }

    @Test
    fun disabledKindsNeverFire() {
        assertEquals(emptyList(), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-03"), false, false, 1))
    }

    @Test
    fun previousWeekIsTheLastCompleteMondayToSunday() {
        // Looking back from Wednesday, the last complete week is 07-27 (Mon) to 08-02 (Sun).
        val (start, end) = PeriodicReportRepository.previousWeek(LocalDate.parse("2026-08-05"))
        assertEquals(LocalDate.parse("2026-07-27"), start)
        assertEquals(LocalDate.parse("2026-08-02"), end)
        assertEquals("2026-W31", PeriodicReportRepository.periodKeyFor(PeriodKind.WEEKLY, start))
    }

    @Test
    fun previousMonthCoversTheWholeMonth() {
        val (start, end) = PeriodicReportRepository.previousMonth(LocalDate.parse("2026-08-01"))
        assertEquals(LocalDate.parse("2026-07-01"), start)
        assertEquals(LocalDate.parse("2026-07-31"), end)
        assertEquals("2026-07", PeriodicReportRepository.periodKeyFor(PeriodKind.MONTHLY, start))
    }

    @Test
    fun isoWeekKeyIsZeroPaddedAndUsesWeekBasedYear() {
        assertEquals("2026-W02", PeriodicReportRepository.periodKeyFor(PeriodKind.WEEKLY, LocalDate.parse("2026-01-05")))
        // A week that straddles the year belongs to the ISO week-based year, not the
        // calendar year — otherwise late December would produce a weekly digest with the
        // wrong year.
        assertEquals("2026-W53", PeriodicReportRepository.periodKeyFor(PeriodKind.WEEKLY, LocalDate.parse("2026-12-28")))
    }
}
