package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.flow.SeenLinks
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.FetchLifecyclePort
import com.dailynews.pipeline.ports.FetchPort
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.SeenLinksStore
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FetchStep(
    private val feeds: FeedSource,
    private val fetchAll: suspend (List<FeedDefinition>, PipelineConfig) -> List<FeedFetchResult>,
    private val enrich: suspend (List<Article>, PipelineConfig) -> List<Article>,
    private val seenLinks: SeenLinksStore,
    private val networkState: NetworkStatePort,
    private val lifecycle: FetchLifecyclePort,
    private val clock: ClockProvider,
    private val runIdFactory: (Instant, Int) -> String = ::defaultRunId,
) : FetchPort {
    override suspend fun fetch(reportDate: LocalDate, attempt: Int, trigger: String, config: PipelineConfig): RawRun {
        if (attempt > 1) lifecycle.beforeRetry(reportDate)
        val configuredFeeds = feeds.enabledFeeds()
        val fetched = fetchAll(configuredFeeds, config)
        val deduped = RawSnapshotBuilder.dedup(fetched.flatMap { it.articles }).sortedByDescending { it.pubDateIso }
        val filtered = SeenLinks.filterPreviouslyReported(deduped, seenLinks.entries(), reportDate).articles
        val enriched = if (config.wifiOnlyPageEnrichment && !networkState.isWifiConnected()) filtered else enrich(filtered, config)
        val generatedAt = clock.now()
        val raw = RawSnapshotBuilder.build(
            configuredFeeds,
            fetched,
            enriched,
            generatedAt,
            runIdFactory(generatedAt, attempt),
            config,
        )
        lifecycle.recordStarted(raw, reportDate, attempt, trigger)
        return raw
    }

}

internal fun defaultRunId(generatedAt: Instant, attempt: Int): String =
    "rss-${generatedAt.toString().replace(Regex("[-:]"), "").substringBefore('.').removeSuffix("Z")}Z-${UUID.randomUUID().toString().take(8)}-a$attempt"
