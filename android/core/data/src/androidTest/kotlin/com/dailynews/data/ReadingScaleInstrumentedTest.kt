package com.dailynews.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailynews.data.db.*
import com.dailynews.data.repo.ArticleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

/** Self-contained data-test APK only. Never opens the production application's database. */
@RunWith(AndroidJUnit4::class)
class ReadingScaleInstrumentedTest {
    @Test fun exportLargePoolFixture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.dailynews.data.test")
        val name = "large-ui-fixture.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, DailyNewsDatabase::class.java, name).build()
        try {
            db.articles().replaceAll((0 until 50000).map { i ->
                val link = "https://scale.test/$i"
                ArticleEntity(link, link, "Scale", "Scale article $i", "测量用摘要 ".repeat(30), "测量用正文 ".repeat(40), "", "2026-09-07T12:00:00Z", "2026-09-07T12:00:00Z")
            })
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            db.close()
            context.getDatabasePath(name).copyTo(File(context.filesDir, "large-ui-fixture.db"), overwrite = true)
            Unit
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun productionLikeQueriesWithReportHistoryAndFtsReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.dailynews.data.test")
        val name = "reading-scale-history.db"
        val output = StringBuilder("pool,query,results,first_ms,median_ms,max_ms,db_bytes,reopen_query_ms\n")
        try {
            for (size in listOf(1000, 10000, 50000)) {
                context.deleteDatabase(name)
                var db = Room.databaseBuilder(context, DailyNewsDatabase::class.java, name).build()
                try {
                    val rows = (0 until size).map { i ->
                        val link = "https://scale.test/$i"
                        ArticleEntity(link,link,"Scale","Title $i", "summary ".repeat(30),"excerpt ".repeat(40),"","2026-09-01T00:00:00Z","2026-09-01T00:00:00Z",
                            note = if (i % 100 == 0) "编译器 GPU" else "", tagsJson = "[]")
                    }
                    db.articles().replaceAll(rows)
                    db.reports().replaceReports(listOf(ReportEntity("2026-09-01", "SUCCESS", "", "", createdAtUtc = "2026-09-01T00:00:00Z")))
                    db.reports().insertItems((0 until size step 10).map { i -> ReportItemEntity("2026-09-01", 1, i, "https://scale.test/$i", "Title $i", "Scale", "", "", summaryZh = "报告中文证据") })
                    val repo = ArticleRepository(db)
                    val samples = mutableListOf<Triple<String, Int, List<Double>>>()
                    for (query in listOf("编译 GPU", "报告中文", "no-match-token", "Title", "FTS:Title")) {
                        val times = mutableListOf<Double>()
                        var count = 0
                        repeat(5) {
                            val t = System.nanoTime()
                            count = if (query == "FTS:Title") db.articles().search(androidx.sqlite.db.SimpleSQLiteQuery(
                                "SELECT a.* FROM articles a JOIN articles_fts f ON f.linkKey = a.linkKey WHERE articles_fts MATCH ? ORDER BY a.pubDateIso DESC, a.linkKey", arrayOf("Title"))).first().size
                            else repo.search(query).first().size
                            times += (System.nanoTime() - t) / 1e6
                        }
                        assertEquals(when(query) { "编译 GPU" -> size / 100; "报告中文" -> size / 10; "Title", "FTS:Title" -> size; else -> 0 }, count)
                        samples += Triple(query,count,times)
                    }
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                    db.close()
                    val bytes = context.getDatabasePath(name).length()
                    val t = System.nanoTime()
                    db = Room.databaseBuilder(context, DailyNewsDatabase::class.java, name).build()
                    assertEquals(size / 100, ArticleRepository(db).search("编译 GPU").first().size)
                    val reopen = (System.nanoTime() - t) / 1e6
                    for ((query,count,times) in samples) output.append("$size,$query,$count,${times.first()},${times.sorted()[2]},${times.max()},$bytes,$reopen\n")
                } finally { db.close() }
            }
            File(context.filesDir,"reading-scale-history.csv").writeText(output.toString())
            println(output)
        } finally { context.deleteDatabase(name) }
    }
}
