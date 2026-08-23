package com.dailynews.pipeline.editorial

import com.dailynews.model.AssembledReport
import com.dailynews.model.LlmContext
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2Mode
import com.dailynews.model.ReportItem
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.text.TextUtils

/**
 * Deterministic assemble/review rejected an artifact that had **already passed the
 * LLM contract**.
 *
 * Previously shared a name with `flow.EditorialContractException`. The orchestrator
 * only imported the latter, so this class all fell into the classification fallback
 * and the user was told to "run it again" — which reproduces the failure exactly.
 * Same name, different thing was the entire cause of that bug, so this was renamed
 * rather than just adding a branch.
 */
class ReportContractException(val errors: List<String>) : IllegalArgumentException(errors.joinToString("; "))

class ReportAssembler {
    fun assemble(
        context: LlmContext,
        validation: ValidationResult,
        part1: Part1Plan,
        part2: Part2Draft,
        topN: Int = 30,
        reportPath: String? = null,
        renderTopN: Boolean = true,
        part2Mode: Part2Mode = Part2Mode.FULL,
    ): AssembledReport {
        require(validation.passed) { "validation.passed == true is required before report assembly" }
        val normalizedTopN = topN.coerceIn(10, 50)
        val errors = EditorialContracts.validateHandoffs(context, validation, part1, part2, normalizedTopN, reportPath, part2Mode)
        if (errors.isNotEmpty()) throw ReportContractException(errors)
        val renderedPart2 = if (part2Mode == Part2Mode.LAZY) Part2Merger.materializeLazy(context, validation, part2) else part2
        val renderedErrors = EditorialContracts.validatePart2(
            context,
            validation,
            renderedPart2,
            allowMissingSummaries = part2Mode == Part2Mode.LAZY,
        )
        if (renderedErrors.isNotEmpty()) throw ReportContractException(renderedErrors)

        val articleByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        val markdown = buildString {
            appendLine("# DailyNews · ${context.meta.date}")
            appendLine()
            appendLine("> 数据来源：${validation.counts.configured}个RSS源，共获取 ${validation.counts.articles} 篇文章")
            val errorGroups = renderedPart2.groups.filter { it.status == "error" }
            if (errorGroups.isNotEmpty()) {
                appendLine("> 抓取异常：" + errorGroups.joinToString("；") { "${it.source} (${it.errorText ?: "抓取失败"})" })
            }
            appendLine()
            appendLine("## Part 1：当日 TOP $normalizedTopN")
            appendLine()
            appendPart1Items(part1.items, articleByLink)
            appendLine("## Part 2：按来源分组")
            appendLine()
            renderedPart2.groups.forEach { group ->
                appendLine("### ${group.source} (${group.articleCount} 篇)")
                if (group.status == "error") {
                    appendLine("抓取状态：${group.errorText ?: "抓取失败"}")
                    appendLine()
                }
                if (group.articles.isEmpty()) {
                    appendLine("无文章")
                    appendLine()
                } else {
                    group.articles.forEachIndexed { index, article ->
                        val authority = articleByLink.getValue(TextUtils.cleanText(article.link))
                        appendLine("${index + 1}. ${markdownLink(authority.title, authority.link)}")
                        appendLine("   - 时间：${article.pubDateIso}")
                        appendLine("   - 摘要：${TextUtils.cleanText(article.summaryZh).ifBlank { LAZY_PLACEHOLDER }}")
                        appendLine()
                    }
                }
            }
            appendLine("## 统计检查")
            appendLine()
            appendLine("- Part 1 文章数：${part1.items.size}")
            appendLine("- Part 2 分组数：${renderedPart2.groups.size}")
            appendLine("- Part 2 文章总数：${renderedPart2.totalArticles}")
        }
        val topNMarkdown = if (renderTopN) TopNRenderer.render(context, part1, normalizedTopN, reportPath) else ""
        val items = buildList {
            // event_key is normalized into storage here: the model may omit it, but
            // report_items must be non-empty, otherwise the story view incorrectly
            // merges every "unfilled" item into one story line.
            part1.items.forEachIndexed { index, item ->
                val article = articleByLink.getValue(TextUtils.cleanText(item.link))
                val alsoLinks = item.alsoLinks.map { raw -> articleByLink.getValue(TextUtils.cleanText(raw)).link }
                val eventKey = EditorialCacheKeys.eventKey(item.eventKey, article.title, article.link)
                add(ReportItem(1, index + 1, article.link, article.title, article.source, article.pubDateUtc, article.pubDateIso, item.summaryZh, alsoLinks, eventKey))
            }
            var part2Position = 0
            renderedPart2.groups.forEach { group ->
                group.articles.forEach { item ->
                    part2Position += 1
                    val authority = articleByLink.getValue(TextUtils.cleanText(item.link))
                    val eventKey = EditorialCacheKeys.eventKey(item.eventKey, authority.title, authority.link)
                    add(ReportItem(2, part2Position, authority.link, authority.title, group.source, authority.pubDateUtc, item.pubDateIso, item.summaryZh, eventKey = eventKey))
                }
            }
        }
        return AssembledReport(
            context.meta.date,
            markdown,
            topNMarkdown,
            items,
            renderedPart2.groups.map { com.dailynews.model.ReportGroup(it.source, it.status, it.articleCount, it.errorText) },
        )
    }
}

