package com.dailynews.pipeline.ports

import com.dailynews.model.AssembledReport
import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import java.time.Instant
import java.time.LocalDate

interface FeedSource {
    suspend fun enabledFeeds(): List<FeedDefinition>
}

interface SeenLinksStore {
    suspend fun entries(): Map<String, LocalDate>
    suspend fun replace(entries: Map<String, LocalDate>)
    /** Atomically records a published report so concurrent imports cannot be overwritten. */
    suspend fun recordReportedLinks(links: List<String>, reportDate: LocalDate)
}

data class EditorialCacheRecord(
    val cacheKey: String,
    val link: String,
    val source: String,
    val title: String,
    val summaryZh: String? = null,
    val part1SummaryZh: String? = null,
    val noiseBucket: String? = null,
    val part1NoiseBucket: String? = null,
    val eventKey: String? = null,
    val updatedAtUtc: Instant? = null,
)

interface EditorialCacheStore {
    suspend fun find(cacheKey: String): EditorialCacheRecord?
    suspend fun recentSince(since: Instant): List<EditorialCacheRecord>
    suspend fun upsert(records: List<EditorialCacheRecord>)
    suspend fun prune(before: Instant)
}

interface ReportSink {
    suspend fun publish(report: AssembledReport)
    suspend fun markFailed(reportDate: String, reason: String) = Unit
}

interface FailureReportSink {
    suspend fun publishFailure(reportDate: String, markdown: String)
}

interface TopNReportSink {
    suspend fun publishTopN(reportDate: String, markdown: String)
}

interface ArtifactSink {
    suspend fun write(runId: String, relativePath: String, content: ByteArray)
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

interface RunLogSink {
    suspend fun log(runId: String, step: String, level: LogLevel, message: String)
}

fun interface ClockProvider {
    fun now(): Instant
}

fun interface FetchPort {
    suspend fun fetch(reportDate: LocalDate, attempt: Int, trigger: String, config: com.dailynews.model.PipelineConfig): RawRun
}

fun interface NetworkStatePort {
    fun isWifiConnected(): Boolean
}

interface FetchLifecyclePort {
    suspend fun beforeRetry(reportDate: LocalDate)
    suspend fun recordStarted(raw: RawRun, reportDate: LocalDate, attempt: Int, trigger: String)
    suspend fun recordWarning(raw: RawRun, step: String, message: String) = Unit
}

data class SweepFeedOutcome(
    val feedName: String,
    val status: String,
    val error: String?,
    val itemCount: Int,
    val newestItemDateIso: String?,
)

data class SweepWrite(
    val fetchedAt: Instant,
    val articles: List<Article>,
    val enrichedLinkKeys: Set<String>,
    val feedOutcomes: List<SweepFeedOutcome>,
)

data class PooledArticle(val article: Article, val needsEnrichment: Boolean)

data class FeedHealth(
    val feedName: String,
    val lastStatus: String?,
    val lastError: String?,
    val newestItemDateIso: String?,
)

/** Storage boundary used by the JVM-only sweep/window pipeline. */
interface ArticlePoolPort {
    suspend fun existingLinkKeys(linkKeys: Set<String>): Set<String>
    suspend fun recordSweep(write: SweepWrite)
    suspend fun pendingEnrichment(from: Instant, limit: Int): List<Article>
    suspend fun articlesSince(from: Instant): List<PooledArticle>
    suspend fun updateEnriched(articles: List<Article>, enrichedAt: Instant)
    suspend fun feedHealth(feedNames: Set<String>): Map<String, FeedHealth>
}
