package com.dailynews.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY position, id") fun observeAll(): Flow<List<FeedEntity>>
    @Query("SELECT * FROM feeds WHERE enabled = 1 ORDER BY position, id") suspend fun enabled(): List<FeedEntity>
    @Query("SELECT * FROM feeds WHERE id = :id") suspend fun get(id: Long): FeedEntity?
    @Query("SELECT * FROM feeds WHERE name IN (:names)") suspend fun byNames(names: Set<String>): List<FeedEntity>
    @Query("SELECT COUNT(*) FROM feeds") suspend fun count(): Int
    @Query("SELECT MAX(position) FROM feeds") suspend fun maxPosition(): Int?
    @Query("SELECT * FROM feeds ORDER BY position, id") suspend fun allNow(): List<FeedEntity>
    @Query("UPDATE feeds SET lastFetchAtUtc = :fetchedAtUtc, lastStatus = :status, lastError = :error, newestItemDateIso = :newestItemDateIso WHERE name = :name")
    suspend fun updateFetchState(name: String, fetchedAtUtc: String, status: String, error: String?, newestItemDateIso: String?)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: FeedEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(entities: List<FeedEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<FeedEntity>)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun update(entity: FeedEntity): Int
    @Query("DELETE FROM feeds WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("DELETE FROM feeds") suspend fun clear()
}

