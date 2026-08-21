package com.dailynews.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "feeds", indices = [Index(value = ["name"], unique = true), Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val errorPolicy: String = "block",
    val enabled: Boolean = true,
    val position: Int = 0,
    val lastFetchAtUtc: String? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
    val newestItemDateIso: String? = null,
)

@Serializable
@Entity(
    tableName = "articles",
    indices = [
        Index("pubDateIso"),
        Index("reportedDate"),
        Index("favoritedAtUtc"),
        // Epic U 阅读器：按源时间线纯索引范围扫描。
        Index(value = ["feedName", "pubDateIso"]),
        // Epic U 阅读器：未读计数覆盖索引，不回表。
        Index(value = ["readAtUtc", "feedName", "pubDateIso"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey val linkKey: String,
    val link: String,
    val feedName: String,
    val title: String,
    val summaryEn: String,
    val articleText: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    val fetchedAtUtc: String,
    val enrichedAtUtc: String? = null,
    val readAtUtc: String? = null,
    val favoritedAtUtc: String? = null,
    val reportedDate: String? = null,
)

@Fts4(contentEntity = ArticleEntity::class)
@Entity(tableName = "articles_fts")
data class ArticleFtsEntity(
    val linkKey: String,
    val title: String,
    val summaryEn: String,
)

@Serializable
@Entity(tableName = "fetch_log", indices = [Index("feedName"), Index("fetchedAtUtc")])
data class FetchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedName: String,
    val fetchedAtUtc: String,
    val status: String,
    val error: String? = null,
    val itemCount: Int,
    val newCount: Int,
)

@Serializable
@Entity(
    tableName = "run_artifacts",
    primaryKeys = ["runId", "name"],
    indices = [Index("runId"), Index("createdAtUtc")],
)
data class RunArtifactEntity(
    val runId: String,
    val name: String,
    val gzipBody: ByteArray,
    val createdAtUtc: String,
)

data class RunArtifactMetadata(
    val runId: String,
    val name: String,
    val createdAtUtc: String,
)

@Serializable
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val runId: String,
    val reportDate: String,
    val status: String,
    val classification: String,
    val validatorExitCode: Int,
    val attempt: Int,
    val trigger: String,
    val blockingReasonsJson: String = "[]",
    val warningsJson: String = "[]",
    val countsJson: String = "{}",
    val startedAtUtc: String,
    val finishedAtUtc: String? = null,
)

@Serializable
@Entity(tableName = "run_logs", indices = [Index("runId")])
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val step: String,
    val level: String,
    val message: String,
    val createdAtUtc: String,
)

@Serializable
@Entity(tableName = "llm_calls", indices = [Index("runId")])
data class LlmCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val role: String,
    val provider: String,
    val model: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val retryIndex: Int,
    val outcome: String,
    val createdAtUtc: String,
)

@Serializable
@Entity(tableName = "llm_usage_monthly")
data class LlmUsageMonthEntity(
    @PrimaryKey val month: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val callCount: Long,
)

@Serializable
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val reportDate: String,
    val status: String,
    val markdown: String,
    val topNMarkdown: String,
    val groupsJson: String = "[]",
    val createdAtUtc: String,
    /** Why a published report was later downgraded. Never overloads [groupsJson], which stays a source-group list. */
    val failureReason: String? = null,
    /** Set once assemble writes the report; a later same-day failure must not overwrite its body. */
    val publishedAtUtc: String? = null,
)

@Serializable
@Entity(
    tableName = "report_items",
    primaryKeys = ["reportDate", "part", "position"],
    indices = [Index("link"), Index("eventKey")],
)
data class ReportItemEntity(
    val reportDate: String,
    val part: Int,
    val position: Int,
    val link: String,
    val title: String,
    val source: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    /** Immutable source-material snapshot for LAZY generation after article-pool retention. */
    val summaryEn: String = "",
    val articleText: String = "",
    val summaryZh: String,
    val alsoLinksJson: String = "[]",
    /**
     * 跨日线索 id。`alsoLinksJson` 只在单份报告内聚类同一事件，这个字段把聚类
     * 延伸到跨天：同一 eventKey 的历史条目就是「这条线索的进展」。
     * 由 EditorialCacheKeys 归一化，保证非空。
     */
    val eventKey: String = "",
)

