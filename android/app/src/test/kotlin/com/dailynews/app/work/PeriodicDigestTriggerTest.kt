package com.dailynews.app.work

import com.dailynews.data.repo.PeriodKind
import com.dailynews.data.repo.PeriodicReportRepository
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** 周期简报的触发时机与周期边界都是纯函数，直接 JVM 单测。 */
class PeriodicDigestTriggerTest {
    @Test
    fun weeklyFiresOnConfiguredWeekdayOnly() {
        // 2026-08-03 是周一。
        assertEquals(listOf(PeriodKind.WEEKLY), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-03"), true, true, 1))
        assertEquals(emptyList(), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-04"), true, true, 1))
        assertEquals(listOf(PeriodKind.WEEKLY), PeriodicDigestWorker.dueKinds(LocalDate.parse("2026-08-04"), true, true, 2))
    }

    @Test
    fun monthlyFiresOnTheFirstAndCanCoincideWithWeekly() {
        // 2026-06-01 是周一，两者同时到期。
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
        // 从周三回看，上一个完整周是 07-27（周一）至 08-02（周日）。
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
        // 跨年周归属 ISO week-based year，而不是自然年——否则 12 月底会生成一份错年份的周报。
        assertEquals("2026-W53", PeriodicReportRepository.periodKeyFor(PeriodKind.WEEKLY, LocalDate.parse("2026-12-28")))
    }
}