@Dao
interface ArticleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entity: ArticleEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<ArticleEntity>)
    @Query("""
        UPDATE articles SET
            link = :link,
            feedName = :feedName,
            title = :title,
            summaryEn = CASE
                WHEN length(trim(:summaryEn)) > length(trim(summaryEn)) THEN :summaryEn
                ELSE summaryEn
            END,
            articleText = CASE WHEN :articleText != '' THEN :articleText ELSE articleText END,
            pubDateUtc = :pubDateUtc,
            pubDateIso = :pubDateIso,
            fetchedAtUtc = :fetchedAtUtc,
            enrichedAtUtc = COALESCE(:enrichedAtUtc, enrichedAtUtc)
        WHERE linkKey = :linkKey
    """)
    suspend fun updateFetched(
        linkKey: String,
        link: String,
        feedName: String,
        title: String,
        summaryEn: String,
        articleText: String,
        pubDateUtc: String,
        pubDateIso: String,
        fetchedAtUtc: String,
        enrichedAtUtc: String?,
    )
    @Query("""
        UPDATE articles SET
            summaryEn = CASE WHEN :summaryEn != '' THEN :summaryEn ELSE summaryEn END,
            articleText = CASE WHEN :articleText != '' THEN :articleText ELSE articleText END,
            enrichedAtUtc = :enrichedAtUtc
        WHERE linkKey = :linkKey
    """)
    suspend fun updateEnriched(linkKey: String, summaryEn: String, articleText: String, enrichedAtUtc: String)
    @Query("SELECT linkKey FROM articles WHERE linkKey IN (:keys)") suspend fun existingKeys(keys: List<String>): List<String>
    @Query("SELECT * FROM articles WHERE linkKey = :linkKey") suspend fun get(linkKey: String): ArticleEntity?

    /**
     * Full projection for in-app reading. Summary policy matches observeTimeline:
     * prefer the Chinese summary from a published report, else fall back to the
     * feed's English summary.
     */
    @Query("""
        SELECT a.linkKey, a.link, a.title, a.feedName AS source,
               COALESCE((SELECT ri.summaryZh FROM report_items ri
                         WHERE ri.link = a.link AND ri.summaryZh <> ''
                         ORDER BY ri.reportDate DESC, ri.part LIMIT 1), a.summaryEn) AS summaryZh,
               a.articleText AS articleText,
               a.pubDateUtc AS pubDateUtc, a.pubDateIso AS pubDateIso,
               a.favoritedAtUtc AS favoritedAtUtc
        FROM articles a WHERE a.linkKey = :linkKey
    """)
    fun observeDetail(linkKey: String): Flow<ArticleDetail?>
    @Query("SELECT * FROM articles ORDER BY pubDateIso DESC") suspend fun allNow(): List<ArticleEntity>
    // Wrapping the indexed column in `julianday(pubDateIso)` makes
    // index_articles_pubDateIso completely unusable: EXPLAIN QUERY PLAN is
    // SCAN articles + USE TEMP B-TREE FOR ORDER BY. At a 30-day retention
    // steady state that reads ~4000 rows (including articleText) and sorts
    // them all, while a range scan only touches the couple of hundred in the
    // window.
    //
    // Direct comparison is safe, but **the bind parameter must be normalized
    // first**: column values are `...+00:00`, callers pass Instant.toString()
    // `...Z`, and lexicographically 'Z' > '+', so a raw compare silently drops
    // articles that fall exactly on the cutoff second. Normalization lives in
    // ArticleRepository.toOffsetIso, pinned by SweepAndWindowStepTest's
    // boundary case. ISO-8601 fixed-width prefixes plus left-aligned fractions
    // make lexicographic order equal temporal order.
    @Query("SELECT * FROM articles WHERE pubDateIso >= :fromIso ORDER BY pubDateIso DESC") suspend fun inWindow(fromIso: String): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE pubDateIso >= :fromIso AND enrichedAtUtc IS NULL ORDER BY pubDateIso DESC LIMIT :limit")
    suspend fun pendingEnrichment(fromIso: String, limit: Int): List<ArticleEntity>
    @Query("UPDATE articles SET readAtUtc = :readAtUtc WHERE linkKey = :linkKey") suspend fun markRead(linkKey: String, readAtUtc: String)
    @Query("UPDATE articles SET favoritedAtUtc = :favoritedAtUtc WHERE linkKey = :linkKey") suspend fun setFavorite(linkKey: String, favoritedAtUtc: String?)
    @Query("UPDATE articles SET reportedDate = :reportDate WHERE linkKey IN (:linkKeys)") suspend fun markReported(linkKeys: List<String>, reportDate: String)
    @Query("SELECT link FROM articles WHERE favoritedAtUtc IS NOT NULL") fun observeSavedLinks(): Flow<List<String>>
    @Query("SELECT link FROM articles WHERE readAtUtc IS NOT NULL") fun observeReadLinks(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM articles WHERE pubDateIso >= :fromIso") fun observeCountSince(fromIso: String): Flow<Int>
    @Query("""
        SELECT a.linkKey, a.link, a.title, a.feedName AS source,
               COALESCE((SELECT ri.summaryZh FROM report_items ri
                         WHERE ri.link = a.link AND ri.summaryZh <> ''
                         ORDER BY ri.reportDate DESC, ri.part LIMIT 1), a.summaryEn) AS summaryZh,
               a.favoritedAtUtc AS favoritedAtUtc,
               a.pubDateUtc AS pubDateUtc,
               a.pubDateIso AS pubDateIso,
               a.readAtUtc AS readAtUtc
        FROM articles a
        WHERE a.favoritedAtUtc IS NOT NULL
        ORDER BY a.favoritedAtUtc DESC
    """)
    fun observeFavorites(): Flow<List<FavoriteArticle>>
    // Epic U reader: two SQLs cover the four combinations of all/by-feed ×
    // all/unread-only. Do not write a single query with (:feedName IS NULL OR …)
    // — OR makes the optimizer drop the composite index; split, Kotlin can pick
    // index_articles_pubDateIso or index_articles_feedName_pubDateIso. pubDateIso
    // <> '' also blocks synthetic rows from FavoriteRepository.save (no date);
    // ORDER BY pubDateIso DESC rides the index (lexicographic = temporal; see
    // FeedFetcher.toOffsetIso).
    @Query("""
        SELECT a.linkKey, a.link, a.title, a.feedName AS source,
               COALESCE((SELECT ri.summaryZh FROM report_items ri
                         WHERE ri.link = a.link AND ri.summaryZh <> ''
                         ORDER BY ri.reportDate DESC, ri.part LIMIT 1), a.summaryEn) AS summaryZh,
               a.pubDateUtc, a.pubDateIso, a.readAtUtc, a.favoritedAtUtc
        FROM articles a
        WHERE a.pubDateIso <> ''
          AND (:unreadOnly = 0 OR a.readAtUtc IS NULL)
        ORDER BY a.pubDateIso DESC, a.linkKey
        LIMIT :limit
    """)
    fun observeTimeline(unreadOnly: Boolean, limit: Int): Flow<List<ReaderArticle>>
    @Query("""
        SELECT a.linkKey, a.link, a.title, a.feedName AS source,
               COALESCE((SELECT ri.summaryZh FROM report_items ri
                         WHERE ri.link = a.link AND ri.summaryZh <> ''
                         ORDER BY ri.reportDate DESC, ri.part LIMIT 1), a.summaryEn) AS summaryZh,
               a.pubDateUtc, a.pubDateIso, a.readAtUtc, a.favoritedAtUtc
        FROM articles a
        WHERE a.pubDateIso <> ''
          AND a.feedName = :feedName
          AND (:unreadOnly = 0 OR a.readAtUtc IS NULL)
        ORDER BY a.pubDateIso DESC, a.linkKey
        LIMIT :limit
    """)
    fun observeTimelineForFeed(feedName: String, unreadOnly: Boolean, limit: Int): Flow<List<ReaderArticle>>
    @Query("SELECT feedName, COUNT(*) AS unread FROM articles WHERE readAtUtc IS NULL AND pubDateIso <> '' GROUP BY feedName")
    fun observeUnreadCounts(): Flow<List<FeedUnreadCount>>

    // Epic V per-day counts for day sections. One query each for the four
    // all/by-feed × all/unread combinations, same reason observeTimeline was
    // split, and stronger: after splitting unreadOnly too, all four are
    // COVERING INDEX (EXPLAIN QUERY PLAN), no INDEXED BY hint needed.
    //   all/all     → SCAN   COVERING INDEX index_articles_pubDateIso
    //   all/unread  → SEARCH COVERING INDEX index_articles_readAtUtc_feedName_pubDateIso (readAtUtc=?)
    //   feed/all    → SEARCH COVERING INDEX index_articles_feedName_pubDateIso (feedName=?)
    //   feed/unread → SEARCH COVERING INDEX index_articles_readAtUtc_feedName_pubDateIso (readAtUtc=? AND feedName=?)
    // Counts are full-pool, independent of the timeline window: the section
    // header writes how many articles that day actually has.
    @Query("SELECT substr(pubDateIso,1,10) AS day, COUNT(*) AS total FROM articles WHERE pubDateIso <> '' GROUP BY day ORDER BY day DESC")
    fun observeDayCounts(): Flow<List<ReaderDayCount>>

    @Query("SELECT substr(pubDateIso,1,10) AS day, COUNT(*) AS total FROM articles WHERE pubDateIso <> '' AND readAtUtc IS NULL GROUP BY day ORDER BY day DESC")
    fun observeUnreadDayCounts(): Flow<List<ReaderDayCount>>

    @Query("SELECT substr(pubDateIso,1,10) AS day, COUNT(*) AS total FROM articles WHERE pubDateIso <> '' AND feedName = :feedName GROUP BY day ORDER BY day DESC")
    fun observeDayCountsForFeed(feedName: String): Flow<List<ReaderDayCount>>

    @Query(
        """
        SELECT substr(pubDateIso,1,10) AS day, COUNT(*) AS total FROM articles
        WHERE pubDateIso <> '' AND feedName = :feedName AND readAtUtc IS NULL
        GROUP BY day ORDER BY day DESC
        """,
    )
    fun observeUnreadDayCountsForFeed(feedName: String): Flow<List<ReaderDayCount>>
    @Query("SELECT COUNT(*) FROM articles WHERE pubDateIso <> ''") fun observePoolCount(): Flow<Int>
    @Query("UPDATE articles SET readAtUtc = NULL WHERE linkKey = :linkKey") suspend fun markUnread(linkKey: String)
    @Query("""
        UPDATE articles SET readAtUtc = :batchStamp
        WHERE readAtUtc IS NULL AND pubDateIso <> '' AND (:feedName IS NULL OR feedName = :feedName)
    """)
    suspend fun markAllRead(feedName: String?, batchStamp: String): Int
    @Query("UPDATE articles SET readAtUtc = :batchStamp WHERE readAtUtc IS NULL AND pubDateIso <> '' AND feedName = :feedName")
    suspend fun markAllReadForFeed(feedName: String, batchStamp: String): Int
    @Query("UPDATE articles SET readAtUtc = NULL WHERE readAtUtc = :batchStamp") suspend fun undoMarkAllRead(batchStamp: String): Int
    @RawQuery(observedEntities = [ArticleEntity::class]) fun search(query: SupportSQLiteQuery): Flow<List<ArticleEntity>>
    @RawQuery(observedEntities = [ArticleEntity::class]) fun searchReportedDates(query: SupportSQLiteQuery): Flow<List<ReportedDateRow>>
    @Query("DELETE FROM articles WHERE favoritedAtUtc IS NULL AND fetchedAtUtc < :beforeUtc") suspend fun prune(beforeUtc: String): Int
    @Query("DELETE FROM articles") suspend fun clear()
}

