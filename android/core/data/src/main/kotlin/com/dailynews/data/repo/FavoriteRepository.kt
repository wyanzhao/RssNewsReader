package com.dailynews.data.repo

import androidx.room.withTransaction
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.FavoriteArticle
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteRepository(private val database: DailyNewsDatabase) {
    fun observeAll(): Flow<List<FavoriteArticle>> = database.articles().observeFavorites()
    fun observeSavedLinks(): Flow<Set<String>> = database.articles().observeSavedLinks().map { it.toSet() }
    fun observeReadLinks(): Flow<Set<String>> = database.articles().observeReadLinks().map { it.toSet() }

    suspend fun save(link: String, title: String, source: String, summaryZh: String) = database.withTransaction {
        val now = Instant.now().toString()
        val key = TextUtils.dedupLinkKey(link)
        if (database.articles().get(key) == null) {
            database.articles().insert(
                ArticleEntity(
                    linkKey = key,
                    link = link,
                    feedName = source,
                    title = title,
                    summaryEn = summaryZh,
                    articleText = "",
                    pubDateUtc = "",
                    pubDateIso = "",
                    fetchedAtUtc = now,
                ),
            )
        }
        database.articles().setFavorite(key, now)
    }

    suspend fun remove(link: String) = database.articles().setFavorite(TextUtils.dedupLinkKey(link), null)

    suspend fun restore(link: String) = database.articles().setFavorite(TextUtils.dedupLinkKey(link), Instant.now().toString())

    suspend fun markRead(link: String) = database.articles().markRead(TextUtils.dedupLinkKey(link), Instant.now().toString())
}
