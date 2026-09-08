package com.dailynews.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailynews.data.db.DailyNewsDatabase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class Migration11To12InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "migration-v11-v12"
    @get:Rule val helper = MigrationTestHelper(instrumentation, DailyNewsDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @After fun cleanUp() { instrumentation.targetContext.deleteDatabase(name) }
    @Test fun oldArticlesSurviveAndOfflineTableStartsEmpty() {
        helper.createDatabase(name, 11).apply {
            execSQL("INSERT INTO articles(linkKey,link,feedName,title,summaryEn,articleText,pubDateUtc,pubDateIso,fetchedAtUtc,favoritedAtUtc) VALUES('key','https://example.test','Source','Old title','summary','body','','','2026-09-01','2026-09-02')")
            close()
        }
        helper.runMigrationsAndValidate(name, 12, true, DailyNewsDatabase.MIGRATION_11_12).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM offline_article_bodies").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            migrated.query("SELECT title,favoritedAtUtc,note,tagsJson,readingIndex,readingOffset,readingContentKey FROM articles").use {
                assertTrue(it.moveToFirst())
                assertEquals("Old title", it.getString(0))
                assertEquals("2026-09-02", it.getString(1))
                assertEquals("", it.getString(2))
                assertEquals("[]", it.getString(3))
                assertEquals(0, it.getInt(4))
                assertEquals(0, it.getInt(5))
                assertEquals("", it.getString(6))
            }
        }
    }
}
