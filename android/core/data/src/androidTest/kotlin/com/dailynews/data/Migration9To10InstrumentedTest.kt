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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class Migration9To10InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "migration-v9-v10"
    @get:Rule val helper = MigrationTestHelper(instrumentation, DailyNewsDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @After fun cleanUp() { instrumentation.targetContext.deleteDatabase(name) }
    @Test fun oldReportRetainsContentWithNoInventedDevelopment() {
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO report_items(reportDate,part,position,link,title,source,pubDateUtc,pubDateIso,summaryEn,articleText,summaryZh,alsoLinksJson,eventKey) VALUES('2026-09-07',1,1,'https://source.example/a','Old report','Source','','','','','旧摘要','[]','compiler')")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 10, true, DailyNewsDatabase.MIGRATION_9_10)
        migrated.query("SELECT summaryZh,eventKey,development FROM report_items").use {
            assertTrue(it.moveToFirst())
            assertEquals("旧摘要", it.getString(0))
            assertEquals("compiler", it.getString(1))
            assertTrue(it.isNull(2))
        }
        migrated.close()
    }
}
