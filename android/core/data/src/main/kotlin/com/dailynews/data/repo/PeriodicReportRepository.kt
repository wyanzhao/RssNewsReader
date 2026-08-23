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
 * Storage and material assembly for weekly / monthly reports.
 *
 * Kept in a separate table rather than `reports`: the latter's primary key `reportDate`
 * is parsed as a date in many places, and `2026-W32` sorts lexicographically after any
 * `2026-08-xx`, so the first consequence of stuffing them in would be the desktop
 * widget's latestNow() treating a weekly report as the "latest report". See the
 * PeriodicReportEntity comments for details.
 */
class PeriodicReportRepository(private val database: DailyNewsDatabase) {

    fun observeSummaries(): Flow<List<PeriodicReportSummary>> = database.periodicReports().observeSummaries()

    fun observe(periodKey: String): Flow<PeriodicReportEntity?> = database.periodicReports().observe(periodKey)

    suspend fun find(periodKey: String): PeriodicReportEntity? = database.periodicReports().find(periodKey)

    /**
     * Assemble the material. Only takes Part 1 items from days with **status = SUCCESS**:
     * reports downgraded by markFailed did not pass review, and their content must not be
     * recirculated through a second round of editing.
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
     * Material trimming. It used to be "every Part 1 item across the whole period,
     * verbatim, into a single call"; a measured monthly report was about 900 items ×
     * 451 bytes = 406 KB ≈ 150–200k tokens: an instant 400 on a cheap model with a 32K
     * window, and on a wide-window model it devoured about a fifth of the monthly budget
     * in one shot, times three again for contract retries.
     *
     * Both steps preserve information content:
     * 1. **Deduplicate by event_key, keeping the newest report of each story line.** The
     *    product definition of the weekly report is "merge along event story lines"
     *    anyway; seven days of running log for the same story line is a burden to the
     *    model, not information. `ShortlistContextBuilder` has long done the same for
     *    recent_top30.
     * 2. If the cap is still exceeded after dedup, keep the most recent by descending
     *    date, so the digest leans toward developments in the latter half of the period.
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

    /** Publish a success. A published periodic digest must not be overwritten by a later failure (same policy as ReportRepository). */
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
     * A failure must still leave a row — the user explicitly chose the pure-LLM route, so
     * any deterministic fallback of the "stitch one together from daily digests when it
     * breaks" kind is forbidden. The failure row carries the reason; the UI shows it
     * explicitly and offers a retry entry point.
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
         * Cap on the number of material items for a single periodic digest.
         *
         * After dedup the weekly report usually stays far below this; the monthly report
         * hits it. 120 items × ~450 bytes ≈ 54 KB is still comfortable for a cheap model
         * with a 32K context.
         */
        const val MAX_DIGEST_ITEMS = 120

        /** ISO week: `2026-W32`. Month: `2026-08`. */
        fun periodKeyFor(kind: PeriodKind, start: LocalDate): String = when (kind) {
            PeriodKind.WEEKLY -> {
                val week = start.get(WeekFields.ISO.weekOfWeekBasedYear())
                val year = start.get(WeekFields.ISO.weekBasedYear())
                "%d-W%02d".format(year, week)
            }
            PeriodKind.MONTHLY -> start.format(DateTimeFormatter.ofPattern("uuuu-MM"))
        }

        /** The previous complete ISO week (Monday through Sunday). */
        fun previousWeek(today: LocalDate): Pair<LocalDate, LocalDate> {
            val thisMonday = today.with(WeekFields.ISO.dayOfWeek(), 1)
            val start = thisMonday.minusWeeks(1)
            return start to start.plusDays(6)
        }

        /** The previous complete calendar month. */
        fun previousMonth(today: LocalDate): Pair<LocalDate, LocalDate> {
            val start = today.withDayOfMonth(1).minusMonths(1)
            return start to start.withDayOfMonth(start.lengthOfMonth())
        }
    }
}