@Dao
interface FetchLogDao {
    @Insert suspend fun insert(entity: FetchLogEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<FetchLogEntity>)
    @Query("SELECT * FROM fetch_log ORDER BY id") suspend fun allNow(): List<FetchLogEntity>
    @Query("SELECT * FROM fetch_log ORDER BY fetchedAtUtc DESC, id DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<FetchLogEntity>>
    @Query("DELETE FROM fetch_log WHERE fetchedAtUtc < :beforeUtc") suspend fun prune(beforeUtc: String): Int
    @Query("DELETE FROM fetch_log") suspend fun clear()
}

@Dao
interface RunArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: RunArtifactEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<RunArtifactEntity>)
    @Query("SELECT * FROM run_artifacts WHERE runId = :runId AND name = :name") suspend fun get(runId: String, name: String): RunArtifactEntity?
    @Query("SELECT * FROM run_artifacts WHERE runId = :runId ORDER BY name") suspend fun forRun(runId: String): List<RunArtifactEntity>
    @Query("SELECT * FROM run_artifacts ORDER BY runId, name") suspend fun allNow(): List<RunArtifactEntity>
    @Query("SELECT runId, name, createdAtUtc FROM run_artifacts ORDER BY runId, name") suspend fun metadata(): List<RunArtifactMetadata>
    @Query("SELECT runId, name, createdAtUtc FROM run_artifacts WHERE runId = :runId ORDER BY name") suspend fun metadataForRun(runId: String): List<RunArtifactMetadata>
    @Query("SELECT length(gzipBody) FROM run_artifacts WHERE runId = :runId AND name = :name") suspend fun bodySize(runId: String, name: String): Int?
    @Query("SELECT substr(gzipBody, :offset, :length) FROM run_artifacts WHERE runId = :runId AND name = :name")
    suspend fun bodyChunk(runId: String, name: String, offset: Int, length: Int): ByteArray?
    @Query("DELETE FROM run_artifacts WHERE createdAtUtc < :beforeUtc") suspend fun deleteBefore(beforeUtc: String): Int
    @Query("DELETE FROM run_artifacts") suspend fun clear()
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: RunEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<RunEntity>)
    @Query("SELECT * FROM runs ORDER BY startedAtUtc") suspend fun allNow(): List<RunEntity>
    @Query("SELECT * FROM runs WHERE runId = :runId") suspend fun get(runId: String): RunEntity?
    @Query("SELECT * FROM runs WHERE runId = :runId") fun observeDetail(runId: String): Flow<RunEntity?>
    @Query("SELECT runId, reportDate, status, classification, validatorExitCode, attempt, startedAtUtc, finishedAtUtc FROM runs ORDER BY startedAtUtc DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<RunSummary>>
    @Query("SELECT * FROM runs WHERE reportDate = :reportDate AND status = 'RUNNING' ORDER BY startedAtUtc DESC LIMIT 1") suspend fun latestRunning(reportDate: String): RunEntity?
    @Query("SELECT * FROM runs WHERE reportDate = :reportDate ORDER BY startedAtUtc DESC LIMIT 1") suspend fun latestForDate(reportDate: String): RunEntity?
    @Query("UPDATE runs SET status = 'FAILED', classification = 'INTERRUPTED', finishedAtUtc = :nowUtc, blockingReasonsJson = '[\"process interrupted before completion\"]' WHERE status = 'RUNNING'") suspend fun markRunningInterrupted(nowUtc: String)
    @Query("DELETE FROM runs WHERE finishedAtUtc IS NOT NULL AND finishedAtUtc < :beforeUtc") suspend fun deleteFinishedBefore(beforeUtc: String): Int
    @Query("DELETE FROM runs") suspend fun clear()
}

