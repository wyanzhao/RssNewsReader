package com.dailynews.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.dailynews.data.db.ArticleDetail
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.FetchLogEntity
import com.dailynews.data.db.FeedUnreadCount
import com.dailynews.data.db.ReaderArticle
import com.dailynews.data.db.ReaderDayCount
import com.dailynews.model.Article
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.fetch.ArticlePoolKeys
import com.dailynews.pipeline.ports.FetchPort
import com.dailynews.pipeline.ports.ArticlePoolPort
import com.dailynews.pipeline.ports.FeedHealth
import com.dailynews.pipeline.ports.PooledArticle
import com.dailynews.pipeline.ports.SweepFeedOutcome
import com.dailynews.pipeline.ports.SweepWrite
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ArticleRepository(private val database: DailyNewsDatabase) : ArticlePoolPort {
    fun observeCountSince(from: Instant): Flow<Int> = database.articles().observeCountSince(from.toOffsetIso())

    fun observeReadLinks(): Flow<Set<String>> = database.articles().observeReadLinks().map { it.toSet() }
    suspend fun recordFetch(raw: RawRun) = recordSweep(
        SweepWrite(
            fetchedAt = Instant.parse(raw.meta.generatedAtUtc),
            articles = raw.articles,
            enrichedLinkKeys = raw.articles.filter { it.articleText.isNotBlank() }
                .mapTo(linkedSetOf(), ArticlePoolKeys::key),
            feedOutcomes = raw.feedResults.map { result ->
                SweepFeedOutcome(
                    result.source,
                    result.status,
                    result.error,
                    result.articleCount,
                    result.newestItemDate,
                )
            },
        ),
    )

    override suspend fun existingLinkKeys(linkKeys: Set<String>): Set<String> =
        linkKeys.chunked(SQLITE_BIND_CHUNK).flatMapTo(linkedSetOf()) { chunk ->
            database.articles().existingKeys(chunk)
        }

    override suspend fun pendingEnrichment(from: Instant, limit: Int): List<Article> =
        database.articles().pendingEnrichment(from.toOffsetIso(), limit.coerceIn(1, 200)).map(ArticleEntity::toModel)

    override suspend fun recordSweep(write: SweepWrite) = database.withTransaction {
        val fetchedAt = write.fetchedAt.toString()
        val newByFeed = mutableMapOf<String, Int>()
        write.articles.forEach { article ->
            val key = ArticlePoolKeys.key(article)
            val entity = article.toEntity(fetchedAt, fetchedAt.takeIf { key in write.enrichedLinkKeys })
            val inserted = database.articles().insert(entity) != -1L
            if (!inserted) {
                database.articles().updateFetched(
                    entity.linkKey,
                    entity.link,
                    entity.feedName,
                    entity.title,
                    entity.summaryEn,
                    entity.articleText,
                    entity.pubDateUtc,
                    entity.pubDateIso,
                    entity.fetchedAtUtc,
                    entity.enrichedAtUtc,
                )
            } else {
                newByFeed[entity.feedName] = newByFeed.getOrDefault(entity.feedName, 0) + 1
            }
        }
        write.feedOutcomes.forEach { result ->
            database.fetchLogs().insert(
                FetchLogEntity(
                    feedName = result.feedName,
                    fetchedAtUtc = fetchedAt,
                    status = result.status,
                    error = result.error,
                    itemCount = result.itemCount,
                    newCount = newByFeed.getOrDefault(result.feedName, 0),
                ),
            )
            database.feeds().updateFetchState(
                result.feedName,
                fetchedAt,
                result.status,
                result.error,
                result.newestItemDateIso,
            )
        }
    }

    override suspend fun articlesSince(from: Instant): List<PooledArticle> =
        database.articles().inWindow(from.toOffsetIso()).map { PooledArticle(it.toModel(), it.enrichedAtUtc == null) }

    override suspend fun updateEnriched(articles: List<Article>, enrichedAt: Instant) = database.withTransaction {
        articles.forEach { article ->
            database.articles().updateEnriched(
                ArticlePoolKeys.key(article),
                article.summaryEn,
                article.articleText,
                enrichedAt.toString(),
            )
        }
    }

    override suspend fun feedHealth(feedNames: Set<String>): Map<String, FeedHealth> =
        if (feedNames.isEmpty()) emptyMap() else database.feeds().byNames(feedNames).associate { feed ->
            feed.name to FeedHealth(feed.name, feed.lastStatus, feed.lastError, feed.newestItemDateIso)
        }

    fun search(query: String): Flow<List<ArticleEntity>> = database.articles().search(
        SimpleSQLiteQuery(
            "SELECT a.* FROM articles a JOIN articles_fts f ON f.docid = a.rowid WHERE articles_fts MATCH ? ORDER BY a.pubDateIso DESC",
            arrayOf(ftsMatchExpression(query)),
        ),
    )

    fun searchReportedDates(query: String): Flow<Set<String>> = database.articles().searchReportedDates(
        SimpleSQLiteQuery(
            "SELECT DISTINCT a.reportedDate AS reportedDate FROM articles a JOIN articles_fts f ON f.docid = a.rowid WHERE a.reportedDate IS NOT NULL AND articles_fts MATCH ?",
            arrayOf(ftsMatchExpression(query)),
        ),
    ).map { rows -> rows.mapTo(linkedSetOf()) { it.reportedDate } }

    suspend fun markRead(link: String, now: Instant = Instant.now()) {
        database.articles().markRead(TextUtils.dedupLinkKey(link), now.toString())
    }

    /**
     * Full projection for in-app reading.
     *
     * `articleText` (~150-word body) had always been fetched and persisted, and was even
     * denormalized onto report_items in a dedicated migration — yet no surface ever read
     * it. So on the subway or on a plane the app was dead: beyond the readable Chinese
     * summaries, every link landed on the browser's offline error page, even though the
     * data had long been in the database and the money had long been paid.
     */
    fun observeDetail(link: String): Flow<ArticleDetail?> =
        database.articles().observeDetail(TextUtils.dedupLinkKey(link))

    // Epic U reader data layer: narrow-projection timeline, unread counts, read/unread writes.
    fun observeTimeline(feedName: String?, unreadOnly: Boolean, limit: Int): Flow<List<ReaderArticle>> {
        val capped = limit.coerceIn(1, 1_000)
        return if (feedName == null) database.articles().observeTimeline(unreadOnly, capped)
        else database.articles().observeTimelineForFeed(feedName, unreadOnly, capped)
    }

    fun observeUnreadCounts(): Flow<List<FeedUnreadCount>> = database.articles().observeUnreadCounts()

    /**
     * Full per-day counts, for the reader section headers to show "08-05 Wednesday · 12 articles".
     * Unrelated to the timeline's paging window — the window only truncates rendering; the
     * section header states how many articles that day really has.
     * Each of the four combinations runs its own SQL (see the ArticleDao comments), and all
     * of them hit the covering index.
     */
    fun observeDayCounts(feedName: String?, unreadOnly: Boolean): Flow<List<ReaderDayCount>> = when {
        feedName == null && !unreadOnly -> database.articles().observeDayCounts()
        feedName == null -> database.articles().observeUnreadDayCounts()
        !unreadOnly -> database.articles().observeDayCountsForFeed(feedName)
        else -> database.articles().observeUnreadDayCountsForFeed(feedName)
    }

    fun observePoolCount(): Flow<Int> = database.articles().observePoolCount()

    suspend fun markUnread(linkKey: String) = database.articles().markUnread(linkKey)

    /** Marks everything read with a batch timestamp; undo rolls back exactly by the same timestamp. */
    suspend fun markAllRead(feedName: String?, batchStamp: String): Int =
        if (feedName == null) database.articles().markAllRead(null, batchStamp)
        else database.articles().markAllReadForFeed(feedName, batchStamp)

    suspend fun undoMarkAllRead(batchStamp: String): Int = database.articles().undoMarkAllRead(batchStamp)

    suspend fun prune(retainDays: Int, now: Instant = Instant.now()): Pair<Int, Int> = database.withTransaction {
        val before = now.minus(retainDays.coerceIn(1, 365).toLong(), ChronoUnit.DAYS).toString()
        database.articles().prune(before) to database.fetchLogs().prune(before)
    }

    suspend fun inWindow(from: Instant): List<ArticleEntity> = database.articles().inWindow(from.toOffsetIso())

    suspend fun pendingEnrichmentRows(from: Instant, limit: Int): List<ArticleEntity> =
        database.articles().pendingEnrichment(from.toOffsetIso(), limit.coerceIn(1, 200))
}

