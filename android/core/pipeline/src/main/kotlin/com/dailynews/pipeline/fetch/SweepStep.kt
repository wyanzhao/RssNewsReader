package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.pipeline.flow.SeenLinks
import com.dailynews.pipeline.ports.ArticlePoolPort
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.SeenLinksStore
import com.dailynews.pipeline.ports.SweepFeedOutcome
import com.dailynews.pipeline.ports.SweepWrite
import java.time.LocalDate

data class SweepResult(
    val feedCount: Int,
    val fetchedArticleCount: Int,
    val acceptedArticleCount: Int,
    val newArticleCount: Int,
)

/**
 * JVM-only incremental fetch step. It owns network fetch composition and writes
 * through [ArticlePoolPort], while Android/Room remain outside this module.
 */
class SweepStep(
    private val feeds: FeedSource,
    private val fetchAll: suspend (List<FeedDefinition>, PipelineConfig) -> List<FeedFetchResult>,
    private val enrich: suspend (List<Article>, PipelineConfig) -> List<Article>,
    private val seenLinks: SeenLinksStore,
    private val networkState: NetworkStatePort,
    private val pool: ArticlePoolPort,
    private val clock: ClockProvider,
) {
    suspend fun run(reportDate: LocalDate, config: PipelineConfig): SweepResult {
        val configuredFeeds = feeds.enabledFeeds()
        val fetched = fetchAll(configuredFeeds, config)
        val fetchedArticles = RawSnapshotBuilder.dedup(fetched.flatMap(FeedFetchResult::articles))
            .sortedByDescending(Article::pubDateIso)
        val accepted = SeenLinks.filterPreviouslyReported(fetchedArticles, seenLinks.entries(), reportDate).articles
        val keys = accepted.mapTo(linkedSetOf(), ArticlePoolKeys::key)
        val existing = pool.existingLinkKeys(keys)
        val newArticles = accepted.filter { ArticlePoolKeys.key(it) !in existing }
        val canEnrich = !config.wifiOnlyPageEnrichment || networkState.isWifiConnected()
        val fetchedAt = clock.now()
        // Persist the fetched pool and feed-health snapshot before page enrichment. A slow or
        // cancelled enrichment pass must never discard the whole sweep.
        pool.recordSweep(
            SweepWrite(
                fetchedAt = fetchedAt,
                articles = accepted,
                enrichedLinkKeys = newArticles
                    .filterNot { it.needsPageEnrichment(config) }
                    .mapTo(linkedSetOf(), ArticlePoolKeys::key),
                feedOutcomes = configuredFeeds.map { feed ->
                    val result = fetched.firstOrNull { it.feed.name == feed.name }
                    val error = result?.error
                    SweepFeedOutcome(
                        feedName = feed.name,
                        status = when {
                            !error.isNullOrBlank() -> "error"
                            result == null || result.articles.isEmpty() -> "empty"
                            else -> "ok"
                        },
                        error = error,
                        itemCount = result?.articles?.size ?: 0,
                        newestItemDateIso = result?.newestItemDate,
                    )
                },
            ),
        )
        if (canEnrich) {
            val pending = pool.pendingEnrichment(
                fetchedAt.minusSeconds(config.fetch.hours * 3_600L),
                MAX_SWEEP_ENRICHMENT_BATCH,
            )
            if (pending.isNotEmpty()) {
                val enriched = actualEnrichmentResults(pending, enrich(pending, config))
                if (enriched.isNotEmpty()) pool.updateEnriched(enriched, fetchedAt)
            }
        }
        return SweepResult(configuredFeeds.size, fetchedArticles.size, accepted.size, newArticles.size)
    }

    private companion object {
        // Keep each sweep bounded even though the shared runtime deadline is 20 minutes.
        const val MAX_SWEEP_ENRICHMENT_BATCH = 100
    }
}

internal fun Article.needsPageEnrichment(config: PipelineConfig): Boolean =
    summaryEn.trim().length < config.summaryEnrichment.shortSummaryThreshold ||
        (config.articleText.enabled && articleText.isBlank())

internal fun actualEnrichmentResults(inputs: List<Article>, outputs: List<Article>): List<Article> {
    val inputByKey = inputs.associateBy(ArticlePoolKeys::key)
    return outputs.filter { output ->
        val input = inputByKey[ArticlePoolKeys.key(output)] ?: return@filter false
        output.summaryEn.trim().length > input.summaryEn.trim().length ||
            (input.articleText.isBlank() && output.articleText.isNotBlank())
    }
}
