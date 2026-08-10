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

/** Version 9 adds the AIHOT bundled feed to existing installations exactly once. */
@RunWith(AndroidJUnit4::class)
class Migration8To9InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "migration-v8-v9"

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
    fun existingInstallationGainsEnabledAihotFeedAtTheEnd() {
        helper.createDatabase(databaseName, 8).apply {
            execSQL(
                "INSERT INTO feeds(name,url,errorPolicy,enabled,position) " +
                    "VALUES('Existing','https://example.com/feed','warn',1,7)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DailyNewsDatabase.MIGRATION_8_9,
        )

        migrated.query(
            "SELECT name, errorPolicy, enabled, position FROM feeds " +
                "WHERE url = 'https://aihot.virxact.com/feed/full.xml'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("AIHOT", cursor.getString(0))
            assertEquals("block", cursor.getString(1))
            assertTrue(cursor.getInt(2) == 1)
            assertEquals(8, cursor.getInt(3))
            assertFalse(cursor.moveToNext())
        }
        migrated.close()
    }

    @Test
    fun existingMatchingUrlIsPreservedWithoutDuplication() {
        helper.createDatabase(databaseName, 8).apply {
            execSQL(
                "INSERT INTO feeds(name,url,errorPolicy,enabled,position) " +
                    "VALUES('My AI feed','https://aihot.virxact.com/feed/full.xml','warn',0,3)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DailyNewsDatabase.MIGRATION_8_9,
        )

        migrated.query(
            "SELECT name, errorPolicy, enabled, position FROM feeds " +
                "WHERE url = 'https://aihot.virxact.com/feed/full.xml'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("My AI feed", cursor.getString(0))
            assertEquals("warn", cursor.getString(1))
            assertTrue(cursor.getInt(2) == 0)
            assertEquals(3, cursor.getInt(3))
            assertFalse(cursor.moveToNext())
        }
        migrated.close()
    }
}