@Dao
interface RunLogDao {
    @Insert suspend fun insert(entity: RunLogEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<RunLogEntity>)
    @Query("SELECT * FROM run_logs ORDER BY id") suspend fun allNow(): List<RunLogEntity>
    @Query("SELECT * FROM run_logs WHERE runId = :runId ORDER BY id") fun observe(runId: String): Flow<List<RunLogEntity>>
    @Query("DELETE FROM run_logs WHERE createdAtUtc < :beforeUtc") suspend fun deleteBefore(beforeUtc: String): Int
    @Query("SELECT COUNT(*) FROM run_logs WHERE runId = :runId") suspend fun count(runId: String): Int
    @Query("DELETE FROM run_logs") suspend fun clear()
}

@Dao
interface LlmCallDao {
    @Insert suspend fun insert(entity: LlmCallEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<LlmCallEntity>)
    @Query("SELECT * FROM llm_calls ORDER BY id") suspend fun allNow(): List<LlmCallEntity>
    @Query("SELECT * FROM llm_calls WHERE runId = :runId ORDER BY id") fun observe(runId: String): Flow<List<LlmCallEntity>>
    @Query("SELECT COALESCE(SUM(inputTokens),0) + COALESCE(SUM(outputTokens),0) FROM llm_calls WHERE createdAtUtc >= :fromUtc") suspend fun tokensSince(fromUtc: String): Long
    @Query("SELECT substr(createdAtUtc, 1, 7) AS month, COALESCE(SUM(inputTokens), 0) AS inputTokens, COALESCE(SUM(outputTokens), 0) AS outputTokens, COUNT(*) AS callCount FROM llm_calls WHERE createdAtUtc < :beforeUtc GROUP BY substr(createdAtUtc, 1, 7)") suspend fun rollupsBefore(beforeUtc: String): List<LlmUsageRollup>
    @Query("DELETE FROM llm_calls WHERE createdAtUtc < :beforeUtc") suspend fun deleteBefore(beforeUtc: String): Int
    @Query("SELECT COUNT(*) FROM llm_calls WHERE runId = :runId") suspend fun count(runId: String): Int
    @Query("DELETE FROM llm_calls") suspend fun clear()
}

