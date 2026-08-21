package com.dailynews.data.repo

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.PeriodicReportEntity
import com.dailynews.data.db.PeriodicReportSummary
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PeriodicDigest
import com.dailynews.pipeline.flow.PeriodicDigestInput
import com.dailynews.pipeline.flow.PeriodicDigestItem
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

enum class PeriodKind { WEEKLY, MONTHLY }

/**
 * 周报 / 月报的存储与素材装配。
 *
 * 存独立表而不是 `reports`：后者主键 `reportDate` 在极多处被当日期解析，
 * 而 `2026-W32` 字典序大于任何 `2026-08-xx`，塞进去第一个后果就是桌面 widget
 * 的 latestNow() 把周报当成「最新报告」。详见 PeriodicReportEntity 的注释。
 */
class PeriodicReportRepository(private val database: DailyNewsDatabase) {

    fun observeSummaries(): Flow<List<PeriodicReportSummary>> = database.periodicReports().observeSummaries()

    fun observe(periodKey: String): Flow<PeriodicReportEntity?> = database.periodicReports().observe(periodKey)

    suspend fun find(periodKey: String): PeriodicReportEntity? = database.periodicReports().find(periodKey)

    /**
     * 装配素材。只取 **status = SUCCESS** 那些天的 Part 1 条目：
     * 被 markFailed 降级过的报告没通过审校，其内容不该被二次编辑重新流通。
     */
    suspend fun collectInput(kind: PeriodKind, start: LocalDate, end: LocalDate): PeriodicDigestInput {
        val rows = database.reports().publishedPart1Between(start.toString(), end.toString())
        return PeriodicDigestInput(
            period = periodKeyFor(kind, start),
            kind = kind.name,
            periodStartDate = start.toString(),
            periodEndDate = end.toString(),
            reportDates = rows.map { it.reportDate }.distinct().sorted(),
            items = boundedItems(rows),
        )
    }

    /**
     * 素材裁剪。此前是「整段时间的每一条 Part 1 原样送进一次调用」，月报实测约
     * 900 条 × 451 字节 = 406 KB ≈ 15–20 万 token：在 32K 窗口的便宜模型上直接 400，
     * 在宽窗口模型上一口气吃掉月度预算的约两成，契约重试再乘三。
     *
     * 两步都保留信息量：
     * 1. **按 event_key 去重，保留每条线索最新的一次报道。** 周报的产品定义本就是
     *    「按事件主线归并」，同一条线索的七天流水对模型是负担不是信息；
     *    `ShortlistContextBuilder` 对 recent_top30 早就是同一个做法。
     * 2. 去重之后仍超上限时，按日期倒序保留最近的，让简报偏向本期后半段的进展。
     */
    private fun boundedItems(rows: List<ReportItemEntity>): List<PeriodicDigestItem> = rows
        .sortedByDescending { it.reportDate }
        .distinctBy { it.eventKey.ifBlank { it.link } }
        .take(MAX_DIGEST_ITEMS)
        .sortedBy { it.reportDate }
        .map { row ->
            PeriodicDigestItem(
                reportDate = row.reportDate,
                title = row.title,
                source = row.source,
                link = row.link,
                summaryZh = row.summaryZh,
                eventKey = row.eventKey,
            )
        }

    /** 成功发布。已发布的周期简报不得被后续失败覆盖（与 ReportRepository 同策略）。 */
    suspend fun publish(
        kind: PeriodKind,
        input: PeriodicDigestInput,
        digest: PeriodicDigest,
        markdown: String,
    ) {
        val now = Instant.now().toString()
        database.periodicReports().upsert(
            PeriodicReportEntity(
                periodKey = input.period,
                kind = kind.name,
                periodStartDate = input.periodStartDate,
                periodEndDate = input.periodEndDate,
                status = "SUCCESS",
                markdown = markdown,
                sourceReportDatesJson = ArtifactJson.compact.encodeToString(input.reportDates),
                itemCount = digest.sections.sumOf { it.links.size },
                failureReason = null,
                createdAtUtc = now,
                publishedAtUtc = now,
            ),
        )
    }

    /**
     * 失败必须留行 —— 用户明确选了纯 LLM 路线，任何「挂了就用日摘要拼一份」的
     * 确定性兜底都被禁止。失败行携带原因，UI 显式展示并给重试入口。
     */
    suspend fun publishFailure(
        kind: PeriodKind,
        periodKey: String,
        start: LocalDate,
        end: LocalDate,
        reason: String,
    ) {
        if (database.periodicReports().wasPublished(periodKey)) return
        database.periodicReports().upsert(
            PeriodicReportEntity(
                periodKey = periodKey,
                kind = kind.name,
                periodStartDate = start.toString(),
                periodEndDate = end.toString(),
                status = "FAILED",
                markdown = "",
                sourceReportDatesJson = "[]",
                itemCount = 0,
                failureReason = reason,
                createdAtUtc = Instant.now().toString(),
                publishedAtUtc = null,
            ),
        )
    }

    companion object {
        /**
         * 单次周期简报的素材条数上限。
         *
         * 去重之后周报通常远低于此，月报会撞上。120 条 × 约 450 字节 ≈ 54 KB，
         * 对 32K 上下文的便宜模型仍然宽裕。
         */
        const val MAX_DIGEST_ITEMS = 120

        /** ISO 周：`2026-W32`。月：`2026-08`。 */
        fun periodKeyFor(kind: PeriodKind, start: LocalDate): String = when (kind) {
            PeriodKind.WEEKLY -> {
                val week = start.get(WeekFields.ISO.weekOfWeekBasedYear())
                val year = start.get(WeekFields.ISO.weekBasedYear())
                "%d-W%02d".format(year, week)
            }
            PeriodKind.MONTHLY -> start.format(DateTimeFormatter.ofPattern("uuuu-MM"))
        }

        /** 上一个完整 ISO 周（周一至周日）。 */
        fun previousWeek(today: LocalDate): Pair<LocalDate, LocalDate> {
            val thisMonday = today.with(WeekFields.ISO.dayOfWeek(), 1)
            val start = thisMonday.minusWeeks(1)
            return start to start.plusDays(6)
        }

        /** 上一个完整自然月。 */
        fun previousMonth(today: LocalDate): Pair<LocalDate, LocalDate> {
            val start = today.withDayOfMonth(1).minusMonths(1)
            return start to start.withDayOfMonth(start.lengthOfMonth())
        }
    }
}
