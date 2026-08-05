package com.dailynews.pipeline

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.fetch.FeedFetchResult
import com.dailynews.pipeline.fetch.FetchStep
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.FetchLifecyclePort
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.SeenLinksStore
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/** MIGRATION-GUARD: legacy single-shot fetch behavior retained through Phase C. */
class FetchStepTest {
    @Test
    fun retrySeenFilteringAndWifiGateAreOwnedByFetchStep() = runBlocking {
        val reportDate = LocalDate.parse("2026-08-04")
        val feed = FeedDefinition("Source", "https://feed")
        val seen = article("https://seen", "2026-08-04T01:00:00+00:00")
        val fresh = article("https://fresh", "2026-08-04T02:00:00+00:00")
        val lifecycle = RecordingLifecycle()
        var enrichmentCalled = false
        val step = FetchStep(
            feeds = object : FeedSource {
                override suspend fun enabledFeeds() = listOf(feed)
            },
            fetchAll = { _, _ -> listOf(FeedFetchResult(feed, listOf(fresh, seen, fresh))) },
            enrich = { articles, _ -> enrichmentCalled = true; articles },
            seenLinks = FakeSeenLinks(mapOf("https://seen" to LocalDate.parse("2026-08-03"))),
            networkState = NetworkStatePort { false },
            lifecycle = lifecycle,
            clock = ClockProvider { Instant.parse("2026-08-04T03:00:00Z") },
            runIdFactory = { _, attempt -> "run-$attempt" },
        )

        val raw = step.fetch(reportDate, attempt = 2, trigger = "manual", config = PipelineConfig(wifiOnlyPageEnrichment = true))

        assertEquals(listOf("https://fresh"), raw.articles.map { it.link })
        assertFalse(enrichmentCalled)
        assertEquals(listOf(reportDate), lifecycle.retries)
        assertEquals("run-2", lifecycle.started?.meta?.runId)
        assertEquals("manual", lifecycle.trigger)
    }

    private fun article(link: String, iso: String) = Article("Source", link, link, "2026-08-04 00:00 UTC", iso, "summary", "")

    private class FakeSeenLinks(private val values: Map<String, LocalDate>) : SeenLinksStore {
        override suspend fun entries() = values
        override suspend fun replace(entries: Map<String, LocalDate>) = Unit
        override suspend fun recordReportedLinks(links: List<String>, reportDate: LocalDate) = Unit
    }

    private class RecordingLifecycle : FetchLifecyclePort {
        val retries = mutableListOf<LocalDate>()
        var started: RawRun? = null
        var trigger: String? = null
        override suspend fun beforeRetry(reportDate: LocalDate) { retries += reportDate }
        override suspend fun recordStarted(raw: RawRun, reportDate: LocalDate, attempt: Int, trigger: String) {
            started = raw
            this.trigger = trigger
        }
    }
}
