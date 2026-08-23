package com.dailynews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject

@Serializable
data class FeedDefinition(
    val name: String,
    val url: String,
    @SerialName("error_policy") val errorPolicy: String = "block",
    val enabled: Boolean = true,
    val position: Int = 0,
)

@Serializable
data class FeedConfigDocument(val feeds: List<FeedDefinition>)

@Serializable
data class RawMeta(
    @SerialName("generated_at_utc") val generatedAtUtc: String,
    @SerialName("run_id") val runId: String,
    @SerialName("input_mode") val inputMode: String,
    @SerialName("feed_count_expected") val feedCountExpected: Int,
)

@Serializable
data class Article(
    val source: String,
    val title: String,
    val link: String,
    @SerialName("pub_date_utc") val pubDateUtc: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_en") val summaryEn: String = "",
    @SerialName("article_text") val articleText: String = "",
)

@Serializable
data class FeedResult(
    val source: String,
    val url: String,
    val status: String,
    val error: String? = null,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("newest_item_date") val newestItemDate: String? = null,
)

@Serializable
data class RawRun(
    val meta: RawMeta,
    val count: Int,
    val articles: List<Article>,
    @SerialName("feed_results") val feedResults: List<FeedResult>,
    @SerialName("configured_feed_count") val configuredFeedCount: Int,
    @SerialName("unique_source_count") val uniqueSourceCount: Int? = null,
    @SerialName("unique_sources") val uniqueSources: List<String>? = null,
    @SerialName("runtime_config") val runtimeConfig: JsonObject? = null,
)

@Serializable
data class ValidationCounts(
    val configured: Int = 0,
    val results: Int = 0,
    val ok: Int = 0,
    val empty: Int = 0,
    val error: Int = 0,
    val articles: Int = 0,
    @SerialName("blocking_error") val blockingError: Int = 0,
    @SerialName("warn_error") val warnError: Int = 0,
)

@Serializable
data class ValidationPolicy(
    @SerialName("block_on_error_count") val blockOnErrorCount: Boolean = false,
    @SerialName("block_on_zero_articles") val blockOnZeroArticles: Boolean = true,
    @SerialName("block_on_feed_results_mismatch") val blockOnFeedResultsMismatch: Boolean = true,
    @SerialName("empty_is_warning_only") val emptyIsWarningOnly: Boolean = true,
    @SerialName("unique_source_count_is_observational") val uniqueSourceCountIsObservational: Boolean = true,
    @SerialName("warn_error_sources") val warnErrorSources: List<String> = emptyList(),
)

@Serializable
data class ValidationMeta(
    @SerialName("generated_at_utc") val generatedAtUtc: String,
    @SerialName("run_id") val runId: String,
    @SerialName("input_mode") val inputMode: String,
)

@Serializable
data class ValidationResult(
    val passed: Boolean,
    @SerialName("blocking_reasons") val blockingReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val counts: ValidationCounts = ValidationCounts(),
    val policy: ValidationPolicy = ValidationPolicy(),
    @SerialName("feed_results") val feedResults: List<FeedResult> = emptyList(),
    val meta: ValidationMeta? = null,
)

@Serializable
data class LlmMeta(
    val date: String,
    @SerialName("generated_at_utc") val generatedAtUtc: String,
    @SerialName("run_id") val runId: String,
    @SerialName("report_path") val reportPath: String,
)

@Serializable
data class LlmValidation(
    val passed: Boolean,
    @SerialName("blocking_reasons") val blockingReasons: List<String>,
    val warnings: List<String>,
    val counts: ValidationCounts,
    val policy: ValidationPolicy,
)

@Serializable
data class ArticleRef(
    val title: String,
    val link: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
)

@Serializable
data class SourceGroup(
    val source: String,
    val url: String,
    val status: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("article_refs") val articleRefs: List<ArticleRef>,
)

@Serializable
data class LlmContext(
    val meta: LlmMeta,
    val validation: LlmValidation,
    @SerialName("all_articles") val allArticles: List<Article>,
    @SerialName("source_groups") val sourceGroups: List<SourceGroup>,
)

@Serializable
data class PreviewPolicy(
    @SerialName("short_summary_threshold") val shortSummaryThreshold: Int,
    @SerialName("included_when") val includedWhen: String,
)

@Serializable
data class Part1BriefArticle(
    /**
     * Short reference id (`a1`, `a2`…). The shortlist writes only this, never echoing the link.
     *
     * This is the heaviest copying burden of the four editorial calls: it must name 40–45 articles
     * at once. Asking the model to transcribe verbatim that many URLs averaging 100 characters is
     * exactly what caused the 2026-08-19 incident.
     */
    val id: String,
    val source: String,
    val title: String,
    val link: String,
    @SerialName("pub_date_utc") val pubDateUtc: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_en") val summaryEn: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("article_text_preview") val articleTextPreview: String? = null,
)

@Serializable
data class Part1Brief(
    val meta: LlmMeta,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("article_text_preview_words") val articleTextPreviewWords: Int,
    @SerialName("preview_policy") val previewPolicy: PreviewPolicy,
    val articles: List<Part1BriefArticle>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("editor_feedback") val editorFeedback: List<String> = emptyList(),
)

