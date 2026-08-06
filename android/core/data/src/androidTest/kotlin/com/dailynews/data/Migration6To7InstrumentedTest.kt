package com.dailynews.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailynews.data.db.DailyNewsDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6To7InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "migration-v6-v7"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        DailyNewsDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanUp() {
        instrumentation.targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun versionSixGainsReaderIndexesUnderRoomGeneratedNames() {
        helper.createDatabase(databaseName, 6).apply {
            execSQL(
                "INSERT INTO articles(linkKey,link,feedName,title,summaryEn,articleText,pubDateUtc,pubDateIso,fetchedAtUtc) " +
                    "VALUES('https://example/a','https://example/a','Source','Title','summary','','2026-08-04 10:00 UTC','2026-08-04T10:00+00:00','2026-08-04T10:01:00Z')",
            )
            close()
        }

        // runMigrationsAndValidate validates the whole v7 schema, including both
        // composite indexes by their Room-generated names.
        val migrated = helper.runMigrationsAndValidate(databaseName, 7, true, DailyNewsDatabase.MIGRATION_6_7)

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name IN " +
                "('index_articles_feedName_pubDateIso','index_articles_readAtUtc_feedName_pubDateIso')",
        ).use { cursor ->
            val names = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            assertEquals(setOf("index_articles_feedName_pubDateIso", "index_articles_readAtUtc_feedName_pubDateIso"), names)
        }
        migrated.query("SELECT linkKey FROM articles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://example/a", cursor.getString(0))
        }
        migrated.close()
    }

    // 全链测试（v3 → 最新版本）住在 Migration7To8InstrumentedTest，
    // 那里的终点跟着最新 schema 走；本文件只负责 v6→v7 这一步。
}
