package com.dailynews.pipeline.editorial

import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

object FallbackReportRenderer {
    private const val TOP_N = 30
    private val line = "=".repeat(70)
    private val utcFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    fun render(
        raw: RawRun,
        validation: ValidationResult,
        reportDate: String,
        config: PipelineConfig = PipelineConfig(),
    ): String {
        val grouped = raw.articles.groupBy { it.source }
        val groups = validation.feedResults
        val topArticles = raw.articles.take(TOP_N)
        val configured = validation.counts.configured.takeIf { it > 0 } ?: raw.configuredFeedCount
        val failedGroups = groups.filter { it.status == "error" }
        return buildString {
            appendLine(line)
            appendLine("[$reportDate] RSS 每日精选 TOP $TOP_N")
            appendLine(line)
            appendLine()
            if (!validation.passed) {
                val reasons = validation.blockingReasons.filter(String::isNotBlank).joinToString("；")
                appendLine(if (reasons.isEmpty()) "校验状态：未通过" else "校验状态：未通过，阻断原因：$reasons")
            }
            appendLine("数据来源：${configured}个RSS源，共获取 ${raw.articles.size} 篇文章")
            appendLine("生成时间：$reportDate UTC")
            appendLine()
            if (failedGroups.isNotEmpty()) {
                appendLine("抓取异常：" + failedGroups.joinToString("；") { if (it.error.isNullOrBlank()) it.source else "${it.source} (${it.error})" })
                appendLine()
            }
            if (topArticles.isEmpty()) {
                appendLine("本次抓取结果为空，过去 24 小时未获取到可展示的文章。")
                appendLine()
            } else {
                topArticles.forEachIndexed { index, article ->
                    appendLine("${index + 1}. ${article.title}")
                    appendLine("   来源: ${article.source}")
                    appendLine("   时间: ${formatUtc(article.pubDateIso)}")
                    appendLine("   链接: ${article.link}")
                    appendLine("   摘要: " + if (article.summaryEn.isNotBlank()) clampText(article.summaryEn, config.render.part1SummaryMaxChars) else "（无）")
                    appendLine()
                }
            }
            appendLine(line)
            appendLine("按来源分组")
            appendLine(line)
            appendLine()
            groups.forEach { group ->
                val articles = grouped[group.source].orEmpty()
                val suffix = if (group.status == "error") "${articles.size}篇 · 抓取失败" else "${articles.size}篇"
                appendLine("--- ${group.source} ($suffix) ---")
                appendLine()
                if (group.status == "error") {
                    appendLine("抓取状态: ${group.error ?: "抓取失败"}")
                    appendLine()
                }
                if (articles.isEmpty()) {
                    appendLine("无文章")
                    appendLine()
                } else {
                    articles.forEachIndexed { index, article ->
                        appendLine("${index + 1}. ${article.title}")
                        appendLine("   时间: ${formatTime(article.pubDateIso)} | 链接: ${article.link}")
                        appendLine("   摘要: " + if (article.summaryEn.isNotBlank()) clampText(article.summaryEn, config.render.part2SummaryMaxChars) else "（无）")
                        appendLine()
                    }
                }
            }
            val part2Total = groups.sumOf { grouped[it.source].orEmpty().size }
            val uniqueSources = raw.uniqueSources ?: raw.articles.map { it.source }.distinct().sorted()
            val uniqueCount = raw.uniqueSourceCount ?: uniqueSources.size
            appendLine(line)
            appendLine("统计检查")
            appendLine(line)
            appendLine()
            appendLine("- feeds.json feed 数量: $configured")
            appendLine("- JSON count: ${raw.articles.size}")
            val uniqueSourcesJson = uniqueSources.joinToString(prefix = "[", postfix = "]", separator = ", ") {
                com.dailynews.model.ArtifactJson.compact.encodeToString(String.serializer(), it)
            }
            appendLine("- JSON 去重 source 列表: $uniqueSourcesJson")
            appendLine("- JSON 去重 source 数量: $uniqueCount")
            appendLine("- Part 1 文章数: ${topArticles.size}")
            appendLine("- Part 2 分组数: ${groups.size}")
            appendLine("- Part 2 文章总数: $part2Total")
            appendLine("- Part 2 分组数与 feeds.json 一致: ${yesNo(groups.size == configured)}")
            appendLine("- Part 2 文章总数与 JSON count 一致: ${yesNo(part2Total == raw.articles.size)}")
            appendLine("- JSON 去重 source 数量与 feeds.json 一致: ${yesNo(uniqueCount == configured)}")
            appendLine("- 校验结论: ${if (validation.passed) "通过" else "未通过"}")
            if (validation.warnings.isNotEmpty()) appendLine("- 校验警告: ${validation.warnings.filter(String::isNotBlank).joinToString("；")}")
            if (failedGroups.isNotEmpty()) {
                appendLine("- 抓取失败来源: " + failedGroups.joinToString("；") { if (it.error.isNullOrBlank()) it.source else "${it.source} (${it.error})" })
            }
            if (!validation.passed && validation.blockingReasons.isNotEmpty()) {
                appendLine("- 阻断原因: ${validation.blockingReasons.filter(String::isNotBlank).joinToString("；")}")
            }
        }
    }

    fun renderDamaged(reportDate: String, reason: String): String = buildString {
        appendLine(line)
        appendLine("[$reportDate] RSS 每日精选 TOP $TOP_N")
        appendLine(line)
        appendLine()
        appendLine("校验状态：未通过，阻断原因：${TextUtils.cleanText(reason)}")
        appendLine("输入产物损坏，无法安全恢复文章或来源明细；未生成正式报告。")
        appendLine()
        appendLine(line)
        appendLine("统计检查")
        appendLine(line)
        appendLine()
        appendLine("- 校验结论: 未通过")
        appendLine("- validator_exit_code: 10")
        appendLine("- 阻断原因: ${TextUtils.cleanText(reason)}")
    }

    fun failedOutputName(outputName: String): String = when {
        outputName.substringBeforeLast('.', outputName).endsWith(".failed") -> outputName
        outputName.endsWith(".md", ignoreCase = true) -> outputName.dropLast(3) + ".failed.md"
        else -> "$outputName.failed.md"
    }

    private fun clampText(value: String, limit: Int): String {
        val text = TextUtils.cleanText(value)
        if (limit <= 0 || text.length <= limit) return text
        return text.take(maxOf(0, limit - 1)).trimEnd() + "…"
    }

    private fun formatUtc(value: String): String = utcFormatter.format(parseInstant(value))
    private fun formatTime(value: String): String = timeFormatter.format(parseInstant(value))
    private fun parseInstant(value: String): Instant = Instant.parse(if (value.endsWith("+00:00")) value.dropLast(6) + "Z" else value)
    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}
