package com.dailynews.pipeline.editorial

import com.dailynews.model.Part2Context
import com.dailynews.model.LlmContext
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2DraftArticle
import com.dailynews.model.Part2DraftGroup
import com.dailynews.model.MissingPart2Summary
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.text.TextUtils

object Part2Merger {
    fun merge(context: Part2Context, missing: List<MissingPart2Summary>): Part2Draft {
        val missingByLink = missing.associateBy { TextUtils.cleanText(it.link) }
        val absent = mutableListOf<String>()
        var total = 0
        val groups = context.groups.map { group ->
            val articles = group.articles.mapNotNull { article ->
                // Both sides of the lookup must be cleaned, or a link carrying
                // stray whitespace silently reads as a missing summary.
                val supplied = missingByLink[TextUtils.cleanText(article.link)]
                val summary = if (!article.needsSummary) article.summaryZh else supplied?.summaryZh
                if (summary.isNullOrBlank()) {
                    absent += article.link.ifBlank { "<missing link>" }
                    null
                } else {
                    Part2DraftArticle(
                        title = article.title,
                        link = article.link,
                        pubDateIso = article.pubDateIso,
                        summaryZh = TextUtils.cleanText(summary),
                        noiseBucket = supplied?.noiseBucket ?: article.noiseBucket ?: "covered",
                        eventKey = supplied?.eventKey ?: article.eventKey.orEmpty(),
                    )
                }
            }
            total += articles.size
            Part2DraftGroup(group.source, group.status, articles.size, group.errorText, articles)
        }
        if (absent.isNotEmpty()) throw EditorialContractException(listOf("missing Part 2 summaries for links: $absent"))
        return Part2Draft(total, groups)
    }

    fun mergeCachedOnly(context: Part2Context): Part2Draft {
        var total = 0
        val groups = context.groups.map { group ->
            val articles = group.articles.mapNotNull { article ->
                article.summaryZh?.takeIf(String::isNotBlank)?.let { summary ->
                    Part2DraftArticle(
                        article.title,
                        article.link,
                        article.pubDateIso,
                        TextUtils.cleanText(summary),
                        article.noiseBucket ?: "covered",
                        article.eventKey.orEmpty(),
                    )
                }
            }
            total += articles.size
            Part2DraftGroup(group.source, group.status, articles.size, group.errorText, articles)
        }
        return Part2Draft(total, groups)
    }

    fun materializeLazy(context: LlmContext, validation: ValidationResult, cached: Part2Draft): Part2Draft {
        val cachedByLink = cached.groups.flatMap(Part2DraftGroup::articles).associateBy { TextUtils.cleanText(it.link) }
        val articlesByLink = context.allArticles.associateBy { TextUtils.cleanText(it.link) }
        val validationBySource = validation.feedResults.associateBy { it.source }
        var total = 0
        val groups = context.sourceGroups.map { group ->
            val articles = group.articleRefs.map { ref ->
                val authority = requireNotNull(articlesByLink[TextUtils.cleanText(ref.link)]) { "missing authority for ${ref.link}" }
                cachedByLink[TextUtils.cleanText(ref.link)] ?: Part2DraftArticle(
                    authority.title,
                    authority.link,
                    authority.pubDateIso,
                    summaryZh = "",
                )
            }
            total += articles.size
            Part2DraftGroup(
                group.source,
                group.status,
                articles.size,
                validationBySource[group.source]?.error,
                articles,
            )
        }
        return Part2Draft(total, groups)
    }
}
