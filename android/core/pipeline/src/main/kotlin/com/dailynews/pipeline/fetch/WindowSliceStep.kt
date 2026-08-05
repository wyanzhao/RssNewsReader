package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.flow.SeenLinks
import com.dailynews.pipeline.ports.ArticlePoolPort
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.FetchLifecyclePort
import com.dailynews.pipeline.ports.FetchPort
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.SeenLinksStore
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** Final sweep plus deterministic article-pool window projection. */
class WindowSliceStep(
    private val feeds: FeedSource,
    private val sweep: SweepStep,
    private val pool: ArticlePoolPort,
    private val enrich: suspend (List<Article>, PipelineConfig) -> List<Article>,
    private val seenLinks: SeenLinksStore,
    private val networkState: NetworkStatePort,
    private val lifecycle: FetchLifecyclePort,
    private val clock: ClockProvider,
    private val runIdFactory: (Instant, Int) -> String = ::defaultRunId,
) : FetchPort {
    override suspend fun fetch(reportDate: LocalDate, attempt: Int, trigger: String, config: PipelineConfig): RawRun {
        if (attempt > 1) lifecycle.beforeRetry(reportDate)
        val initialNow = clock.now()
        val initialFrom = initialNow.minusSeconds(config.fetch.hours * 3_600L)
        val existingWindow = if (attempt > 1) pool.articlesSince(initialFrom) else emptyList()
        val sweepFailure = if (attempt == 1 || existingWindow.isEmpty()) {
            try {
                sweep.run(reportDate, config)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
        } else {
            null
        }
        val generatedAt = clock.now()
        val from = generatedAt.minusSeconds(config.fetch.hours * 3_600L)
        var pooled = if (existingWindow.isNotEmpty() && from == initialFrom) existingWindow else pool.articlesSince(from)
        if (sweepFailure != null && pooled.isEmpty()) throw sweepFailure

        val filtered = SeenLinks.filterPreviouslyReported(
            pooled.map { it.article },
            seenLinks.entries(),
            reportDate,
        ).articles
        val filteredKeys = filtered.mapTo(hashSetOf(), ArticlePoolKeys::key)
        val pendingCandidates = pooled.filter { it.needsEnrichment && ArticlePoolKeys.key(it.article) in filteredKeys }
            .map { it.article }
        val pending = pendingCandidates.take(MAX_ENRICHMENT_BATCH)
        if (pending.isNotEmpty() && (!config.wifiOnlyPageEnrichment || networkState.isWifiConnected())) {
            val enriched = actualEnrichmentResults(pending, enrich(pending, config))
            if (enriched.isNotEmpty()) pool.updateEnriched(enriched, generatedAt)
            val replacements = enriched.associateBy(ArticlePoolKeys::key)
            pooled = pooled.map { row ->
                val replacement = replacements[ArticlePoolKeys.key(row.article)]
                if (replacement == null) row else row.copy(article = replacement, needsEnrichment = false)
            }
        }
        val currentArticles = pooled.map { it.article }
            .filter { ArticlePoolKeys.key(it) in filteredKeys }
        val configuredFeeds = feeds.enabledFeeds()
        val health = pool.feedHealth(configuredFeeds.mapTo(linkedSetOf(), FeedDefinition::name))
        val counts = currentArticles.groupingBy(Article::source).eachCount()
        val syntheticResults = configuredFeeds.map { feed ->
            val state = health[feed.name]
            val count = counts.getOrDefault(feed.name, 0)
            FeedFetchResult(
                feed = feed,
                articles = currentArticles.filter { it.source == feed.name },
                error = state?.lastError.takeIf { count == 0 && state?.lastStatus == "error" },
                newestItemDate = state?.newestItemDateIso,
            )
        }
        val raw = RawSnapshotBuilder.build(
            configuredFeeds,
            syntheticResults,
            currentArticles,
            generatedAt,
            runIdFactory(generatedAt, attempt),
            config,
        )
        lifecycle.recordStarted(raw, reportDate, attempt, trigger)
        if (sweepFailure != null) {
            lifecycle.recordWarning(
                raw,
                "final_sweep",
                "final sweep failed; continued from article pool: ${sweepFailure::class.simpleName}: ${sweepFailure.message}",
            )
        }
        if (pendingCandidates.size > MAX_ENRICHMENT_BATCH) {
            lifecycle.recordWarning(
                raw,
                "page_enrichment",
                "page enrichment capped at $MAX_ENRICHMENT_BATCH articles; ${pendingCandidates.size - MAX_ENRICHMENT_BATCH} remain pending for a future sweep",
            )
        }
        return raw
    }

    private companion object {
        const val MAX_ENRICHMENT_BATCH = 200
    }
}