object TopNRenderer {
    fun render(context: LlmContext, plan: Part1Plan, topN: Int = 30, reportPath: String? = null): String {
        val normalizedTopN = topN.coerceIn(10, 50)
        val errors = EditorialContracts.validatePart1(context, plan, normalizedTopN)
        if (errors.isNotEmpty()) throw ReportContractException(errors)
        val articleByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        return buildString {
            appendLine("# DailyNews Top $normalizedTopN · ${context.meta.date}")
            appendLine()
            if (plan.shortfall > 0) {
                appendLine("> 本日入选 ${plan.items.size} 条（不足 $normalizedTopN，缺口 ${plan.shortfall}）")
                appendLine()
            }
            appendPart1Items(plan.items, articleByLink)
            if (reportPath != null) {
                appendLine("---")
                appendLine("完整报告：$reportPath")
            }
        }
    }
}

data class ReportReview(val passed: Boolean, val errors: List<String>)

object ReportReviewer {
    fun review(
        markdown: String,
        reportPath: String,
        context: LlmContext,
        validation: ValidationResult,
        part1: Part1Plan,
        part2: Part2Draft,
        topN: Int = 30,
        part2Mode: Part2Mode = Part2Mode.FULL,
    ): ReportReview {
        val errors = EditorialContracts.validateHandoffs(context, validation, part1, part2, topN, reportPath, part2Mode).toMutableList()
        val reviewedPart2 = if (part2Mode == Part2Mode.LAZY) Part2Merger.materializeLazy(context, validation, part2) else part2
        errors += EditorialContracts.validatePart2(
            context,
            validation,
            reviewedPart2,
            allowMissingSummaries = part2Mode == Part2Mode.LAZY,
        )
        reviewedPart2.groups.flatMap { it.articles }.forEach { article ->
            if (TextUtils.cleanText(article.link) !in markdown) errors += "report missing link: ${article.link}"
            val escapedTitle = markdownLinkText(article.title)
            if (escapedTitle !in markdown) errors += "report missing title: $escapedTitle"
        }
        part1.items.forEach { item ->
            if (TextUtils.cleanText(item.link) !in markdown) errors += "report missing Part 1 link: ${item.link}"
        }
        return ReportReview(errors.isEmpty(), errors)
    }
}

private const val LAZY_PLACEHOLDER = "展开来源后生成中文摘要"

private fun StringBuilder.appendPart1Items(
    items: List<Part1PlanItem>,
    articleByLink: Map<String, com.dailynews.model.Article>,
) {
    if (items.isEmpty()) {
        appendLine("本次没有可进入 Part 1 的文章。")
        appendLine()
        return
    }
    items.forEachIndexed { index, item ->
        val article = articleByLink.getValue(TextUtils.cleanText(item.link))
        appendLine("${index + 1}. ${markdownLink(article.title, article.link)}")
        appendLine("   - 来源：${article.source}")
        appendLine("   - 时间：${article.pubDateUtc}")
        appendLine("   - 摘要：${TextUtils.cleanText(item.summaryZh)}")
        val related = item.alsoLinks.map { articleByLink.getValue(TextUtils.cleanText(it)) }.joinToString("；") { also ->
            "${also.source}: ${TextUtils.cleanText(also.title)}"
        }
        if (related.isNotEmpty()) appendLine("   - 相关来源：$related")
        appendLine()
    }
}

private fun markdownLinkText(value: String): String = TextUtils.cleanText(value).replace("[", "\\[").replace("]", "\\]")

private fun markdownLink(title: String, link: String): String {
    val target = TextUtils.cleanText(link).let { if (it.any { char -> char == '(' || char == ')' || char == ' ' }) "<$it>" else it }
    return "[${markdownLinkText(title)}]($target)"
}