internal const val SQLITE_BIND_CHUNK = 900

class RecordingFetchPort(
    private val delegate: FetchPort,
    private val articles: ArticleRepository,
) : FetchPort {
    override suspend fun fetch(reportDate: LocalDate, attempt: Int, trigger: String, config: PipelineConfig): RawRun =
        delegate.fetch(reportDate, attempt, trigger, config).also { articles.recordFetch(it) }
}

private fun Article.toEntity(fetchedAtUtc: String, enrichedAtUtc: String? = fetchedAtUtc.takeIf { articleText.isNotBlank() }) = ArticleEntity(
    linkKey = ArticlePoolKeys.key(this),
    link = link,
    feedName = source,
    title = title,
    summaryEn = summaryEn,
    articleText = articleText,
    pubDateUtc = pubDateUtc,
    pubDateIso = pubDateIso,
    fetchedAtUtc = fetchedAtUtc,
    enrichedAtUtc = enrichedAtUtc,
)

internal fun ArticleEntity.toModel() = Article(feedName, title, link, pubDateUtc, pubDateIso, summaryEn, articleText)

internal fun ftsMatchExpression(query: String): String {
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    require(tokens.isNotEmpty()) { "FTS query must not be blank" }
    return tokens.joinToString(" AND ") { token -> "\"${token.replace("\"", "\"\"")}\"" }
}

/**
 * The same transformation `FeedFetcher` uses when writing `pubDateIso`.
 *
 * These two must agree, or the lexicographic comparison in window queries mismatches on
 * the timezone suffix: the column values are `...+00:00`, while `Instant.toString()`
 * yields `...Z`, and 'Z' > '+' would silently drop articles exactly at the cutoff
 * second — precisely the bug easiest to introduce when replacing `julianday()` with
 * range comparison.
 */
private fun Instant.toOffsetIso(): String = toString().removeSuffix("Z") + "+00:00"
