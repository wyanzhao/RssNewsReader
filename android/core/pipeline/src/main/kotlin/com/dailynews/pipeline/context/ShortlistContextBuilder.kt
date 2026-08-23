package com.dailynews.pipeline.context

import com.dailynews.model.Article
import com.dailynews.model.LlmContext
import com.dailynews.model.LlmMeta
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.EditorialRefs
import com.dailynews.pipeline.flow.ShortlistContextFactory
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.EditorialCacheStore
import java.time.temporal.ChronoUnit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShortlistContextArticle(
    /** Short-ref id (`a1`, `a2`, …). The Part 1 plan writes only this and never echoes the link. */
    val id: String,
    val source: String,
    val title: String,
    val link: String,
    @SerialName("pub_date_utc") val pubDateUtc: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_en") val summaryEn: String,
    @SerialName("article_text") val articleText: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("cached_summary_zh") val cachedSummaryZh: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("cached_event_key") val cachedEventKey: String? = null,
)

@Serializable
data class RecentTopNEvent(
    val title: String,
    val source: String,
    @SerialName("event_key") val eventKey: String,
    @SerialName("covered_on") val coveredOn: String,
)

@Serializable
data class Part1ShortlistContext(
    val meta: LlmMeta,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("cache_hits") val cacheHits: Int,
    // The wire name is a historical leftover: the field never had a 30-entry cap; it is recent top-N continuity material.
    // The prompt reads it under this name, and renaming would touch both Kotlin and markdown for tidiness only, so it stays.
    @SerialName("recent_top30") val recentTopN: List<RecentTopNEvent>,
    val articles: List<ShortlistContextArticle>,
)

/** Lookback window for cross-day lead continuity. The day count in the prompt copy is pinned to this constant by AssetPromptContractTest. */
const val RECENT_EVENT_WINDOW_DAYS = 7L

/**
 * Hard cap on the entry count of `recent_top30[]`.
 *
 * Note: `part1_shortlist_context` is **not covered by context_budget accounting** (the budget only
 * covers llm_context / part1_brief / part2_context), so this payload has no external gate at all.
 * After dedup there are typically 120–180 entries; this adds one more safety floor.
 */
const val RECENT_EVENT_CAP = 150

class ShortlistContextBuilder(
    private val cache: EditorialCacheStore,
    private val clock: ClockProvider,
) : ShortlistContextFactory {
    override suspend fun build(context: LlmContext, links: List<String>): Part1ShortlistContext {
        val byLink = context.allArticles.associateBy(Article::link)
        require(links.all(byLink::containsKey)) { "shortlist contains links absent from all_articles" }
        var hits = 0
        val articles = links.mapIndexed { index, link ->
            val article = byLink.getValue(link)
            val record = cache.find(EditorialCacheKeys.cacheKey(article))
                ?: cache.find(EditorialCacheKeys.legacyCacheKey(article))
            val cachedSummary = record?.part1SummaryZh
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { EditorialContracts.summaryLintErrors(it, "cached summary_zh", 400).isEmpty() }
            if (cachedSummary != null) hits += 1
            ShortlistContextArticle(
                id = EditorialRefs.articleId(index),
                source = article.source,
                title = article.title,
                link = article.link,
                pubDateUtc = article.pubDateUtc,
                pubDateIso = article.pubDateIso,
                summaryEn = article.summaryEn,
                articleText = article.articleText,
                cachedSummaryZh = cachedSummary,
                // Summary lint and event key are two independent defense lines: lint blocks injected body text,
                // while the event key has its own shape guard (sanitizeEventKey). Letting a summary-lint failure
                // drag the key down with it would silently sever this article's lead continuity whenever a summary
                // happens to run over length.
                cachedEventKey = EditorialCacheKeys.sanitizeEventKey(record?.eventKey).takeIf { it.isNotEmpty() },
            )
        }
        val cutoff = clock.now().minus(RECENT_EVENT_WINDOW_DAYS, ChronoUnit.DAYS)
        val recent = cache.recentSince(cutoff)
            .filter { !it.part1SummaryZh.isNullOrBlank() && it.updatedAtUtc != null && !it.updatedAtUtc.isBefore(cutoff) }
            .filter { EditorialContracts.summaryLintErrors(it.part1SummaryZh, "recent cached summary_zh", 400).isEmpty() }
            .map { record ->
                RecentTopNEvent(
                    title = record.title,
                    source = record.source,
                    // Empty keys are normalized before dedup; otherwise all records missing a key would collapse
                    // into the same bucket, treating unrelated events as the same lead.
                    eventKey = EditorialCacheKeys.sanitizeEventKey(record.eventKey).ifEmpty {
                        EditorialCacheKeys.eventKey(null, record.title, record.link)
                    },
                    coveredOn = record.updatedAtUtc!!.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                )
            }
            .sortedWith(compareByDescending<RecentTopNEvent> { it.coveredOn }.thenByDescending { it.eventKey })
            // Widening the window from 3 days to 7 doubles the entry count. Deduping by lead (keeping the most
            // recent coverage) is what the model actually needs; duplicates of the same lead just burn tokens.
            .distinctBy { it.eventKey }
            .take(RECENT_EVENT_CAP)
        return Part1ShortlistContext(context.meta, links.size, hits, recent, articles)
    }
}
