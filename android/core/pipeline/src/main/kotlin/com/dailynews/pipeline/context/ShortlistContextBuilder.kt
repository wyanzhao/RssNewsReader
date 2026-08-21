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
    /** 短引用 id（`a1`、`a2`…）。Part 1 计划只写这个，不回显 link。 */
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
    // wire 名是历史遗留：字段从来没有 30 条上限，它是 recent_top-N 的连续性材料。
    // prompt 按这个名字读，改名要同时动 Kotlin 与 markdown，收益只有整洁，故保留。
    @SerialName("recent_top30") val recentTopN: List<RecentTopNEvent>,
    val articles: List<ShortlistContextArticle>,
)

/** 跨日线索连续性的回看窗口。prompt 文案里的天数由 AssetPromptContractTest 钉死到这个常量。 */
const val RECENT_EVENT_WINDOW_DAYS = 7L

/**
 * `recent_top30[]` 的条数硬上限。
 *
 * 注意：`part1_shortlist_context` **不在 context_budget 的记账范围内**
 * （预算只覆盖 llm_context / part1_brief / part2_context），所以这条负载没有
 * 任何外部闸门。去重之后通常 120–180 条，这里再兜一道底。
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
                // 摘要 lint 与 event key 是两条独立的防线：lint 挡的是注入正文，
                // event key 有自己的形态守卫（sanitizeEventKey）。让摘要 lint 连坐掉 key
                // 会在摘要偶发超长时悄悄切断这条文章的线索连续性。
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
                    // 空 key 先归一化再去重，否则所有缺 key 的记录会塌进同一个桶，
                    // 把互不相干的事件当成同一条线索。
                    eventKey = EditorialCacheKeys.sanitizeEventKey(record.eventKey).ifEmpty {
                        EditorialCacheKeys.eventKey(null, record.title, record.link)
                    },
                    coveredOn = record.updatedAtUtc!!.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                )
            }
            .sortedWith(compareByDescending<RecentTopNEvent> { it.coveredOn }.thenByDescending { it.eventKey })
            // 窗口从 3 天放宽到 7 天后条数会翻倍。按线索去重（保留最新一次报道）
            // 才是模型真正需要的信息，重复的同一线索只是在烧 token。
            .distinctBy { it.eventKey }
            .take(RECENT_EVENT_CAP)
        return Part1ShortlistContext(context.meta, links.size, hits, recent, articles)
    }
}