/**
 * 周报 / 月报。刻意不进 `reports` 表：那张表的主键 `reportDate` 在极多处被当日期解析，
 * 而 `2026-W32` 字典序大于 `2026-08-05`（`W` > `0`），塞进去的第一个后果就是
 * 桌面 widget 的 latestNow() 把周报当成「最新报告」展示。
 * 语义也不同——`reports` 是流水线产物，这里是对已发布行的二次编辑产物。
 */
@Serializable
@Entity(tableName = "periodic_reports", indices = [Index("kind"), Index("createdAtUtc")])
data class PeriodicReportEntity(
    /** `2026-W32`（ISO 周）或 `2026-08`（月）。 */
    @PrimaryKey val periodKey: String,
    /** `WEEKLY` | `MONTHLY`。 */
    val kind: String,
    val periodStartDate: String,
    val periodEndDate: String,
    /** `SUCCESS` | `FAILED`。失败必须留行，绝不伪造内容。 */
    val status: String,
    val markdown: String,
    val sourceReportDatesJson: String = "[]",
    val itemCount: Int = 0,
    val failureReason: String? = null,
    val createdAtUtc: String,
    val publishedAtUtc: String? = null,
)

data class PeriodicReportSummary(
    val periodKey: String,
    val kind: String,
    val status: String,
    val periodStartDate: String,
    val periodEndDate: String,
    val itemCount: Int,
    val failureReason: String?,
    val createdAtUtc: String,
)

@Serializable
@Entity(tableName = "editorial_cache", indices = [Index("link")])
data class EditorialCacheEntity(
    @PrimaryKey val cacheKey: String,
    val link: String,
    val source: String,
    val title: String,
    val summaryZh: String? = null,
    val part1SummaryZh: String? = null,
    val noiseBucket: String? = null,
    val part1NoiseBucket: String? = null,
    val eventKey: String? = null,
    val updatedAtUtc: String? = null,
)

@Serializable
@Entity(tableName = "seen_links")
data class SeenLinkEntity(@PrimaryKey val linkKey: String, val firstSeenDate: String)

data class FavoriteArticle(
    val linkKey: String,
    val link: String,
    val title: String,
    val source: String,
    val summaryZh: String,
    val favoritedAtUtc: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    val readAtUtc: String?,
)

/** 线索深度投影：该 event_key 被报道过多少个不同日期。 */
data class StoryDepth(val eventKey: String, val days: Int)

/** 按天分节的日计数投影。`day` 是 UTC 日（substr(pubDateIso,1,10)）。 */
data class ReaderDayCount(val day: String, val total: Int)

/**
 * 阅读器窄投影：不含 articleText 与完整 summaryEn，单项载荷比
 * [ArticleEntity] 砍掉 >90%，避免全池 SELECT * 的十几 MB 扫描。
 */
data class ReaderArticle(
    val linkKey: String,
    val link: String,
    val title: String,
    val source: String,
    val summaryZh: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    val readAtUtc: String?,
    val favoritedAtUtc: String?,
)

/**
 * 应用内阅读所需的完整投影。
 *
 * 与窄投影 [ReaderArticle] 的区别只有 `articleText`：正文只在真正要读它的那一屏
 * 才取，列表投影保持轻量。中文摘要仍然按 report_items 优先、summaryEn 兜底，
 * 与 observeTimeline 同一口径。
 */
data class ArticleDetail(
    val linkKey: String,
    val link: String,
    val title: String,
    val source: String,
    val summaryZh: String,
    val articleText: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    val favoritedAtUtc: String?,
)

data class FeedUnreadCount(
    val feedName: String,
    val unread: Int,
)

data class ReportedDateRow(val reportedDate: String)

data class ReportSummary(
    val reportDate: String,
    val status: String,
    val failureReason: String?,
    val createdAtUtc: String,
    val publishedAtUtc: String?,
)

data class ReportPreview(
    val reportDate: String,
    val status: String,
    val failureReason: String?,
    val articleCount: Int,
    val previewTitle1: String?,
    val previewTitle2: String?,
    val previewTitle3: String?,
)

data class RunSummary(
    val runId: String,
    val reportDate: String,
    val status: String,
    val classification: String,
    val validatorExitCode: Int,
    val attempt: Int,
    val startedAtUtc: String,
    val finishedAtUtc: String?,
)

data class LlmUsageRollup(
    val month: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val callCount: Long,
)
