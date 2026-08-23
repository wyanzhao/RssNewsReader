package com.dailynews.pipeline

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.fetch.FeedFetchResult
import com.dailynews.pipeline.fetch.ArticlePoolKeys
import com.dailynews.pipeline.fetch.FetchStep
import com.dailynews.pipeline.fetch.SweepStep
import com.dailynews.pipeline.fetch.WindowSliceStep
import com.dailynews.pipeline.ports.ArticlePoolPort
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.FeedHealth
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.FetchLifecyclePort
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.PooledArticle
import com.dailynews.pipeline.ports.SeenLinksStore
import com.dailynews.pipeline.ports.SweepWrite
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** KEEP: native sweep/window behavior is the Android V2 oracle. */
class SweepAndWindowStepTest {
    private val date = LocalDate.parse("2026-08-04")
    private val now = Instant.parse("2026-08-04T12:00:00Z")

    /**
     * Timezone-suffix boundary for window comparison.
     *
     * Pool `pubDateIso` is `...+00:00`; the cutoff Instant's `toString()` is
     * `...Z`. Lexicographically 'Z'(0x5A) > '+'(0x2B), so a **non-normalized**
     * raw compare treats an article that falls exactly on the cutoff second as
     * earlier and drops it. That is the only bug that can be introduced when
     * swapping `julianday()` for an index-friendly range compare, so it must
     * have its own case.
     */
    @Test
    fun windowIncludesArticlesExactlyOnTheCutoffSecond() = runBlocking {
        val cutoff = Instant.parse("2026-08-04T10:00:00Z")
        val pool = FakePool()
        listOf(
            article("https://on-the-second", "2026-08-04T10:00:00+00:00"),
            article("https://one-second-earlier", "2026-08-03T09:59:59+00:00"),
            article("https://later", "2026-08-04T11:00:00+00:00"),
        ).forEach { pool.rows[ArticlePoolKeys.key(it)] = PooledArticle(it, false) }

        val inWindow = pool.articlesSince(cutoff).map { it.article.link }

        assertEquals(listOf("https://later", "https://on-the-second"), inWindow)
    }

    @Test
    fun emptyPoolMatchesLegacySingleShotOutput() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val fetched = listOf(
            article("https://two", "2026-08-04T11:00:00+00:00"),
            article("https://one/", "2026-08-04T10:00:00+00:00"),
            article("https://one", "2026-08-04T10:00:00+00:00"),
        )
        val source = FixedFeeds(listOf(feed))
        val seen = FakeSeenLinks()
        val fetchAll: suspend (List<FeedDefinition>, PipelineConfig) -> List<FeedFetchResult> = { _, _ ->
            listOf(FeedFetchResult(feed, fetched, newestItemDate = "2026-08-04T11:00:00+00:00"))
        }
        val enrich: suspend (List<Article>, PipelineConfig) -> List<Article> = { articles, _ ->
            articles.map { it.copy(articleText = "body:${it.title}") }
        }
        val legacy = FetchStep(
            source,
            fetchAll,
            enrich,
            seen,
            NetworkStatePort { true },
            RecordingLifecycle(),
            ClockProvider { now },
            runIdFactory = { _, _ -> "same-run" },
        )
        val pool = FakePool()
        val sweep = SweepStep(source, fetchAll, enrich, seen, NetworkStatePort { true }, pool, ClockProvider { now })
        val native = WindowSliceStep(
            source,
            sweep,
            pool,
            enrich,
            seen,
            NetworkStatePort { true },
            RecordingLifecycle(),
            ClockProvider { now },
            runIdFactory = { _, _ -> "same-run" },
        )

