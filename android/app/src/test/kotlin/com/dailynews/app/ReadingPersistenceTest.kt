package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.db.*
import com.dailynews.data.repo.*
import com.dailynews.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingPersistenceTest {
    @Test fun annotationsAndPositionSurviveRefreshRetentionAndBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val link = "https://example.test/read"
            db.articles().insert(ArticleEntity(link, link, "Source", "Title", "Summary", "Body", "", "", "2025-01-01T00:00:00Z"))
            val repository = ArticleRepository(db)
            repository.saveAnnotations(link, "编译器笔记", listOf(" AI ", "ai", "芯片"))
            repository.saveReadingPosition(link, 3, 180, "content")
            assertEquals(listOf(link), repository.search("Title 编译 AI").first().map { it.link })
            assertTrue(repository.search("%_").first().isEmpty())
            assertFailsWith<IllegalArgumentException> { repository.saveAnnotations(link, "x".repeat(10_001), emptyList()) }
            assertEquals("编译器笔记", db.articles().get(link)?.note)
            assertNotNull(db.articles().get(link)?.favoritedAtUtc)
            db.articles().updateFetched(link, link, "Source", "Changed", "Longer summary", "Changed body", "", "", "2025-01-02T00:00:00Z", null)
            db.articles().setFavorite(link, null)
            assertEquals(0, db.articles().prune("2026-01-01T00:00:00Z"))
            val detail = repository.observeDetail(link).first()!!
            assertEquals("编译器笔记", detail.note)
            assertEquals(listOf("AI", "芯片"), ArtifactJson.codec.decodeFromString<List<String>>(detail.tagsJson))
            assertEquals(3, detail.readingIndex)
            assertEquals(180, detail.readingOffset)
            assertEquals("content", detail.readingContentKey)
            val config = PipelineConfigRepository(context)
            config.save(PipelineConfig(reading = ReadingPreferences(24, 180)))
            val body = OfflineArticleBody(link, "保存正文", "2026-09-08T00:00:00Z", true)
            db.offlineBodies().save(body)
            val backups = StateBackupRepository(db, config)
            val output = ByteArrayOutputStream()
            backups.exportZip(output)
            db.articles().clear()
            config.save(PipelineConfig())
            backups.importZip(output.toByteArray())
            assertEquals(detail, repository.observeDetail(link).first())
            assertEquals(body, db.offlineBodies().get(link))
            assertEquals(ReadingPreferences(24, 180), config.config.first().reading)
            repository.saveAnnotations(link, "", emptyList())
            assertEquals(0, db.articles().prune("2026-01-01T00:00:00Z"))
            db.offlineBodies().remove(link)
            assertEquals(1, db.articles().prune("2026-01-01T00:00:00Z"))
        } finally { db.close() }
    }
}