@Serializable
data class Part2ContextArticle(
    val title: String,
    val link: String,
    @SerialName("pub_date_utc") val pubDateUtc: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("summary_material") val summaryMaterial: String? = null,
    @SerialName("summary_source") val summarySource: String,
    @SerialName("cache_status") val cacheStatus: String,
    @SerialName("needs_summary") val needsSummary: Boolean,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("summary_zh") val summaryZh: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("event_key") val eventKey: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("noise_bucket") val noiseBucket: String? = null,
)

@Serializable
data class Part2ContextGroup(
    val source: String,
    val url: String,
    val status: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("error_text") val errorText: String? = null,
    val articles: List<Part2ContextArticle>,
)

@Serializable
data class Part2Context(
    val meta: LlmMeta,
    @SerialName("total_articles") val totalArticles: Int,
    val groups: List<Part2ContextGroup>,
    @SerialName("summary_policy") val summaryPolicy: JsonObject,
    val cache: JsonObject,
)

@Serializable
data class ContextBudgetLimits(
    @SerialName("llm_context_max_bytes") val llmContextMaxBytes: Int,
    @SerialName("part1_brief_max_bytes") val part1BriefMaxBytes: Int,
    @SerialName("part2_context_max_bytes") val part2ContextMaxBytes: Int,
    @SerialName("total_context_max_bytes") val totalContextMaxBytes: Int,
)

@Serializable
data class ContextBudgetSizes(
    @SerialName("llm_context_bytes") val llmContextBytes: Int,
    @SerialName("part1_brief_bytes") val part1BriefBytes: Int,
    @SerialName("part2_context_bytes") val part2ContextBytes: Int,
    @SerialName("total_context_bytes") val totalContextBytes: Int,
)

@Serializable
data class ContextBudgetCounts(
    val articles: Int,
    val sources: Int,
    @SerialName("part2_cache_hits") val part2CacheHits: Int,
    @SerialName("part2_missing_summaries") val part2MissingSummaries: Int,
)

@Serializable
data class PerSourceCount(val source: String, @SerialName("article_count") val articleCount: Int)

@Serializable
data class ContextBudget(
    val meta: LlmMeta,
    val limits: ContextBudgetLimits,
    val sizes: ContextBudgetSizes,
    val counts: ContextBudgetCounts,
    @SerialName("per_source") val perSource: List<PerSourceCount>,
    @SerialName("within_budget") val withinBudget: Boolean,
    val violations: List<ContextBudgetViolation>,
)

@Serializable
data class ContextBudgetViolation(
    val size: String,
    val actual: Int,
    val limit: Int,
)

@Serializable
data class Part2DraftArticle(
    val title: String,
    val link: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("noise_bucket") val noiseBucket: String = "covered",
    @SerialName("event_key") val eventKey: String = "",
)

@Serializable
data class Part2DraftGroup(
    val source: String,
    val status: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("error_text") val errorText: String? = null,
    val articles: List<Part2DraftArticle>,
)

@Serializable
data class Part2Draft(
    @SerialName("total_articles") val totalArticles: Int,
    val groups: List<Part2DraftGroup>,
)

@Serializable
data class ReportItem(
    val part: Int,
    val position: Int,
    val link: String,
    val title: String,
    val source: String,
    @SerialName("pub_date_utc") val pubDateUtc: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("also_links") val alsoLinks: List<String> = emptyList(),
    /** Cross-day thread id. `also_links` only clusters within this report; this field connects the same event to previous days' reports. */
    @SerialName("event_key") val eventKey: String = "",
)

@Serializable
data class AssembledReport(
    @SerialName("report_date") val reportDate: String,
    val markdown: String,
    @SerialName("top_n_markdown") val topNMarkdown: String,
    val items: List<ReportItem>,
    val groups: List<ReportGroup> = emptyList(),
)

@Serializable
data class ReportGroup(
    val source: String,
    val status: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("error_text") val errorText: String? = null,
)

@Serializable
data class RunOutput(
    @SerialName("report_date") val reportDate: String,
    @SerialName("run_dir") val runDir: String,
    @SerialName("raw_path") val rawPath: String,
    @SerialName("validation_path") val validationPath: String,
    @SerialName("llm_context_path") val llmContextPath: String,
    @SerialName("report_path") val reportPath: String,
    @SerialName("validation_passed") val validationPassed: Boolean,
    @SerialName("validator_exit_code") val validatorExitCode: Int,
)

enum class ValidatorExitClass(val code: Int) {
    OK(0),
    INPUT_DAMAGED(10),
    CONTRACT_MISMATCH(20),
    QUALITY_BLOCK(30),
    UNEXPECTED(40),
}

enum class RunClassification { SUCCESS, EXPECTED_BLOCK, UNEXPECTED_ERROR }

fun classifyRun(exitCode: Int, validationPassed: Boolean): RunClassification = when {
    exitCode == ValidatorExitClass.OK.code && validationPassed -> RunClassification.SUCCESS
    exitCode in setOf(10, 20, 30) && !validationPassed -> RunClassification.EXPECTED_BLOCK
    else -> RunClassification.UNEXPECTED_ERROR
}
