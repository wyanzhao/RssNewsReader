package com.dailynews.pipeline

import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.context.ShortlistContextBuilder
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.EditorialCacheRecord
import com.dailynews.pipeline.ports.EditorialCacheStore
import com.dailynews.pipeline.validate.QcValidator
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** KEEP: deterministic shortlist cache injection and recent-event contracts. */
class ShortlistContextBuilderTest {
    @Test
    fun `injects linted part1 cache and three day continuity only`() = kotlinx.coroutines.runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        val context = LlmContextBuilder().build(raw, validation, "2026-04-10", "/report.md", config).llmContext
        val first = context.allArticles.first()
        val now = Instant.parse("2026-04-10T22:00:00Z")
        val good = EditorialCacheRecord(
            cacheKey = EditorialCacheKeys.cacheKey(first),
            link = first.link,
            source = first.source,
            title = first.title,
            part1SummaryZh = "可复用的昨日事件摘要",
            eventKey = "event-a",
            updatedAtUtc = now.minusSeconds(86_400),
        )
        val bad = EditorialCacheRecord(
            cacheKey = EditorialCacheKeys.cacheKey(context.allArticles[1]),
            link = context.allArticles[1].link,
            source = context.allArticles[1].source,
            title = context.allArticles[1].title,
            part1SummaryZh = "污染 https://example.com",
            eventKey = "event-b",
            updatedAtUtc = now.minusSeconds(86_400),
        )
        val old = good.copy(cacheKey = "old", link = "https://old", title = "Old", eventKey = "old", updatedAtUtc = now.minusSeconds(10 * 86_400))
        val store = FakeCache(listOf(good, bad, old))

        val result = ShortlistContextBuilder(store, ClockProvider { now })
            .build(context, context.allArticles.take(2).map { it.link })

        assertEquals(1, result.cacheHits)
        assertEquals("可复用的昨日事件摘要", result.articles[0].cachedSummaryZh)
        assertEquals("event-a", result.articles[0].cachedEventKey)
        assertNull(result.articles[1].cachedSummaryZh)
        assertEquals(listOf("event-a"), result.recentTopN.map { it.eventKey })
    }

    private class FakeCache(private val records: List<EditorialCacheRecord>) : EditorialCacheStore {
        override suspend fun find(cacheKey: String) = records.firstOrNull { it.cacheKey == cacheKey }
        override suspend fun recentSince(since: Instant) = records
        override suspend fun upsert(records: List<EditorialCacheRecord>) = Unit
        override suspend fun prune(before: Instant) = Unit
    }
}
