package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.db.*
import com.dailynews.data.repo.PeriodicReportRepository
import com.dailynews.data.repo.PeriodKind
import com.dailynews.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeriodicPersonalizationTest {
    @Test fun publishedPeriodOnlyPreservesTrajectoryAndFrozenWatches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            for (day in 1..4) {
                val date = "2026-09-0$day"
                database.reports().upsert(ReportEntity(date, if (day == 3) "FAILED" else "SUCCESS", "", "", createdAtUtc = date))
                database.reports().insertItems(listOf(ReportItemEntity(date, 1, 1, "https://example.test/$day", "Title $day", "Source", "", "", summaryZh = "摘要 $day", eventKey = "chip")))
            }
            val watches = WatchPreferences(topics = listOf(" AI "), events = listOf(EventWatch("chip", "Chip", "2026-09-01")))
            val input = PeriodicReportRepository(database).collectInput(PeriodKind.WEEKLY, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03"), watches)
            assertEquals(listOf("2026-09-01", "2026-09-02"), input.reportDates)
            assertEquals(listOf("https://example.test/1", "https://example.test/2"), input.items.map { it.link })
            assertEquals(2, input.sourceArticleCount)
            assertEquals(listOf("AI"), input.watches.topics)
            val serialized = ArtifactJson.compact.encodeToString(input)
            assertTrue(serialized.contains("source_article_count"))
            assertTrue(serialized.contains("afterReportDate"))
            assertEquals(listOf("chip"), input.watches.events.map { it.eventKey })
        } finally { database.close() }
    }
}
