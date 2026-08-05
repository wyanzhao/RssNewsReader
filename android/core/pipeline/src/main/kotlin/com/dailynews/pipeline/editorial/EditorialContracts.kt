package com.dailynews.pipeline.editorial

import com.dailynews.model.LlmContext
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2Mode
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.text.TextUtils

data class ArtifactAudit(
    val passed: Boolean,
    val errors: List<String>,
    val articleCount: Int,
    val sourceCount: Int,
)

object EditorialContracts {
    const val PART1_SUMMARY_HARD_CAP = 400
    const val PART2_SUMMARY_HARD_CAP = 200
    const val MIN_TOP_N = 10
    const val MAX_TOP_N = 50

    fun summaryLintErrors(summary: String?, label: String, hardCap: Int): List<String> {
        val cleaned = TextUtils.cleanText(summary)
        return buildList {
            if (cleaned.length > hardCap) add("$label summary_zh exceeds $hardCap chars (${cleaned.length})")
            val lowered = cleaned.lowercase()
            if ("http://" in lowered || "https://" in lowered || "](" in cleaned) {
                add("$label summary_zh must not contain links")
            }
        }
    }

    fun audit(context: LlmContext, validation: ValidationResult): ArtifactAudit {
        val errors = mutableListOf<String>()
        val articleByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        val expected = validation.counts.articles
        if (context.allArticles.size != expected) {
            errors += "all_articles length ${context.allArticles.size} != validation.counts.articles $expected"
        }
        var groupedTotal = 0
        context.sourceGroups.forEach { group ->
            if (group.articleCount != group.articleRefs.size) {
                errors += "${group.source} article_count ${group.articleCount} != article_refs ${group.articleRefs.size}"
            }
            groupedTotal += group.articleRefs.size
            group.articleRefs.forEach { ref ->
                if (ref.link !in articleByLink) errors += "${group.source} article ref not found in all_articles: ${ref.link}"
            }
        }
        if (groupedTotal != expected) {
            errors += "source_groups article_refs total $groupedTotal != validation.counts.articles $expected"
        }
        if (context.sourceGroups.map { it.source } != validation.feedResults.map { it.source }) {
            errors += "source_groups source order does not match validation.feed_results"
        }
        val feedsBySource = validation.feedResults.associateBy { it.source }
        context.sourceGroups.filter { it.status == "error" }.forEach { group ->
            if (feedsBySource[group.source]?.error.isNullOrBlank()) {
                errors += "${group.source} has status=error but no validation.feed_results error text"
            }
        }
        return ArtifactAudit(errors.isEmpty(), errors, context.allArticles.size, context.sourceGroups.size)
    }

    fun validatePart1(context: LlmContext, plan: Part1Plan, topN: Int): List<String> {
        val errors = mutableListOf<String>()
        val normalizedTopN = topN.coerceIn(MIN_TOP_N, MAX_TOP_N)
        val articleByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        if (plan.items.size > normalizedTopN) errors += "part1 items exceed $normalizedTopN (${plan.items.size})"
        val expectedShortfall = maxOf(0, normalizedTopN - plan.items.size)
        if (plan.shortfall != expectedShortfall) {
            errors += "part1_plan shortfall ${plan.shortfall} != expected $expectedShortfall ($normalizedTopN - ${plan.items.size} items)"
        }
        val mainLinks = plan.items.map { TextUtils.cleanText(it.link) }.filter(String::isNotEmpty).toSet()
        val seenMain = mutableSetOf<String>()
        val alsoSeen = mutableMapOf<String, Int>()
        plan.items.forEachIndexed { zeroIndex, item ->
            val index = zeroIndex + 1
            val link = TextUtils.cleanText(item.link)
            if (link.isEmpty()) {
                errors += "part1 item $index missing link"
                return@forEachIndexed
            }
            if (!seenMain.add(link)) errors += "part1 item $index duplicates link $link"
            if (link !in articleByLink) errors += "part1 item $index link absent from all_articles: $link"
            if (TextUtils.cleanText(item.summaryZh).isEmpty()) {
                errors += "part1 item $index missing summary_zh"
            } else {
                errors += summaryLintErrors(item.summaryZh, "part1 item $index", PART1_SUMMARY_HARD_CAP)
            }
            item.alsoLinks.forEach { rawAlsoLink ->
                val alsoLink = TextUtils.cleanText(rawAlsoLink)
                when {
                    alsoLink.isEmpty() -> errors += "part1 item $index has an empty also_link"
                    alsoLink == link -> errors += "part1 item $index also_links repeats its own link"
                    alsoLink in mainLinks -> errors += "part1 item $index also_link duplicates another item's link: $alsoLink"
                    alsoLink in alsoSeen -> errors += "part1 item $index also_link already used by item ${alsoSeen[alsoLink]}: $alsoLink"
                    else -> alsoSeen[alsoLink] = index
                }
                if (alsoLink.isNotEmpty() && alsoLink !in articleByLink) {
                    errors += "part1 item $index also_link absent from all_articles: $alsoLink"
                }
            }
        }
        return errors
    }

