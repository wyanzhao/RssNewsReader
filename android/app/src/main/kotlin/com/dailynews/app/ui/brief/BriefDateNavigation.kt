package com.dailynews.app.ui.brief

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Brief-page date navigation is a pure function, JVM-testable the same way as
 * `ui/reader/ReaderFilters.kt`.
 *
 * Stepping only jumps **between dates that have a report**: ±1 calendar day would
 * make the user tap through several empty days during a coverage gap, and this app
 * does not guarantee a report every day (provider unconfigured, fetch failure,
 * manual skip).
 */
internal fun previousReportDate(available: List<String>, current: String): String? =
    available.filter { it < current }.maxOrNull()

internal fun nextReportDate(available: List<String>, current: String): String? =
    available.filter { it > current }.minOrNull()

/** `2026-08-05 Wednesday` (localized day of week); today additionally gets the "· Today" suffix. Unparseable dates are echoed back as-is, no throw. */
internal fun briefDateLabel(date: String, today: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val weekday = when (parsed.dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
    return if (date == today) "$date $weekday · 今天" else "$date $weekday"
}

/**
 * Empty-state copy must distinguish "today has not been generated yet" from "that day has
 * no report" — the former can still get a makeup run; the latter is settled history, and
 * offering a makeup-run button would only make people wait for nothing.
 */
internal fun briefEmptyTitle(isToday: Boolean): String = if (isToday) "今天还没有报告" else "这一天没有报告"

internal fun briefEmptyMessage(isToday: Boolean, date: String, nextScheduledAt: String, providerConfigured: Boolean): String =
    when {
        isToday && !providerConfigured -> "下次计划：$nextScheduledAt。请先配置 provider，编辑分支会保持 fail closed。"
        isToday -> "下次计划：$nextScheduledAt。也可以用「立即生成」手动补跑。"
        else -> "$date 没有生成过报告。用上方箭头或日期列表切换到有报告的日子。"
    }

/** Returns null on parse failure, so callers fall back to "follow today" instead of crashing on a dirty date. */
internal fun normalizeReportDate(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed).toString()
    } catch (_: DateTimeParseException) {
        null
    }
}
