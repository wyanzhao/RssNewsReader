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
    indices = [Index("pubDateIso"), Index("reportedDate"), Index("favoritedAtUtc")],
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
@Entity(tableName = "report_items", primaryKeys = ["reportDate", "part", "position"], indices = [Index("link")])
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
