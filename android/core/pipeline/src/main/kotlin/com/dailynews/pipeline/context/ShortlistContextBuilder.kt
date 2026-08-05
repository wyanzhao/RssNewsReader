package com.dailynews.pipeline.context

import com.dailynews.model.Article
import com.dailynews.model.LlmContext
import com.dailynews.model.LlmMeta
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.editorial.EditorialContracts
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
    @SerialName("recent_top30") val recentTopN: List<RecentTopNEvent>,
    val articles: List<ShortlistContextArticle>,
)

class ShortlistContextBuilder(
    private val cache: EditorialCacheStore,
    private val clock: ClockProvider,
) : ShortlistContextFactory {
    override suspend fun build(context: LlmContext, links: List<String>): Part1ShortlistContext {
        val byLink = context.allArticles.associateBy(Article::link)
        require(links.all(byLink::containsKey)) { "shortlist contains links absent from all_articles" }
        var hits = 0
        val articles = links.map { link ->
            val article = byLink.getValue(link)
            val record = cache.find(EditorialCacheKeys.cacheKey(article))
                ?: cache.find(EditorialCacheKeys.legacyCacheKey(article))
            val cachedSummary = record?.part1SummaryZh
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { EditorialContracts.summaryLintErrors(it, "cached", 400).isEmpty() }
            if (cachedSummary != null) hits += 1
            ShortlistContextArticle(
                source = article.source,
                title = article.title,
                link = article.link,
                pubDateUtc = article.pubDateUtc,
                pubDateIso = article.pubDateIso,
                summaryEn = article.summaryEn,
                articleText = article.articleText,
                cachedSummaryZh = cachedSummary,
                cachedEventKey = record?.eventKey?.trim()?.takeIf { cachedSummary != null && it.isNotEmpty() },
            )
        }
        val cutoff = clock.now().minus(3, ChronoUnit.DAYS)
        val recent = cache.recentSince(cutoff)
            .filter { !it.part1SummaryZh.isNullOrBlank() && it.updatedAtUtc != null && !it.updatedAtUtc.isBefore(cutoff) }
            .filter { EditorialContracts.summaryLintErrors(it.part1SummaryZh, "recent cached", 400).isEmpty() }
            .map { record ->
                RecentTopNEvent(
                    title = record.title,
                    source = record.source,
                    eventKey = record.eventKey.orEmpty(),
                    coveredOn = record.updatedAtUtc!!.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                )
            }
            .sortedWith(compareByDescending<RecentTopNEvent> { it.coveredOn }.thenByDescending { it.eventKey })
        return Part1ShortlistContext(context.meta, links.size, hits, recent, articles)
    }
}
