package com.dailynews.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.FeedEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.FeedConfigDocument
import com.dailynews.model.FeedDefinition
import com.dailynews.pipeline.parse.OpmlParser
import com.dailynews.pipeline.ports.FeedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString

data class FeedRecord(
    val id: Long,
    val name: String,
    val url: String,
    val errorPolicy: String,
    val enabled: Boolean,
    val position: Int,
    val lastStatus: String? = null,
    val lastError: String? = null,
    val newestItemDateIso: String? = null,
) {
    fun toDefinition() = FeedDefinition(name, url, errorPolicy, enabled, position)
}

interface FeedEditorRepository {
    fun observeAll(): Flow<List<FeedRecord>>
    suspend fun insert(feed: FeedDefinition): Long
    suspend fun update(id: Long, feed: FeedDefinition)
    suspend fun delete(id: Long)
    suspend fun restore(feed: FeedRecord)
    suspend fun reorder(orderedIds: List<Long>)
    suspend fun importOpml(content: String): Int
    suspend fun exportOpml(): String
}

class FeedRepository(
    private val database: DailyNewsDatabase,
    private val appContext: Context,
) : FeedSource, FeedEditorRepository {
    override fun observeAll(): Flow<List<FeedRecord>> = database.feeds().observeAll().map { rows -> rows.map(FeedEntity::toRecord) }

    override suspend fun enabledFeeds(): List<FeedDefinition> = database.feeds().enabled().map(FeedEntity::toModel)

    suspend fun seedIfEmpty() {
        if (database.feeds().count() != 0) return
        val json = appContext.assets.open("seed/feeds.json").bufferedReader().use { it.readText() }
        val document = ArtifactJson.codec.decodeFromString<FeedConfigDocument>(json)
        database.feeds().insertAll(document.feeds.mapIndexed { index, feed -> feed.toEntity(position = index) })
    }

    override suspend fun insert(feed: FeedDefinition): Long = database.withTransaction {
        val position = (database.feeds().maxPosition() ?: -1) + 1
        database.feeds().insert(feed.toEntity(position = position))
    }

    override suspend fun update(id: Long, feed: FeedDefinition) = database.withTransaction {
        val original = requireNotNull(database.feeds().get(id)) { "feed $id no longer exists" }
        val changed = database.feeds().update(
            original.copy(
                name = feed.name,
                url = feed.url,
                errorPolicy = feed.errorPolicy,
                enabled = feed.enabled,
            ),
        )
        check(changed == 1) { "feed $id update affected $changed rows" }
    }

    override suspend fun delete(id: Long) {
        database.feeds().delete(id)
    }

    override suspend fun restore(feed: FeedRecord) {
        database.withTransaction {
            database.feeds().insert(
                FeedEntity(
                    id = feed.id,
                    name = feed.name,
                    url = feed.url,
                    errorPolicy = feed.errorPolicy,
                    enabled = feed.enabled,
                    position = feed.position,
                    lastStatus = feed.lastStatus,
                    lastError = feed.lastError,
                    newestItemDateIso = feed.newestItemDateIso,
                ),
            )
        }
    }

    override suspend fun reorder(orderedIds: List<Long>) = database.withTransaction {
        orderedIds.distinct().forEachIndexed { position, id ->
            val row = database.feeds().get(id) ?: return@forEachIndexed
            database.feeds().update(row.copy(position = position))
        }
    }

    override suspend fun importOpml(content: String): Int {
        val parsed = OpmlParser.parse(content)
        return database.withTransaction {
            val firstPosition = (database.feeds().maxPosition() ?: -1) + 1
            database.feeds().insertAll(parsed.mapIndexed { index, feed -> feed.toEntity(position = firstPosition + index) })
                .count { it != -1L }
        }
    }

    override suspend fun exportOpml(): String = OpmlParser.render(enabledFeeds())
}

private fun FeedEntity.toModel() = FeedDefinition(name, url, errorPolicy, enabled, position)
private fun FeedEntity.toRecord() = FeedRecord(id, name, url, errorPolicy, enabled, position, lastStatus, lastError, newestItemDateIso)
private fun FeedDefinition.toEntity(id: Long = 0, position: Int = this.position) = FeedEntity(id, name, url, errorPolicy, enabled, position)
