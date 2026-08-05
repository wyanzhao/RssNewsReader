package com.dailynews.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailynews.data.db.DailyNewsDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Migration3To4InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "migration-v3-v4"
    private val v4DatabaseName = "migration-v4-v5"
    private val fullChainDatabaseName = "migration-v3-v6"
    private val v1DatabaseName = "migration-v1-v2"
    private val v2DatabaseName = "migration-v2-v3"

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
        instrumentation.targetContext.deleteDatabase(v4DatabaseName)
        instrumentation.targetContext.deleteDatabase(fullChainDatabaseName)
        instrumentation.targetContext.deleteDatabase(v1DatabaseName)
        instrumentation.targetContext.deleteDatabase(v2DatabaseName)
    }

    @Test
    fun reportFailureColumnsAndMonthlyUsageSurviveEarlyMigrations() {
        helper.createDatabase(v1DatabaseName, 1).apply {
            execSQL(
                "INSERT INTO reports(reportDate,status,markdown,topNMarkdown,groupsJson,createdAtUtc) " +
                    "VALUES('2026-08-01','SUCCESS','# report','top','[]','2026-08-01T00:00:00Z')",
            )
            close()
        }
        val v2 = helper.runMigrationsAndValidate(v1DatabaseName, 2, true, DailyNewsDatabase.MIGRATION_1_2)
        v2.query("SELECT failureReason,publishedAtUtc FROM reports WHERE reportDate='2026-08-01'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("2026-08-01T00:00:00Z", cursor.getString(1))
        }
        v2.close()

        helper.createDatabase(v2DatabaseName, 2).close()
        val v3 = helper.runMigrationsAndValidate(v2DatabaseName, 3, true, DailyNewsDatabase.MIGRATION_2_3)
        v3.query("SELECT count(*) FROM llm_usage_monthly").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v3.close()
    }

    @Test
    fun versionThreeMigratesThroughVersionSixWithReportSourceSnapshot() {
        helper.createDatabase(fullChainDatabaseName, 3).apply {
            execSQL("INSERT INTO feeds(name,url,errorPolicy,enabled,position) VALUES('Source','https://feed','block',1,0)")
            execSQL(
                "INSERT INTO favorites(link,title,source,summaryZh,savedAtUtc) " +
                    "VALUES('https://example/snapshot/','Saved title','Source','source material','2026-08-03T01:02:03Z')",
            )
            execSQL(
                "INSERT INTO reports(reportDate,status,markdown,topNMarkdown,groupsJson,createdAtUtc,failureReason,publishedAtUtc) " +
                    "VALUES('2026-08-04','SUCCESS','# report','top','[]','2026-08-04T00:00:00Z',NULL,'2026-08-04T00:00:00Z')",
            )
            execSQL(
                "INSERT INTO report_items(reportDate,part,position,link,title,source,pubDateUtc,pubDateIso,summaryZh,alsoLinksJson) " +
                    "VALUES('2026-08-04',2,1,'https://example/snapshot/','Saved title','Source','','','中文摘要','[]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            fullChainDatabaseName,
            6,
            true,
            DailyNewsDatabase.MIGRATION_3_4,
            DailyNewsDatabase.MIGRATION_4_5,
            DailyNewsDatabase.MIGRATION_5_6,
        )

        migrated.query("SELECT summaryEn,articleText FROM report_items WHERE reportDate='2026-08-04'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("source material", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        migrated.query("SELECT count(*) FROM run_artifacts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun v4RunsSurviveMigrationAndGainRoomBackedArtifacts() {
        helper.createDatabase(v4DatabaseName, 4).apply {
            execSQL(
                "INSERT INTO runs(runId,reportDate,status,classification,validatorExitCode,attempt,trigger,blockingReasonsJson,warningsJson,countsJson,startedAtUtc,finishedAtUtc) " +
                    "VALUES('run-v4','2026-08-04','SUCCESS','SUCCESS',0,1,'test','[]','[]','{}','2026-08-04T00:00:00Z','2026-08-04T00:01:00Z')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            v4DatabaseName,
            5,
            true,
            DailyNewsDatabase.MIGRATION_4_5,
        )

        migrated.query("SELECT status FROM runs WHERE runId='run-v4'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SUCCESS", cursor.getString(0))
        }
        migrated.query("SELECT count(*) FROM run_artifacts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun versionAwareFuseCopiesV4DatabaseBeforeOpeningV5() {
        helper.createDatabase(v4DatabaseName, 4).close()
        val context = instrumentation.targetContext
        val backupName = "backup-fuse-v4.db"
        val backupDirectory = File(context.filesDir, "backup")
        listOf(backupName, "$backupName-wal", "$backupName-shm", "$backupName.complete").forEach {
            File(backupDirectory, it).delete()
        }

        DailyNewsDatabase.backupDatabaseVersionIfNeeded(context, 4, backupName, v4DatabaseName)

        assertTrue(File(backupDirectory, backupName).isFile)
        assertTrue(File(backupDirectory, "$backupName.complete").isFile)
    }

    @Test
    fun favoriteRowsBecomeArticleStateAndFtsIsReady() {
        helper.createDatabase(databaseName, 3).apply {
            execSQL(
                "INSERT INTO feeds(name,url,errorPolicy,enabled,position) VALUES('Source','https://feed','block',1,0)",
            )
            execSQL(
                "INSERT INTO favorites(link,title,source,summaryZh,savedAtUtc) VALUES('https://example/item/','Saved title','Source','收藏摘要','2026-08-03T01:02:03Z')",
            )
            execSQL(
                "INSERT INTO favorites(link,title,source,summaryZh,savedAtUtc) VALUES('https://example/item','Newer title','Source','较新的收藏摘要','2026-08-04T01:02:03Z')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            DailyNewsDatabase.MIGRATION_3_4,
        )

        migrated.query("SELECT linkKey, link, title, favoritedAtUtc FROM articles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://example/item", cursor.getString(0))
            assertEquals("https://example/item", cursor.getString(1))
            assertEquals("Newer title", cursor.getString(2))
            assertEquals("2026-08-04T01:02:03Z", cursor.getString(3))
            assertFalse(cursor.moveToNext())
        }
        migrated.query("SELECT title FROM articles_fts WHERE articles_fts MATCH 'Newer'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Newer title", cursor.getString(0))
        }
        migrated.query("SELECT lastFetchAtUtc, lastStatus, lastError, newestItemDateIso FROM feeds").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue((0..3).all(cursor::isNull))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='favorites'").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        migrated.close()
    }

    @Test
    fun preMigrationFuseCopiesDatabaseAndWalSidecarsOnce() {
        val context = instrumentation.targetContext
        val databaseFile = context.getDatabasePath("backup-fuse-source.db")
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(byteArrayOf(1, 2, 3))
        File(databaseFile.path + "-wal").writeBytes(byteArrayOf(4, 5))
        File(databaseFile.path + "-shm").writeBytes(byteArrayOf(6))
        val backupName = "backup-fuse-v3.db"
        val backupDirectory = File(context.filesDir, "backup")
        listOf(backupName, "$backupName-wal", "$backupName-shm", "$backupName.complete").forEach {
            File(backupDirectory, it).delete()
        }

        DailyNewsDatabase.backupV3DatabaseIfNeeded(context, databaseFile.name, backupName)
        databaseFile.writeBytes(byteArrayOf(9))
        DailyNewsDatabase.backupV3DatabaseIfNeeded(context, databaseFile.name, backupName)

        assertEquals(listOf<Byte>(1, 2, 3), File(backupDirectory, backupName).readBytes().toList())
        assertEquals(listOf<Byte>(4, 5), File(backupDirectory, "$backupName-wal").readBytes().toList())
        assertEquals(listOf<Byte>(6), File(backupDirectory, "$backupName-shm").readBytes().toList())
        assertTrue(File(backupDirectory, "$backupName.complete").isFile)
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }
}