@Dao
interface LlmUsageMonthDao {
    @Query("SELECT * FROM llm_usage_monthly WHERE month = :month") suspend fun get(month: String): LlmUsageMonthEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: LlmUsageMonthEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(entities: List<LlmUsageMonthEntity>)
    @Query("SELECT * FROM llm_usage_monthly ORDER BY month") suspend fun allNow(): List<LlmUsageMonthEntity>
    @Query("DELETE FROM llm_usage_monthly") suspend fun clear()
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(report: ReportEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceReports(reports: List<ReportEntity>)
    @Query("SELECT * FROM reports ORDER BY reportDate") suspend fun allNow(): List<ReportEntity>
    @Query("DELETE FROM report_items WHERE reportDate = :reportDate") suspend fun deleteItems(reportDate: String)

    /**
     * Drop only historical Part 2 items.
     *
     * `reports` / `report_items` used to be **the only two tables without a
     * retention period**, and they were also the fastest-growing: LAZY expansion
     * writes one part-2 row per pooled article, each carrying a summaryEn +
     * articleText snapshot (~240 KB/day), while `PART2_SECTION_ENABLED = false`
     * means the UI never shows that section. ~60 MB in half a year.
     *
     * **Part 1 must never be pruned with the same ruler**: cross-day story lines
     * (observeStory / observeStoryDepth) and weekly/monthly digests
     * (publishedPart1Between) both read it; it must be kept long term.
     */
    @Query("DELETE FROM report_items WHERE part = 2 AND reportDate < :beforeDate")
    suspend fun deletePart2Before(beforeDate: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItems(items: List<ReportItemEntity>)
    @Query("SELECT * FROM report_items ORDER BY reportDate, part, position") suspend fun allItemsNow(): List<ReportItemEntity>
    @Query("""
        SELECT reportDate, status, failureReason, createdAtUtc, publishedAtUtc
        FROM reports
        WHERE reportDate COLLATE NOCASE LIKE :pattern ESCAPE '\'
           OR status COLLATE NOCASE LIKE :pattern ESCAPE '\'
           OR (status != 'SUCCESS' AND markdown COLLATE NOCASE LIKE :pattern ESCAPE '\')
           OR EXISTS (
               SELECT 1 FROM report_items
               WHERE report_items.reportDate = reports.reportDate
                 AND (title COLLATE NOCASE LIKE :pattern ESCAPE '\'
                      OR summaryZh COLLATE NOCASE LIKE :pattern ESCAPE '\')
           )
        ORDER BY reportDate DESC
    """) fun searchSummaries(pattern: String): Flow<List<ReportSummary>>
    @Query("SELECT * FROM reports WHERE reportDate = :date") fun observeReport(date: String): Flow<ReportEntity?>
    @Query("SELECT reportDate, status, failureReason, createdAtUtc, publishedAtUtc FROM reports ORDER BY reportDate DESC") fun observeAllSummaries(): Flow<List<ReportSummary>>
    @Query("""
        SELECT r.reportDate, r.status, r.failureReason,
               (SELECT COUNT(*) FROM report_items c WHERE c.reportDate = r.reportDate AND c.part = 2) AS articleCount,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 0) AS previewTitle1,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 1) AS previewTitle2,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 2) AS previewTitle3
        FROM reports r
        WHERE r.reportDate COLLATE NOCASE LIKE :pattern ESCAPE '\'
           OR r.status COLLATE NOCASE LIKE :pattern ESCAPE '\'
           OR COALESCE(r.failureReason, '') COLLATE NOCASE LIKE :pattern ESCAPE '\'
           OR (r.status != 'SUCCESS' AND r.markdown COLLATE NOCASE LIKE :pattern ESCAPE '\')
           OR EXISTS (
               SELECT 1 FROM report_items i
               WHERE i.reportDate = r.reportDate
                 AND (i.title COLLATE NOCASE LIKE :pattern ESCAPE '\'
                      OR i.summaryZh COLLATE NOCASE LIKE :pattern ESCAPE '\')
           )
        ORDER BY r.reportDate DESC
    """) fun searchPreviews(pattern: String): Flow<List<ReportPreview>>
    @Query("""
        SELECT r.reportDate, r.status, r.failureReason,
               (SELECT COUNT(*) FROM report_items c WHERE c.reportDate = r.reportDate AND c.part = 2) AS articleCount,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 0) AS previewTitle1,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 1) AS previewTitle2,
               (SELECT title FROM report_items p WHERE p.reportDate = r.reportDate AND p.part = 1 ORDER BY position LIMIT 1 OFFSET 2) AS previewTitle3
        FROM reports r ORDER BY r.reportDate DESC
    """) fun observeAllPreviews(): Flow<List<ReportPreview>>
    @Query("SELECT * FROM report_items WHERE reportDate = :date ORDER BY part, position") fun observeItems(date: String): Flow<List<ReportItemEntity>>

    // Epic V story history: Part 1 items for the same event_key, date descending, via index_report_items_eventKey.
    @Query("SELECT * FROM report_items WHERE eventKey = :eventKey AND part = 1 AND eventKey <> '' ORDER BY reportDate DESC, position")
    fun observeStory(eventKey: String): Flow<List<ReportItemEntity>>

    /**
     * How many days each story line has been reported. The UI only shows the
     * story-history entry when >= 2 — opening a single-article story would
     * only show itself, an empty promise.
     */
    @Query(
        """
        SELECT eventKey, COUNT(DISTINCT reportDate) AS days FROM report_items
        WHERE part = 1 AND eventKey <> '' AND eventKey IN (:eventKeys)
        GROUP BY eventKey
        """,
    )
    fun observeStoryDepth(eventKeys: List<String>): Flow<List<StoryDepth>>

    /**
     * Epic V periodic-digest material: Part 1 items from days in the range that
     * **successfully published**. Primary key (reportDate, part, position)
     * starts with reportDate, so BETWEEN is a PK range scan; EXISTS is a PK
     * point lookup on reports. No new index needed. Days demoted by markFailed
     * are excluded by status = 'SUCCESS': content that failed review must not
     * enter a weekly digest.
     */
    @Query(
        """
        SELECT ri.* FROM report_items ri
        WHERE ri.part = 1 AND ri.reportDate BETWEEN :start AND :end AND ri.summaryZh <> ''
          AND EXISTS (SELECT 1 FROM reports r WHERE r.reportDate = ri.reportDate AND r.status = 'SUCCESS')
        ORDER BY ri.reportDate, ri.position
        """,
    )
    suspend fun publishedPart1Between(start: String, end: String): List<ReportItemEntity>
    @Query("SELECT * FROM reports WHERE reportDate = :date") suspend fun get(date: String): ReportEntity?
    @Query("SELECT * FROM reports ORDER BY reportDate DESC LIMIT 1") suspend fun latestNow(): ReportEntity?
    @Query("SELECT * FROM report_items WHERE reportDate = :date AND part = 1 ORDER BY position LIMIT :limit") suspend fun topItemsNow(date: String, limit: Int): List<ReportItemEntity>
    @Query("SELECT * FROM report_items WHERE reportDate = :date ORDER BY part, position") suspend fun itemsNow(date: String): List<ReportItemEntity>
    @Query("SELECT * FROM report_items WHERE reportDate = :date AND part = 2 AND source = :source ORDER BY position")
    suspend fun part2ItemsForSource(date: String, source: String): List<ReportItemEntity>
    @Query("UPDATE report_items SET summaryZh = :summaryZh WHERE reportDate = :date AND part = 2 AND position = :position")
    suspend fun updatePart2Summary(date: String, position: Int, summaryZh: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM reports WHERE reportDate = :date AND status = 'SUCCESS')") suspend fun hasSuccess(date: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM reports WHERE reportDate = :date AND publishedAtUtc IS NOT NULL)") suspend fun wasPublished(date: String): Boolean
    @Query("UPDATE reports SET topNMarkdown = :markdown WHERE reportDate = :date AND status = 'SUCCESS'") suspend fun updateTopN(date: String, markdown: String)
    @Query("UPDATE reports SET status = 'FAILED', topNMarkdown = '', failureReason = :reason WHERE reportDate = :date") suspend fun markFailed(date: String, reason: String)
    @Query("DELETE FROM report_items") suspend fun clearAllItems()
    @Query("DELETE FROM reports") suspend fun clearReports()
}

@Dao
interface EditorialCacheDao {
    @Query("SELECT * FROM editorial_cache WHERE cacheKey = :key") suspend fun find(key: String): EditorialCacheEntity?
    @Query("SELECT * FROM editorial_cache WHERE part1SummaryZh IS NOT NULL AND updatedAtUtc IS NOT NULL AND updatedAtUtc >= :sinceUtc ORDER BY updatedAtUtc DESC, eventKey DESC") suspend fun recentSince(sinceUtc: String): List<EditorialCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(records: List<EditorialCacheEntity>)
    @Query("SELECT * FROM editorial_cache ORDER BY cacheKey") suspend fun allNow(): List<EditorialCacheEntity>
    @Query("DELETE FROM editorial_cache WHERE updatedAtUtc IS NOT NULL AND updatedAtUtc < :beforeUtc") suspend fun prune(beforeUtc: String)
    @Query("DELETE FROM editorial_cache") suspend fun clear()
}

@Dao
interface SeenLinksDao {
    @Query("SELECT * FROM seen_links") suspend fun all(): List<SeenLinkEntity>
    @Query("DELETE FROM seen_links") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(entries: List<SeenLinkEntity>)
}

@Dao
interface PeriodicReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(report: PeriodicReportEntity)
    @Query("SELECT * FROM periodic_reports WHERE periodKey = :periodKey") suspend fun find(periodKey: String): PeriodicReportEntity?
    @Query("SELECT * FROM periodic_reports WHERE periodKey = :periodKey") fun observe(periodKey: String): Flow<PeriodicReportEntity?>
    @Query(
        """
        SELECT periodKey, kind, status, periodStartDate, periodEndDate, itemCount, failureReason, createdAtUtc
        FROM periodic_reports ORDER BY periodKey DESC
        """,
    )
    fun observeSummaries(): Flow<List<PeriodicReportSummary>>
    @Query("SELECT * FROM periodic_reports ORDER BY periodKey") suspend fun allNow(): List<PeriodicReportEntity>
    /** A successfully published periodic digest must not be overwritten by a later failure; same policy as reports.wasPublished. */
    @Query("SELECT COUNT(*) > 0 FROM periodic_reports WHERE periodKey = :periodKey AND publishedAtUtc IS NOT NULL")
    suspend fun wasPublished(periodKey: String): Boolean
    @Query("DELETE FROM periodic_reports") suspend fun clear()
}
