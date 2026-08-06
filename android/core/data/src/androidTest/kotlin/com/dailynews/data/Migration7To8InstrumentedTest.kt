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

/** Epic V：report_items.eventKey（含回填）与 periodic_reports 建表。 */
@RunWith(AndroidJUnit4::class)
class Migration7To8InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "migration-v7-v8"
    private val fullChainDatabaseName = "migration-v3-v8"

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
        instrumentation.targetContext.deleteDatabase(fullChainDatabaseName)
    }

    @Test
    fun versionSevenGainsEventKeyBackfilledFromEditorialCache() {
        helper.createDatabase(databaseName, 7).apply {
            execSQL(
                "INSERT INTO report_items(reportDate,part,position,link,title,source,pubDateUtc,pubDateIso,summaryEn,articleText,summaryZh,alsoLinksJson) " +
                    "VALUES('2026-08-04',1,1,'https://example/a','Title A','Source','2026-08-04 10:00 UTC','2026-08-04T10:00+00:00','','','中文摘要 A','[]')",
            )
            // 第二条在缓存里没有对应 event key，迁移后应保持空串而不是继承上一条。
            execSQL(
                "INSERT INTO report_items(reportDate,part,position,link,title,source,pubDateUtc,pubDateIso,summaryEn,articleText,summaryZh,alsoLinksJson) " +
                    "VALUES('2026-08-04',1,2,'https://example/b','Title B','Source','2026-08-04 11:00 UTC','2026-08-04T11:00+00:00','','','中文摘要 B','[]')",
            )
            execSQL(
                "INSERT INTO editorial_cache(cacheKey,link,source,title,eventKey) " +
                    "VALUES('key-a','https://example/a','Source','Title A','openai-funding-round')",
            )
            close()
        }

        // runMigrationsAndValidate 校验整个 v8 schema，包含索引的 Room 生成名。
        val migrated = helper.runMigrationsAndValidate(databaseName, 8, true, DailyNewsDatabase.MIGRATION_7_8)

        migrated.query("SELECT link, eventKey FROM report_items ORDER BY position").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://example/a", cursor.getString(0))
            assertEquals("openai-funding-round", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("https://example/b", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name IN " +
                "('index_report_items_eventKey','index_periodic_reports_kind','index_periodic_reports_createdAtUtc')",
        ).use { cursor ->
            val names = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            assertEquals(
                setOf("index_report_items_eventKey", "index_periodic_reports_kind", "index_periodic_reports_createdAtUtc"),
                names,
            )
        }
        migrated.query("SELECT COUNT(*) FROM periodic_reports").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun periodicReportsTableAcceptsIsoWeekKeys() {
        helper.createDatabase(databaseName, 7).close()
        val migrated = helper.runMigrationsAndValidate(databaseName, 8, true, DailyNewsDatabase.MIGRATION_7_8)

        migrated.execSQL(
            "INSERT INTO periodic_reports(periodKey,kind,periodStartDate,periodEndDate,status,markdown,sourceReportDatesJson,itemCount,createdAtUtc) " +
                "VALUES('2026-W32','WEEKLY','2026-08-03','2026-08-09','SUCCESS','# 周报','[\"2026-08-04\"]',12,'2026-08-10T00:00:00Z')",
        )
        migrated.query("SELECT kind, itemCount FROM periodic_reports WHERE periodKey = '2026-W32'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WEEKLY", cursor.getString(0))
            assertEquals(12, cursor.getInt(1))
        }
        migrated.close()
    }

    /** 全链测试的终点始终是最新版本，这样一次覆盖安装的真实路径永远被验证。 */
    @Test
    fun fullChainFromVersionThreeReachesVersionEightWithFavoritesIntact() {
        helper.createDatabase(fullChainDatabaseName, 3).apply {
            execSQL("INSERT INTO feeds(name,url,errorPolicy,enabled,position) VALUES('Source','https://feed','block',1,0)")
            execSQL(
                "INSERT INTO favorites(link,title,source,summaryZh,savedAtUtc) " +
                    "VALUES('https://example/chain/','Chained title','Source','收藏摘要','2026-08-03T01:02:03Z')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            fullChainDatabaseName,
            8,
            true,
            DailyNewsDatabase.MIGRATION_3_4,
            DailyNewsDatabase.MIGRATION_4_5,
            DailyNewsDatabase.MIGRATION_5_6,
            DailyNewsDatabase.MIGRATION_6_7,
            DailyNewsDatabase.MIGRATION_7_8,
        )

        migrated.query("SELECT linkKey, favoritedAtUtc FROM articles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://example/chain", cursor.getString(0))
            assertEquals("2026-08-03T01:02:03Z", cursor.getString(1))
        }
        migrated.close()
    }
}
