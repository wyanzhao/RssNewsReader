package com.dailynews.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption

/**
 * Single source of truth for the Room schema version.
 *
 * Both `@Database(version=)` and the state-backup envelope must read it. Previously
 * the envelope carried its own literal default; on v8→v9 only one half was updated,
 * so every v9 export claimed to be v8 — and the import-side "reject higher-version
 * backups" guard could never fire. Two copies of one number will drift.
 */
const val DAILYNEWS_SCHEMA_VERSION = 9

@Database(
    entities = [
        FeedEntity::class,
        ArticleEntity::class,
        ArticleFtsEntity::class,
        FetchLogEntity::class,
        RunArtifactEntity::class,
        RunEntity::class,
        RunLogEntity::class,
        LlmCallEntity::class,
        LlmUsageMonthEntity::class,
        ReportEntity::class,
        ReportItemEntity::class,
        EditorialCacheEntity::class,
        SeenLinkEntity::class,
        PeriodicReportEntity::class,
    ],
    version = DAILYNEWS_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class DailyNewsDatabase : RoomDatabase() {
    abstract fun feeds(): FeedDao
    abstract fun articles(): ArticleDao
    abstract fun fetchLogs(): FetchLogDao
    abstract fun runArtifacts(): RunArtifactDao
    abstract fun runs(): RunDao
    abstract fun runLogs(): RunLogDao
    abstract fun llmCalls(): LlmCallDao
    abstract fun llmUsageMonths(): LlmUsageMonthDao
    abstract fun reports(): ReportDao
    abstract fun editorialCache(): EditorialCacheDao
    abstract fun seenLinks(): SeenLinksDao
    abstract fun periodicReports(): PeriodicReportDao

    companion object {
        /** Splits the review-failure reason out of `groupsJson`, which must stay a source-group list. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reports ADD COLUMN failureReason TEXT")
                db.execSQL("ALTER TABLE reports ADD COLUMN publishedAtUtc TEXT")
                db.execSQL("UPDATE reports SET publishedAtUtc = createdAtUtc WHERE status = 'SUCCESS'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `llm_usage_monthly` (`month` TEXT NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `callCount` INTEGER NOT NULL, PRIMARY KEY(`month`))",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN lastFetchAtUtc TEXT")
                db.execSQL("ALTER TABLE feeds ADD COLUMN lastStatus TEXT")
                db.execSQL("ALTER TABLE feeds ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE feeds ADD COLUMN newestItemDateIso TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `articles` (
                        `linkKey` TEXT NOT NULL,
                        `link` TEXT NOT NULL,
                        `feedName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `summaryEn` TEXT NOT NULL,
                        `articleText` TEXT NOT NULL,
                        `pubDateUtc` TEXT NOT NULL,
                        `pubDateIso` TEXT NOT NULL,
                        `fetchedAtUtc` TEXT NOT NULL,
                        `enrichedAtUtc` TEXT,
                        `readAtUtc` TEXT,
                        `favoritedAtUtc` TEXT,
                        `reportedDate` TEXT,
                        PRIMARY KEY(`linkKey`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_pubDateIso` ON `articles` (`pubDateIso`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_reportedDate` ON `articles` (`reportedDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_favoritedAtUtc` ON `articles` (`favoritedAtUtc`)")
                db.execSQL(
                    """INSERT OR IGNORE INTO articles(
                        linkKey, link, feedName, title, summaryEn, articleText,
                        pubDateUtc, pubDateIso, fetchedAtUtc, enrichedAtUtc,
                        readAtUtc, favoritedAtUtc, reportedDate
                    )
                    SELECT rtrim(link, '/'), link, source, title, summaryZh, '',
                           '', '', savedAtUtc, NULL, NULL, savedAtUtc, NULL
                    FROM favorites
                    ORDER BY savedAtUtc DESC, rowid DESC""".trimIndent(),
                )
                val expectedFavoriteIdentities = db.query("SELECT COUNT(DISTINCT rtrim(link, '/')) FROM favorites").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                val migratedFavoriteIdentities = db.query("SELECT COUNT(*) FROM articles WHERE favoritedAtUtc IS NOT NULL").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                check(migratedFavoriteIdentities == expectedFavoriteIdentities) {
                    "favorite migration lost normalized identities: expected=$expectedFavoriteIdentities actual=$migratedFavoriteIdentities"
                }
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `fetch_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `feedName` TEXT NOT NULL,
                        `fetchedAtUtc` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `error` TEXT,
                        `itemCount` INTEGER NOT NULL,
                        `newCount` INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fetch_log_feedName` ON `fetch_log` (`feedName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fetch_log_fetchedAtUtc` ON `fetch_log` (`fetchedAtUtc`)")
                createArticleFts(db)
                db.execSQL("DROP TABLE favorites")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `run_artifacts` (
                        `runId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `gzipBody` BLOB NOT NULL,
                        `createdAtUtc` TEXT NOT NULL,
                        PRIMARY KEY(`runId`, `name`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_artifacts_runId` ON `run_artifacts` (`runId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_artifacts_createdAtUtc` ON `run_artifacts` (`createdAtUtc`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE report_items ADD COLUMN summaryEn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE report_items ADD COLUMN articleText TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """UPDATE report_items SET
                        summaryEn = COALESCE((SELECT a.summaryEn FROM articles a WHERE a.linkKey = rtrim(report_items.link, '/') LIMIT 1), ''),
                        articleText = COALESCE((SELECT a.articleText FROM articles a WHERE a.linkKey = rtrim(report_items.link, '/') LIMIT 1), '')
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Epic U reader indexes: names must match Room-generated names byte-for-byte or validateMigration fails.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_feedName_pubDateIso` ON `articles` (`feedName`, `pubDateIso`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_readAtUtc_feedName_pubDateIso` ON `articles` (`readAtUtc`, `feedName`, `pubDateIso`)")
            }
        }

        /** Epic V: persist the cross-day story id and create the periodic-reports table. Index names and CREATE TABLE SQL must match Room's generated form byte-for-byte. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE report_items ADD COLUMN eventKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_items_eventKey` ON `report_items` (`eventKey`)")
                // Backfill: editorial_cache stored historical event keys by link. Most
                // backfilled story lines are single-article (the old key was a per-title
                // slug, and Chinese titles collapsed to empty), so real cross-source
                // clustering starts with the first v8 report; the cost of this UPDATE
                // buys day-one historical depth and is worth it.
                db.execSQL(
                    """
                    UPDATE report_items SET eventKey = COALESCE((
                        SELECT c.eventKey FROM editorial_cache c
                        WHERE c.link = report_items.link AND c.eventKey IS NOT NULL AND c.eventKey <> ''
                        LIMIT 1
                    ), '')
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `periodic_reports` (
                        `periodKey` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `periodStartDate` TEXT NOT NULL,
                        `periodEndDate` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `markdown` TEXT NOT NULL,
                        `sourceReportDatesJson` TEXT NOT NULL,
                        `itemCount` INTEGER NOT NULL,
                        `failureReason` TEXT,
                        `createdAtUtc` TEXT NOT NULL,
                        `publishedAtUtc` TEXT,
                        PRIMARY KEY(`periodKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_periodic_reports_kind` ON `periodic_reports` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_periodic_reports_createdAtUtc` ON `periodic_reports` (`createdAtUtc`)")
            }
        }

        /** Adds the AIHOT bundled feed once for existing installations. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO feeds(
                        name, url, errorPolicy, enabled, position,
                        lastFetchAtUtc, lastStatus, lastError, newestItemDateIso
                    ) VALUES(
                        'AIHOT',
                        'https://aihot.virxact.com/feed/full.xml',
                        'block',
                        1,
                        COALESCE((SELECT MAX(position) + 1 FROM feeds), 0),
                        NULL, NULL, NULL, NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): DailyNewsDatabase {
            val appContext = context.applicationContext
            backupDatabaseVersionIfNeeded(appContext, 3, "dailynews-v3.db")
            backupDatabaseVersionIfNeeded(appContext, 4, "dailynews-v4.db")
            backupDatabaseVersionIfNeeded(appContext, 7, "dailynews-v7.db")
            backupDatabaseVersionIfNeeded(appContext, 8, "dailynews-v8.db")
            return Room.databaseBuilder(
            appContext,
            DailyNewsDatabase::class.java,
            "dailynews.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()
        }

        internal fun backupDatabaseVersionIfNeeded(
            context: Context,
            expectedVersion: Int,
            backupName: String,
            databaseName: String = "dailynews.db",
        ) {
            val source = context.getDatabasePath(databaseName)
            if (!source.isFile || databaseVersion(source) != expectedVersion) return
            backupV3DatabaseIfNeeded(context, databaseName, backupName)
        }

        private fun databaseVersion(source: File): Int? = runCatching {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA user_version", null).use { cursor ->
                    cursor.takeIf { it.moveToFirst() }?.getInt(0)
                }
            }
        }.getOrNull()

        internal fun createArticleFts(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `articles_fts` USING FTS4(`linkKey` TEXT NOT NULL, `title` TEXT NOT NULL, `summaryEn` TEXT NOT NULL, content=`articles`)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_BEFORE_UPDATE BEFORE UPDATE ON `articles` BEGIN DELETE FROM `articles_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_BEFORE_DELETE BEFORE DELETE ON `articles` BEGIN DELETE FROM `articles_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_AFTER_UPDATE AFTER UPDATE ON `articles` BEGIN INSERT INTO `articles_fts`(`docid`, `linkKey`, `title`, `summaryEn`) VALUES (NEW.`rowid`, NEW.`linkKey`, NEW.`title`, NEW.`summaryEn`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_articles_fts_AFTER_INSERT AFTER INSERT ON `articles` BEGIN INSERT INTO `articles_fts`(`docid`, `linkKey`, `title`, `summaryEn`) VALUES (NEW.`rowid`, NEW.`linkKey`, NEW.`title`, NEW.`summaryEn`); END")
            db.execSQL("INSERT INTO articles_fts(articles_fts) VALUES('rebuild')")
        }

        internal fun backupV3DatabaseIfNeeded(
            context: Context,
            databaseName: String = "dailynews.db",
            backupName: String = "dailynews-v3.db",
        ) {
            val source = context.getDatabasePath(databaseName)
            if (!source.isFile) return
            val backupDirectory = File(context.filesDir, "backup")
            val complete = File(backupDirectory, "$backupName.complete")
            if (complete.isFile) return
            backupDirectory.mkdirs()
            listOf("" to "", "-wal" to "-wal", "-shm" to "-shm").forEach { (sourceSuffix, targetSuffix) ->
                val input = File(source.path + sourceSuffix)
                if (input.isFile) atomicCopy(input, File(backupDirectory, "$backupName$targetSuffix"))
            }
            val temporary = File(backupDirectory, ".$backupName.complete.tmp")
            temporary.writeText("source=${source.path}\n", Charsets.UTF_8)
            moveReplacing(temporary, complete)
        }

        private fun atomicCopy(source: File, target: File) {
            val temporary = File(target.parentFile, ".${target.name}.tmp")
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            moveReplacing(temporary, target)
        }

        private fun moveReplacing(source: File, target: File) {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