    fun validatePart2(
        context: LlmContext,
        validation: ValidationResult,
        draft: Part2Draft,
        allowMissingSummaries: Boolean = false,
        allowPartial: Boolean = false,
    ): List<String> {
        val errors = mutableListOf<String>()
        val articleByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        val expectedSources = context.sourceGroups.map { it.source }
        val seenLinks = mutableSetOf<String>()
        if (draft.groups.map { it.source } != expectedSources) {
            errors += "part2 groups source order does not match llm_context.source_groups"
        }
        var total = 0
        draft.groups.forEach { group ->
            total += group.articles.size
            if (group.articleCount != group.articles.size) {
                errors += "${group.source} article_count ${group.articleCount} != articles ${group.articles.size}"
            }
            group.articles.forEachIndexed { zeroIndex, article ->
                val index = zeroIndex + 1
                if (article.title.isBlank()) errors += "${group.source} article $index missing title"
                if (article.link.isBlank()) errors += "${group.source} article $index missing link"
                if (article.pubDateIso.isBlank()) errors += "${group.source} article $index missing pub_date_iso"
                if (!allowMissingSummaries && article.summaryZh.isBlank()) errors += "${group.source} article $index missing summary_zh"
                errors += summaryLintErrors(article.summaryZh, "${group.source} article $index", PART2_SUMMARY_HARD_CAP)
                val cleanLink = TextUtils.cleanText(article.link)
                if (!seenLinks.add(cleanLink)) errors += "${group.source} article $index duplicates link: $cleanLink"
                val authority = articleByLink[cleanLink]
                if (authority == null) {
                    errors += "${group.source} article $index link absent from all_articles: ${article.link}"
                } else {
                    if (article.title != authority.title) errors += "${group.source} article $index title changed for ${article.link}"
                    if (authority.source != group.source) {
                        errors += "${group.source} article $index link belongs to source ${authority.source}: ${article.link}"
                    }
                }
            }
        }
        if (!allowPartial && total != validation.counts.articles) {
            errors += "part2 total $total != validation.counts.articles ${validation.counts.articles}"
        }
        if (draft.totalArticles != total) errors += "part2 total_articles ${draft.totalArticles} != counted total $total"
        return errors
    }

    fun validateHandoffs(
        context: LlmContext,
        validation: ValidationResult,
        part1: Part1Plan,
        part2: Part2Draft,
        topN: Int,
        reportPath: String? = null,
        part2Mode: Part2Mode = Part2Mode.FULL,
    ): List<String> = buildList {
        addAll(audit(context, validation).errors)
        addAll(validatePart1(context, part1, topN))
        addAll(validatePart2(context, validation, part2, allowPartial = part2Mode == Part2Mode.LAZY))
        if (reportPath?.endsWith(".failed.md") == true) add("report_path must not be a failed report")
    }
}
