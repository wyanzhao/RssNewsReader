package com.dailynews.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailynews.data.db.*
import com.dailynews.data.repo.OfflineArticleRepository
import com.dailynews.pipeline.ports.ArticleBodyPort
import com.dailynews.pipeline.ports.RetrievedArticleBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class OfflineBodyInstrumentedTest {
    @Test fun explicitFetchFailureRetentionAndRemoval() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DailyNewsDatabase::class.java).build()
        try {
            val key = "https://example.test/story"
            db.articles().insert(ArticleEntity(key,key,"Source","Title","summary","excerpt","","","2020-01-01"))
            var calls = 0
            var fail = false
            val repo = OfflineArticleRepository(db, ArticleBodyPort {
                calls++
                if (fail) error("denied")
                RetrievedArticleBody("saved body", true)
            })
            assertNull(repo.observe(key).first())
            assertEquals(0, calls)
            repo.fetchAndSave(key)
            val saved = repo.observe(key).first()
            assertEquals("saved body", saved?.text)
            assertTrue(saved?.truncated == true)
            assertEquals("excerpt", db.articles().get(key)?.articleText)
            assertEquals(0, db.articles().prune("2026-01-01"))
            fail = true
            assertTrue(runCatching { repo.fetchAndSave(key) }.isFailure)
            assertEquals(saved, repo.observe(key).first())
            repo.remove(key)
            assertNull(repo.observe(key).first())
            assertEquals(1, db.articles().prune("2026-01-01"))
            assertEquals(2, calls)
        } finally { db.close() }
    }
}
