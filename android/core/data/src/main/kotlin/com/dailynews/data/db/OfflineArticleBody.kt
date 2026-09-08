package com.dailynews.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "offline_article_bodies", foreignKeys = [ForeignKey(
    entity = ArticleEntity::class, parentColumns = ["linkKey"], childColumns = ["linkKey"], onDelete = ForeignKey.CASCADE,
)])
data class OfflineArticleBody(
    @PrimaryKey val linkKey: String,
    val text: String,
    val fetchedAtUtc: String,
    val truncated: Boolean,
)

@Dao
interface OfflineArticleBodyDao {
    @Query("SELECT * FROM offline_article_bodies WHERE linkKey = :key") fun observe(key: String): Flow<OfflineArticleBody?>
    @Query("SELECT * FROM offline_article_bodies WHERE linkKey = :key") suspend fun get(key: String): OfflineArticleBody?
    @Query("SELECT * FROM offline_article_bodies ORDER BY linkKey") suspend fun allNow(): List<OfflineArticleBody>
    @Upsert suspend fun save(body: OfflineArticleBody)
    @Query("DELETE FROM offline_article_bodies WHERE linkKey = :key") suspend fun remove(key: String)
    @Query("DELETE FROM offline_article_bodies") suspend fun clear()
}