        assertEquals(
            legacy.fetch(date, 1, "manual", PipelineConfig()),
            native.fetch(date, 1, "manual", PipelineConfig()),
        )
    }

    @Test
    fun sweepSkipsPriorDaySeenLinksButKeepsSameDayLinksAndIsIdempotent() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val prior = article("https://prior", "2026-08-04T09:00:00+00:00")
        val sameDay = article("https://same", "2026-08-04T10:00:00+00:00")
        val pool = FakePool()
        val step = SweepStep(
            FixedFeeds(listOf(feed)),
            { _, _ -> listOf(FeedFetchResult(feed, listOf(prior, sameDay))) },
            { articles, _ -> articles },
            FakeSeenLinks(mapOf(prior.link to date.minusDays(1), sameDay.link to date)),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )

        val first = step.run(date, PipelineConfig())
        val second = step.run(date, PipelineConfig())

        assertEquals(listOf(sameDay.link), pool.rows.values.map { it.article.link })
        assertEquals(1, first.newArticleCount)
        assertEquals(0, second.newArticleCount)
        assertEquals(2, pool.writes.size)
    }

    @Test
    fun sweepPreservesPriorPageSummaryAndOnlyMarksActualEnrichment() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val raw = article("https://article", "2026-08-04T11:00:00+00:00").copy(summaryEn = "short")
        val pool = FakePool()
        var enrichCalls = 0
        val step = SweepStep(
            FixedFeeds(listOf(feed)),
            { _, _ -> listOf(FeedFetchResult(feed, listOf(raw))) },
            { articles, _ ->
                enrichCalls += 1
                articles.map { it.copy(summaryEn = "a much longer page metadata summary", articleText = "page body") }
            },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )

        step.run(date, PipelineConfig())
        step.run(date, PipelineConfig())

        val stored = pool.rows.getValue("https://article")
        assertEquals("a much longer page metadata summary", stored.article.summaryEn)
        assertEquals("page body", stored.article.articleText)
        assertTrue(!stored.needsEnrichment)
        assertEquals(1, enrichCalls)
    }

    @Test
    fun failedPageEnrichmentRemainsPendingForAFutureSweep() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val raw = article("https://article", "2026-08-04T11:00:00+00:00").copy(summaryEn = "short")
        val pool = FakePool()
        val step = SweepStep(
            FixedFeeds(listOf(feed)),
            { _, _ -> listOf(FeedFetchResult(feed, listOf(raw))) },
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )

        step.run(date, PipelineConfig())

        assertTrue(pool.rows.getValue("https://article").needsEnrichment)
    }

    @Test
    fun sweepPersistsTheWholePoolBeforeBoundedEnrichmentFails() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val fetched = (1..150).map { index ->
            article("https://article-$index", "2026-08-04T11:00:00+00:00").copy(summaryEn = "short")
        }
        val pool = FakePool()
        var attemptedBatchSize = 0
        val step = SweepStep(
            FixedFeeds(listOf(feed)),
            { _, _ -> listOf(FeedFetchResult(feed, fetched)) },
            { articles, _ -> attemptedBatchSize = articles.size; error("page host stalled") },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )

        assertFailsWith<IllegalStateException> { step.run(date, PipelineConfig()) }

        assertEquals(150, pool.rows.size)
        assertEquals(100, attemptedBatchSize)
        assertEquals(150, pool.rows.values.count(PooledArticle::needsEnrichment))
    }

    @Test
    fun linklessArticlesUseDistinctStablePoolIdentities() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val fetched = (1..12).map { index ->
            article("", "2026-08-04T${index.toString().padStart(2, '0')}:00:00+00:00").copy(title = "id-only-$index")
        }
        val pool = FakePool()
        val step = SweepStep(
            FixedFeeds(listOf(feed)),
            { _, _ -> listOf(FeedFetchResult(feed, fetched)) },
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { false },
            pool,
            ClockProvider { now },
        )

        step.run(date, PipelineConfig(wifiOnlyPageEnrichment = true))

        assertEquals(12, pool.rows.size)
        assertTrue(pool.rows.keys.all { it.startsWith("linkless:") })
    }

    @Test
    fun windowDerivesOkErrorEmptyMatrixAndSurvivesFinalSweepFailure() = runBlocking {
        val feeds = listOf(
            FeedDefinition("HasArticle", "https://ok"),
            FeedDefinition("Failed", "https://failed"),
            FeedDefinition("Empty", "https://empty"),
        )
        val pool = FakePool().apply {
            rows["https://article"] = PooledArticle(
                article("https://article", "2026-08-04T11:00:00+00:00", source = "HasArticle"),
                needsEnrichment = false,
            )
            health["HasArticle"] = FeedHealth("HasArticle", "error", "latest sweep failed", null)
            health["Failed"] = FeedHealth("Failed", "error", "HTTP 503", "2026-08-01T00:00:00+00:00")
            health["Empty"] = FeedHealth("Empty", "empty", null, null)
        }
        val source = FixedFeeds(feeds)
        val sweep = SweepStep(
            source,
            { _, _ -> error("offline") },
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )
        val lifecycle = RecordingLifecycle()
        val window = WindowSliceStep(
            source,
            sweep,
            pool,
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            lifecycle,
            ClockProvider { now },
            runIdFactory = { _, _ -> "pool-run" },
        )

        val raw = window.fetch(date, 1, "scheduled", PipelineConfig())

        assertEquals(listOf("ok", "error", "empty"), raw.feedResults.map { it.status })
        assertEquals("HTTP 503", raw.feedResults[1].error)
        assertEquals("2026-08-01T00:00:00+00:00", raw.feedResults[1].newestItemDate)
        assertEquals(1, raw.count)
        assertTrue(lifecycle.warnings.single().contains("continued from article pool"))
    }

    @Test
    fun finalSweepFailureWithEmptyPoolPreservesLegacyFailureSemantics() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val source = FixedFeeds(listOf(feed))
        val pool = FakePool()
        val sweep = SweepStep(
            source,
            { _, _ -> error("offline") },
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )
        val window = WindowSliceStep(
            source,
            sweep,
            pool,
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            RecordingLifecycle(),
            ClockProvider { now },
        )

        val failure = assertFailsWith<IllegalStateException> { window.fetch(date, 1, "manual", PipelineConfig()) }
        assertEquals("offline", failure.message)
    }

    @Test
    fun boundedRetryReusesExistingWindowWithoutRefetchingFeeds() = runBlocking {
        val feed = FeedDefinition("Source", "https://feed")
        val source = FixedFeeds(listOf(feed))
        val pool = FakePool().apply {
            rows["https://pooled"] = PooledArticle(
                article("https://pooled", "2026-08-04T11:00:00+00:00"),
                needsEnrichment = false,
            )
            health[feed.name] = FeedHealth(feed.name, "ok", null, null)
        }
        var fetchCalls = 0
        val sweep = SweepStep(
            source,
            { _, _ -> fetchCalls += 1; emptyList() },
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            pool,
            ClockProvider { now },
        )
        val lifecycle = RecordingLifecycle()
        val window = WindowSliceStep(
            source,
            sweep,
            pool,
            { articles, _ -> articles },
            FakeSeenLinks(),
            NetworkStatePort { true },
            lifecycle,
            ClockProvider { now },
            runIdFactory = { _, _ -> "retry-run" },
        )

        val raw = window.fetch(date, 2, "scheduled", PipelineConfig())

        assertEquals(0, fetchCalls)
        assertEquals(1, raw.count)
        assertEquals(listOf(date), lifecycle.retries)
    }

    private fun article(link: String, iso: String, source: String = "Source") = Article(
        source,
        link.substringAfterLast('/').ifBlank { "item" },
        link,
        "2026-08-04 00:00 UTC",
        iso,
        "summary",
        "",
    )

    private class FixedFeeds(private val feeds: List<FeedDefinition>) : FeedSource {
        override suspend fun enabledFeeds() = feeds
    }

    private class FakeSeenLinks(private val values: Map<String, LocalDate> = emptyMap()) : SeenLinksStore {
        override suspend fun entries() = values
        override suspend fun replace(entries: Map<String, LocalDate>) = Unit
        override suspend fun recordReportedLinks(links: List<String>, reportDate: LocalDate) = Unit
    }

    private class RecordingLifecycle : FetchLifecyclePort {
        val warnings = mutableListOf<String>()
        val retries = mutableListOf<LocalDate>()
        override suspend fun beforeRetry(reportDate: LocalDate) { retries += reportDate }
        override suspend fun recordStarted(raw: RawRun, reportDate: LocalDate, attempt: Int, trigger: String) = Unit
        override suspend fun recordWarning(raw: RawRun, step: String, message: String) { warnings += message }
    }

    private class FakePool : ArticlePoolPort {
        val rows = linkedMapOf<String, PooledArticle>()
        val health = linkedMapOf<String, FeedHealth>()
        val writes = mutableListOf<SweepWrite>()

        override suspend fun existingLinkKeys(linkKeys: Set<String>) = linkKeys.intersect(rows.keys)

        override suspend fun recordSweep(write: SweepWrite) {
            writes += write
            write.articles.forEach { article ->
                val key = ArticlePoolKeys.key(article)
                val prior = rows[key]
                val preservedSummary = if (
                    prior != null && prior.article.summaryEn.trim().length >= article.summaryEn.trim().length
                ) prior.article.summaryEn else article.summaryEn
                rows[key] = PooledArticle(
                    article = article.copy(
                        summaryEn = preservedSummary,
                        articleText = prior?.article?.articleText?.takeIf(String::isNotBlank) ?: article.articleText,
                    ),
                    needsEnrichment = when {
                        key in write.enrichedLinkKeys -> false
                        prior != null -> prior.needsEnrichment
                        else -> true
                    },
                )
            }
            write.feedOutcomes.forEach { outcome ->
                health[outcome.feedName] = FeedHealth(
                    outcome.feedName,
                    outcome.status,
                    outcome.error,
                    outcome.newestItemDateIso,
                )
            }
        }

        override suspend fun pendingEnrichment(from: Instant, limit: Int): List<Article> {
            val cutoff = from.toString().removeSuffix("Z") + "+00:00"
            return rows.values
                .filter(PooledArticle::needsEnrichment)
                .filter { it.article.pubDateIso >= cutoff }
                .sortedByDescending { it.article.pubDateIso }
                .take(limit)
                .map(PooledArticle::article)
        }

        // Production is a SQL lexicographic compare (`pubDateIso >= :fromIso`,
        // parameter normalized via toOffsetIso), not an Instant compare. A fake
        // that used OffsetDateTime.parse would diverge in two places: empty
        // pubDateIso would throw (production silently excludes it), and the
        // timezone-suffix difference at the boundary second would be flattened —
        // which is exactly the only bug that can be introduced when swapping
        // julianday for a range compare.
        override suspend fun articlesSince(from: Instant): List<PooledArticle> {
            val cutoff = from.toString().removeSuffix("Z") + "+00:00"
            return rows.values
                .filter { it.article.pubDateIso >= cutoff }
                .sortedByDescending { it.article.pubDateIso }
        }

        override suspend fun updateEnriched(articles: List<Article>, enrichedAt: Instant) {
            articles.forEach { article ->
                val key = ArticlePoolKeys.key(article)
                val prior = rows.getValue(key).article
                rows[key] = PooledArticle(
                    article.copy(
                        summaryEn = article.summaryEn.takeIf(String::isNotBlank) ?: prior.summaryEn,
                        articleText = article.articleText.takeIf(String::isNotBlank) ?: prior.articleText,
                    ),
                    needsEnrichment = false,
                )
            }
        }

        override suspend fun feedHealth(feedNames: Set<String>) = health.filterKeys { it in feedNames }
    }
}
