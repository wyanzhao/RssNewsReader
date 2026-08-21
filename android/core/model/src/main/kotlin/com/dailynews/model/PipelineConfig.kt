package com.dailynews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val scheduleTimePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

fun isValidScheduleTime(value: String): Boolean = scheduleTimePattern.matches(value)

@Serializable
enum class Part2Mode { FULL, LAZY }

@Serializable
data class FetchConfig(
    val hours: Int = 28,
    @SerialName("max_summary") val maxSummary: Int = 300,
    @SerialName("stale_feed_warn_days") val staleFeedWarnDays: Int = 30,
)

@Serializable
data class SummaryEnrichmentConfig(
    @SerialName("short_summary_threshold") val shortSummaryThreshold: Int = 80,
    @SerialName("page_fallback_cap") val pageFallbackCap: Int = 300,
)

@Serializable
data class ArticleTextConfig(
    val enabled: Boolean = true,
    @SerialName("max_words") val maxWords: Int = 150,
    @SerialName("max_workers") val maxWorkers: Int = 4,
)

@Serializable
data class RenderConfig(
    @SerialName("part1_summary_max_chars") val part1SummaryMaxChars: Int = 200,
    @SerialName("part2_summary_max_chars") val part2SummaryMaxChars: Int = 200,
)

@Serializable
data class ContextBudgetConfig(
    @SerialName("llm_context_max_bytes") val llmContextMaxBytes: Int = 500_000,
    @SerialName("part1_brief_max_bytes") val part1BriefMaxBytes: Int = 150_000,
    @SerialName("part2_context_max_bytes") val part2ContextMaxBytes: Int = 150_000,
    @SerialName("total_context_max_bytes") val totalContextMaxBytes: Int = 800_000,
    @SerialName("hard_block") val hardBlock: Boolean = true,
)

@Serializable
data class LlmExecutionConfig(
    @SerialName("connect_timeout_seconds") val connectTimeoutSeconds: Int = 1_200,
    @SerialName("read_timeout_seconds") val readTimeoutSeconds: Int = 1_200,
    @SerialName("call_timeout_seconds") val callTimeoutSeconds: Int = 1_200,
) {
    fun normalized(): LlmExecutionConfig {
        val connect = connectTimeoutSeconds.coerceIn(5, 1_200)
        val read = readTimeoutSeconds.coerceIn(30, 1_200)
        val call = callTimeoutSeconds.coerceIn(maxOf(60, read), 1_200)
        return copy(
            connectTimeoutSeconds = connect,
            readTimeoutSeconds = read,
            callTimeoutSeconds = call,
        )
    }
}

@Serializable
data class PipelineConfig(
    val fetch: FetchConfig = FetchConfig(),
    @SerialName("summary_enrichment") val summaryEnrichment: SummaryEnrichmentConfig = SummaryEnrichmentConfig(),
    @SerialName("article_text") val articleText: ArticleTextConfig = ArticleTextConfig(),
    val render: RenderConfig = RenderConfig(),
    @SerialName("context_budget") val contextBudget: ContextBudgetConfig = ContextBudgetConfig(),
    @SerialName("llm_execution") val llmExecution: LlmExecutionConfig = LlmExecutionConfig(),
    @SerialName("part1_max_items") val part1MaxItems: Int = 30,
    @SerialName("schedule_time") val scheduleTime: String = "10:00",
    @SerialName("weekly_digest_enabled") val weeklyDigestEnabled: Boolean = true,
    @SerialName("monthly_digest_enabled") val monthlyDigestEnabled: Boolean = true,
    /** ISO 周几触发上一周的周报：1 = 周一。 */
    @SerialName("weekly_digest_weekday") val weeklyDigestWeekday: Int = 1,
    @SerialName("wifi_only_page_enrichment") val wifiOnlyPageEnrichment: Boolean = false,
    @SerialName("artifact_retention_days") val artifactRetentionDays: Int = 14,
    @SerialName("article_retention_days") val articleRetentionDays: Int = 30,
    /**
     * Part 2 报告条目的保留期。只作用于 `part = 2`——Part 1 是跨天线索与周期简报的
     * 素材，必须长期保留，不能和这把尺子共用。
     */
    @SerialName("report_retention_days") val reportRetentionDays: Int = 45,
    @SerialName("sweep_interval_minutes") val sweepIntervalMinutes: Int = 120,
    @SerialName("use_legacy_single_shot_fetch") val useLegacySingleShotFetch: Boolean = false,
    @SerialName("part2_mode") val part2Mode: Part2Mode = Part2Mode.FULL,
    @SerialName("monthly_token_budget") val monthlyTokenBudget: Long = 1_000_000,
    @SerialName("max_llm_calls_per_run") val maxLlmCallsPerRun: Int = 20,
    @SerialName("editor_feedback") val editorFeedback: List<String> = emptyList(),
) {
    fun normalized(): PipelineConfig = copy(
        fetch = fetch.copy(
            hours = fetch.hours.coerceIn(1, 168),
            maxSummary = fetch.maxSummary.coerceIn(1, 4_000),
            staleFeedWarnDays = fetch.staleFeedWarnDays.coerceAtLeast(1),
        ),
        articleText = articleText.copy(
            maxWords = articleText.maxWords.coerceIn(1, 2_000),
            maxWorkers = articleText.maxWorkers.coerceIn(1, 16),
        ),
        llmExecution = llmExecution.normalized(),
        part1MaxItems = part1MaxItems.coerceIn(10, 50),
        // Epic U：Part 2（按来源逐条中文摘要）已退出产品面，强制 LAZY 使 DRAFTER 成本归零。
        // normalized() 同时覆盖 DataStore 读/写、RunOrchestrator 与状态恢复四条路径，
        // 并自愈老设备已落盘的 "part2_mode":"FULL"。
        // 恢复步骤：删掉本行，并把 ui/report/ReportSections.kt 的 PART2_SECTION_ENABLED 改回 true。
        part2Mode = Part2Mode.LAZY,
        artifactRetentionDays = artifactRetentionDays.coerceIn(1, 365),
        articleRetentionDays = articleRetentionDays.coerceIn(1, 365),
        reportRetentionDays = reportRetentionDays.coerceIn(7, 365),
        sweepIntervalMinutes = sweepIntervalMinutes.coerceIn(15, 360),
        monthlyTokenBudget = monthlyTokenBudget.coerceAtLeast(0),
        maxLlmCallsPerRun = maxLlmCallsPerRun.coerceIn(4, 100),
        editorFeedback = editorFeedback.map(String::trim).filter(String::isNotEmpty).takeLast(20),
        weeklyDigestWeekday = weeklyDigestWeekday.coerceIn(1, 7),
    )
}
