package com.dailynews.app.ui.brief

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 简报页的日期导航是纯函数，和 `ui/reader/ReaderFilters.kt` 一样可以直接 JVM 单测。
 *
 * 步进只在**有报告的日期之间**跳：按自然日 ±1 会让用户在断更期间连点好几下空白，
 * 而这个 app 本来就不保证每天都有报告（provider 未配置、抓取失败、手动跳过）。
 */
internal fun previousReportDate(available: List<String>, current: String): String? =
    available.filter { it < current }.maxOrNull()

internal fun nextReportDate(available: List<String>, current: String): String? =
    available.filter { it > current }.minOrNull()

/** `2026-08-05 星期三`；当天再加「· 今天」。日期不可解析时原样回显，不抛。 */
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
 * 空态文案要区分「今天还没生成」和「那天就没有报告」——前者可以补跑，
 * 后者是既成历史，给出补跑按钮只会让人白等。
 */
internal fun briefEmptyTitle(isToday: Boolean): String = if (isToday) "今天还没有报告" else "这一天没有报告"

internal fun briefEmptyMessage(isToday: Boolean, date: String, nextScheduledAt: String, providerConfigured: Boolean): String =
    when {
        isToday && !providerConfigured -> "下次计划：$nextScheduledAt。请先配置 provider，编辑分支会保持 fail closed。"
        isToday -> "下次计划：$nextScheduledAt。也可以用「立即生成」手动补跑。"
        else -> "$date 没有生成过报告。用上方箭头或日期列表切换到有报告的日子。"
    }

/** 解析失败时返回 null，让调用方回退到「跟随当天」而不是崩在一个脏日期上。 */
internal fun normalizeReportDate(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed).toString()
    } catch (_: DateTimeParseException) {
        null
    }
}
