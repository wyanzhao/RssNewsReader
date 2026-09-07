package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.db.*
import com.dailynews.data.repo.ReportRepository
import com.dailynews.model.AssembledReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorialHistoryPersistenceTest {
    @Test fun watchedBaselineFindsOldSuccessfulReportExcludingFailedSameDayAndOtherEvents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            for ((date, status) in listOf("2026-01-01" to "SUCCESS", "2026-01-02" to "SUCCESS", "2026-09-06" to "FAILED", "2026-09-07" to "SUCCESS")) {
                database.reports().upsert(ReportEntity(date, status, "report", "digest", createdAtUtc = "2026-09-08T02:00:00Z"))
                database.reports().insertItems(listOf(ReportItemEntity(date, 1, 1, "https://example.org/$date", "Story", "Example", "time", "time", summaryZh = "已发表摘要", eventKey = "compiler")))
            }
            val repository = ReportRepository(database, context)
            val event = repository.latestBefore("compiler", "2026-09-07")
            assertEquals("2026-01-02", event?.coveredOn)
            assertEquals("https://example.org/2026-01-02", event?.link)
            assertNull(repository.latestBefore("other-event", "2026-09-07"))
            assertEquals(listOf("2026-09-07", "2026-01-02", "2026-01-01"), repository.story("compiler").first().map { it.reportDate })
            assertEquals(mapOf("compiler" to 3), repository.storyDepth(listOf("compiler")).first())
            assertTrue(repository.before("2026-09-07", "2026-08-31").isEmpty())
        } finally { database.close() }
    }

    @Test fun actualReportDatesExcludeSameDayAndFailedReportsAndEmptyReplacementIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            for ((date, status) in listOf("2026-09-05" to "FAILED", "2026-09-06" to "SUCCESS", "2026-09-07" to "SUCCESS")) {
                database.reports().upsert(ReportEntity(date, status, "saved report", "saved digest", createdAtUtc = "2026-09-08T02:00:00Z"))
                database.reports().insertItems(listOf(ReportItemEntity(date, 1, 1, "https://example.org/$date", "Story", "Example", "time", "time", summaryZh = "已发表摘要")))
            }
            val repository = ReportRepository(database, context)
            val events = repository.before("2026-09-07", "2026-08-31")
            assertEquals(listOf("2026-09-06"), events.map { it.coveredOn })
            assertFailsWith<IllegalArgumentException> { repository.publish(AssembledReport("2026-09-07", "empty", "empty", emptyList())) }
            assertEquals("saved report", database.reports().get("2026-09-07")?.markdown)
            assertEquals(1, database.reports().topItemsNow("2026-09-07", 30).size)
        } finally { database.close() }
    }
}
