package com.dailynews.data.repo

import androidx.room.withTransaction
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.EditorialCacheEntity
import com.dailynews.data.db.ReportEntity
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.db.ReportSummary
import com.dailynews.data.db.ReportPreview
import com.dailynews.model.ArtifactJson
import com.dailynews.model.AssembledReport
import com.dailynews.model.ReportItem
import com.dailynews.pipeline.ports.ReportSink
import com.dailynews.pipeline.ports.FailureReportSink
import com.dailynews.pipeline.ports.TopNReportSink
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.flow.Part2OnDemandGenerator
import com.dailynews.pipeline.flow.Part2SummaryRequest
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import java.io.File
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

class ReportRepository(
    private val database: DailyNewsDatabase,
    context: Context,
) : ReportSink, FailureReportSink, TopNReportSink {
    private val reportsDirectory = File(context.filesDir, "reports")
    private val articles = ArticleRepository(database)
    private val lazyGenerationMutex = Mutex()

    override suspend fun publish(report: AssembledReport) {
        atomicWrite(File(reportsDirectory, "rss-report-${report.reportDate}.md"), report.markdown)
        val now = Instant.now().toString()
        val materialByLink = report.items.map(ReportItem::link).distinct().associateWith { link ->
            database.articles().get(TextUtils.dedupLinkKey(link))
        }
        database.withTransaction {
            database.reports().upsert(
                ReportEntity(
                    report.reportDate,
                    "SUCCESS",
                    report.markdown,
                    report.topNMarkdown,
                    ArtifactJson.compact.encodeToString(report.groups),
                    now,
                    failureReason = null,
                    publishedAtUtc = now,
                ),
            )
            database.reports().deleteItems(report.reportDate)
            database.reports().insertItems(report.items.map { item ->
                ReportItemEntity(
                    report.reportDate,
                    item.part,
                    item.position,
                    item.link,
                    item.title,
                    item.source,
                    item.pubDateUtc,
                    item.pubDateIso,
                    materialByLink[item.link]?.summaryEn.orEmpty(),
                    materialByLink[item.link]?.articleText.orEmpty(),
                    item.summaryZh,
                    ArtifactJson.compact.encodeToString(item.alsoLinks),
                )
            })
            val reportedKeys = report.items.flatMap { item -> listOf(item.link) + item.alsoLinks }
                .map(TextUtils::dedupLinkKey)
                .filter(String::isNotBlank)
                .distinct()
            reportedKeys.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                database.articles().markReported(chunk, report.reportDate)
            }
        }
    }

    override suspend fun publishFailure(reportDate: String, markdown: String) {
        atomicWrite(File(reportsDirectory, "rss-report-$reportDate.failed.md"), markdown)
        // A manual same-day retry must not erase a report that was already
        // published — including one a failed review later downgraded, which is
        // no longer a SUCCESS row but still holds the real report body.
        if (!database.reports().wasPublished(reportDate)) {
            database.reports().upsert(ReportEntity(reportDate, "FAILED", markdown, "", "[]", Instant.now().toString()))
        }
    }

    override suspend fun publishTopN(reportDate: String, markdown: String) {
        atomicWrite(File(reportsDirectory, "top-$reportDate.md"), markdown)
        database.reports().updateTopN(reportDate, markdown)
    }

    override suspend fun markFailed(reportDate: String, reason: String) {
        database.reports().markFailed(reportDate, reason)
    }

    fun summaries(query: String = ""): Flow<List<ReportSummary>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return database.reports().searchSummaries("%")
        return combine(
            database.reports().searchSummaries(reportSearchPattern(normalized)),
            articles.searchReportedDates(normalized),
            database.reports().observeAllSummaries(),
        ) { direct, articleDates, all ->
            val directDates = direct.mapTo(mutableSetOf(), ReportSummary::reportDate)
            (direct + all.filter { it.reportDate in articleDates && directDates.add(it.reportDate) })
                .sortedByDescending(ReportSummary::reportDate)
        }
    }
    fun report(date: String): Flow<ReportEntity?> = database.reports().observeReport(date)
    fun previews(query: String = ""): Flow<List<ReportPreview>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return database.reports().searchPreviews("%")
        return combine(
            database.reports().searchPreviews(reportSearchPattern(normalized)),
            articles.searchReportedDates(normalized),
            database.reports().observeAllPreviews(),
        ) { direct, articleDates, all ->
            val directDates = direct.mapTo(mutableSetOf(), ReportPreview::reportDate)
            (direct + all.filter { it.reportDate in articleDates && directDates.add(it.reportDate) })
                .sortedByDescending(ReportPreview::reportDate)
        }
    }
    fun items(date: String): Flow<List<ReportItemEntity>> = database.reports().observeItems(date)
    suspend fun hasSuccess(date: String): Boolean = database.reports().hasSuccess(date)

    suspend fun widgetSnapshot(limit: Int = 3): WidgetReportSnapshot? {
        val report = database.reports().latestNow() ?: return null
        return WidgetReportSnapshot(
            reportDate = report.reportDate,
            status = report.status,
            titles = if (report.status == "SUCCESS") database.reports().topItemsNow(report.reportDate, limit.coerceIn(1, 3)).map { it.title } else emptyList(),
        )
    }

    suspend fun generatePart2Group(
        reportDate: String,
        source: String,
        generator: Part2OnDemandGenerator,
        shortSummaryThreshold: Int,
        maxCallsPerRun: Int,
        llmExecution: LlmExecutionConfig,
    ): Int = lazyGenerationMutex.withLock {
        val pendingItems = database.reports().part2ItemsForSource(reportDate, source)
            .filter { it.summaryZh.isBlank() }
        if (pendingItems.isEmpty()) return 0

        val requests = pendingItems.map { item ->
            Part2SummaryRequest(
                source = source,
                title = item.title,
                link = item.link,
                pubDateIso = item.pubDateIso,
                summaryMaterial = when {
                    item.summaryEn.length >= shortSummaryThreshold -> item.summaryEn
                    item.articleText.isNotBlank() -> item.articleText.truncateWords(60)
                    else -> item.summaryEn
                },
            )
        }
        val runId = database.runs().latestForDate(reportDate)?.runId ?: "lazy-$reportDate"
        val remainingCalls = maxCallsPerRun - database.llmCalls().count(runId)
        require(remainingCalls > 0) { "per-run LLM call limit exhausted ($maxCallsPerRun)" }
        val generated = generator.generate(runId, requests, remainingCalls, llmExecution)
        val generatedByLink = generated.associateBy { TextUtils.cleanText(it.link) }
        val requiredLinks = requests.mapTo(linkedSetOf()) { TextUtils.cleanText(it.link) }
        require(generated.size == requiredLinks.size && generatedByLink.keys == requiredLinks) {
            "on-demand Part 2 response does not exactly cover the source group"
        }
        val lintErrors = generated.flatMapIndexed { index, item ->
            buildList {
                if (item.summaryZh.isBlank()) add("item ${index + 1} missing summary_zh")
                addAll(EditorialContracts.summaryLintErrors(item.summaryZh, "item ${index + 1}", EditorialContracts.PART2_SUMMARY_HARD_CAP))
            }
        }
        require(lintErrors.isEmpty()) { lintErrors.joinToString("; ") }

        val now = Instant.now().toString()
        database.withTransaction {
            pendingItems.forEach { item ->
                val summary = generatedByLink.getValue(TextUtils.cleanText(item.link))
                check(database.reports().updatePart2Summary(reportDate, item.position, summary.summaryZh) == 1) {
                    "Part 2 item disappeared while updating ${item.link}"
                }
                val article = com.dailynews.model.Article(
                    item.source,
                    item.title,
                    item.link,
                    item.pubDateUtc,
                    item.pubDateIso,
                    item.summaryEn,
                    item.articleText,
                )
                val key = EditorialCacheKeys.cacheKey(article)
                val existing = database.editorialCache().find(key)
                database.editorialCache().upsert(
                    listOf(
                        (existing ?: EditorialCacheEntity(key, item.link, item.source, item.title)).copy(
                            link = article.link,
                            source = article.source,
                            title = article.title,
                            summaryZh = summary.summaryZh,
                            noiseBucket = summary.noiseBucket,
                            eventKey = EditorialCacheKeys.eventKey(summary.eventKey, article.title),
                            updatedAtUtc = now,
                        ),
                    ),
                )
            }
        }
        return generated.size
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        check(temporary.renameTo(target)) { "atomic report rename failed: ${target.absolutePath}" }
    }
}

data class WidgetReportSnapshot(val reportDate: String, val status: String, val titles: List<String>)

internal fun reportSearchPattern(query: String): String = buildString {
    append('%')
    query.trim().forEach { character ->
        if (character == '\\' || character == '%' || character == '_') append('\\')
        append(character)
    }
    append('%')
}

private fun String.truncateWords(maxWords: Int): String {
    val words = trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return if (words.size > maxWords) words.take(maxWords).joinToString(" ") + "..." else words.joinToString(" ")
}
