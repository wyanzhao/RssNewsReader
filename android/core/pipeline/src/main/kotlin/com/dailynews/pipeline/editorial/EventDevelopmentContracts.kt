package com.dailynews.pipeline.editorial

import com.dailynews.model.Part1Plan
import com.dailynews.pipeline.context.Part1ShortlistContext

/** Structural evidence checks, not independent semantic verification of a model's novelty claim. */
object EventDevelopmentContracts {
    fun errors(plan: Part1Plan, context: Part1ShortlistContext): List<String> = buildList {
        val history = (context.recentTopN + context.watchedHistory.mapNotNull { it.latest })
            .filter { it.link.isNotBlank() && it.summaryZh.isNotBlank() && it.coveredOn < context.meta.date &&
                runCatching { java.time.LocalDate.parse(it.coveredOn) }.isSuccess &&
                EditorialContracts.summaryLintErrors(it.summaryZh, "historical summary_zh", 400).isEmpty() }
            .groupBy { it.eventKey }.mapValues { (_, rows) -> rows.maxBy { it.coveredOn } }
        val articles = context.articles.associateBy { it.link }
        plan.items.forEachIndexed { index, item ->
            val label = "part1 item ${index + 1} development"
            val cachedKey = articles[item.link]?.cachedEventKey
            if (cachedKey != null && cachedKey in history && item.eventKey != cachedKey) {
                add("$label event_key must retain the known cached key $cachedKey")
            }
            val baseline = history[item.eventKey]
            val progress = item.development
            if (baseline == null) {
                if (progress != null) add("$label has no usable historical baseline; use null")
            } else if (progress == null) {
                add("$label required for previously covered event ${item.eventKey}; describe actual new facts or exclude")
            } else {
                if (progress.baselineDate != baseline.coveredOn) add("$label baseline_date must be ${baseline.coveredOn}")
                if (progress.changeZh.isBlank()) add("$label change_zh is empty")
                addAll(EditorialContracts.summaryLintErrors(progress.changeZh, "$label change_zh", 200))
                if (progress.evidenceLink !in listOf(item.link) + item.alsoLinks) add("$label evidence must belong to this item's ref or also_refs")
                val article = articles[progress.evidenceLink]
                val quote = progress.evidenceQuote
                if (quote.length !in 10..400 || quote.isBlank()) add("$label evidence_quote must contain 10-400 source characters")
                else if (article == null || !(article.articleText.contains(quote) || article.summaryEn.contains(quote))) {
                    add("$label evidence_quote is not an exact excerpt of current article_text or summary_en")
                }
            }
        }
    }
}
