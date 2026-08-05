package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.FeedResult
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawMeta
import com.dailynews.model.RawRun
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RawSnapshotBuilder {
    fun build(
        feeds: List<FeedDefinition>,
        fetched: List<FeedFetchResult>,
        articles: List<Article>,
        generatedAt: Instant,
        runId: String,
        config: PipelineConfig,
    ): RawRun {
        val feedOrder = feeds.mapIndexed { index, feed -> feed.name to index }.toMap()
        val deduped = dedup(articles).sortedWith(
            compareByDescending<Article> { parseArticleInstant(it.pubDateIso) }
                .thenBy { feedOrder[it.source] ?: Int.MAX_VALUE }
                .thenBy(Article::link),
        )
        val counts = deduped.groupingBy { it.source }.eachCount()
        val fetchedByName = fetched.associateBy { it.feed.name }
        val feedResults = feeds.map { feed ->
            val result = fetchedByName[feed.name]
            val count = counts.getOrDefault(feed.name, 0)
            FeedResult(
                feed.name,
                feed.url,
                when {
                    !result?.error.isNullOrBlank() -> "error"
                    count == 0 -> "empty"
                    else -> "ok"
                },
                result?.error,
                count,
                result?.newestItemDate,
            )
        }
        val unique = deduped.map { it.source }.distinct().sorted()
        val snapshot = buildJsonObject {
            put("config_path", "android://datastore/pipeline_config")
            put("fetch", buildJsonObject {
                put("stale_feed_warn_days", config.fetch.staleFeedWarnDays)
            })
            put("summary_enrichment", buildJsonObject {
                put("short_summary_threshold", config.summaryEnrichment.shortSummaryThreshold)
                put("page_fallback_cap", config.summaryEnrichment.pageFallbackCap)
                put("effective_page_fallback_cap", minOf(config.fetch.maxSummary, config.summaryEnrichment.pageFallbackCap))
            })
            put("article_text", buildJsonObject {
                put("enabled", config.articleText.enabled)
                put("max_words", config.articleText.maxWords)
                put("max_workers", config.articleText.maxWorkers)
            })
            put("render", buildJsonObject {
                put("part1_summary_max_chars", config.render.part1SummaryMaxChars)
                put("part2_summary_max_chars", config.render.part2SummaryMaxChars)
            })
            put("context_budget", buildJsonObject {
                put("llm_context_max_bytes", config.contextBudget.llmContextMaxBytes)
                put("part1_brief_max_bytes", config.contextBudget.part1BriefMaxBytes)
                put("part2_context_max_bytes", config.contextBudget.part2ContextMaxBytes)
                put("total_context_max_bytes", config.contextBudget.totalContextMaxBytes)
            })
        }
        return RawRun(
            RawMeta(generatedAt.toString(), runId, "feeds.json", feeds.size),
            deduped.size,
            deduped,
            feedResults,
            feeds.size,
            unique.size,
            unique,
            snapshot,
        )
    }

    private fun parseArticleInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrDefault(Instant.MIN)
        }

    fun dedup(articles: List<Article>): List<Article> {
        val seen = mutableSetOf<String>()
        return articles.filter { article ->
            val key = TextUtils.dedupLinkKey(article.link)
            key.isEmpty() || seen.add(key)
        }
    }
}
