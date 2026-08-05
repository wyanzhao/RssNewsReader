package com.dailynews.data.repo

import androidx.room.withTransaction
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.EditorialCacheEntity
import com.dailynews.data.db.SeenLinkEntity
import com.dailynews.pipeline.ports.EditorialCacheRecord
import com.dailynews.pipeline.ports.EditorialCacheStore
import com.dailynews.pipeline.ports.SeenLinksStore
import com.dailynews.pipeline.flow.SeenLinks
import java.time.Instant
import java.time.LocalDate

class SeenLinksRepository(private val database: DailyNewsDatabase) : SeenLinksStore {
    override suspend fun entries(): Map<String, LocalDate> = database.seenLinks().all().associate { it.linkKey to LocalDate.parse(it.firstSeenDate) }

    override suspend fun replace(entries: Map<String, LocalDate>) {
        database.withTransaction {
            database.seenLinks().clear()
            database.seenLinks().insertAll(entries.map { (key, date) -> SeenLinkEntity(key, date.toString()) })
        }
    }

    override suspend fun recordReportedLinks(links: List<String>, reportDate: LocalDate) {
        database.withTransaction {
            val entries = database.seenLinks().all()
                .associate { it.linkKey to LocalDate.parse(it.firstSeenDate) }
                .toMutableMap()
            SeenLinks.recordReportedLinks(entries, links, reportDate)
            SeenLinks.prune(entries, reportDate)
            database.seenLinks().clear()
            database.seenLinks().insertAll(entries.map { (key, date) -> SeenLinkEntity(key, date.toString()) })
        }
    }

    suspend fun mergeLatest(entries: Map<String, LocalDate>) {
        database.withTransaction {
            val merged = database.seenLinks().all().associate { it.linkKey to LocalDate.parse(it.firstSeenDate) }.toMutableMap()
            entries.forEach { (key, date) ->
                merged[key] = merged[key]?.let { existing -> maxOf(existing, date) } ?: date
            }
            database.seenLinks().clear()
            database.seenLinks().insertAll(merged.map { (key, date) -> SeenLinkEntity(key, date.toString()) })
        }
    }
}

class EditorialCacheRepository(private val database: DailyNewsDatabase) : EditorialCacheStore {
    override suspend fun find(cacheKey: String): EditorialCacheRecord? = database.editorialCache().find(cacheKey)?.toRecord()

    override suspend fun recentSince(since: Instant): List<EditorialCacheRecord> =
        database.editorialCache().recentSince(since.toString()).map(EditorialCacheEntity::toRecord)

    override suspend fun upsert(records: List<EditorialCacheRecord>) {
        database.editorialCache().upsert(records.map(EditorialCacheRecord::toEntity))
    }

    override suspend fun prune(before: Instant) = database.editorialCache().prune(before.toString())
}

private fun EditorialCacheEntity.toRecord() = EditorialCacheRecord(
    cacheKey, link, source, title, summaryZh, part1SummaryZh, noiseBucket,
    part1NoiseBucket, eventKey, updatedAtUtc?.let(Instant::parse),
)

private fun EditorialCacheRecord.toEntity() = EditorialCacheEntity(
    cacheKey, link, source, title, summaryZh, part1SummaryZh, noiseBucket,
    part1NoiseBucket, eventKey, updatedAtUtc?.toString(),
)
