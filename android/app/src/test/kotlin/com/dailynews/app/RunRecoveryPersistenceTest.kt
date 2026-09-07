package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.RunRepository
import com.dailynews.data.repo.RunRecoveryRepository
import com.dailynews.model.*
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.RecoveryRejectedException
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
class RunRecoveryPersistenceTest {
    @Test fun frozenInputSurvivesReopenAndDriftOrSuccessfulSourcesLeaveExplicitFailures() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val runs = RunRepository(database)
            val store = ArtifactStore(database)
            val date = LocalDate.parse("2026-09-07")
            val config = PipelineConfig().normalized()
            val raw = RawRun(RawMeta("2026-09-07T00:00:00Z", "source", "test", 1), 0, emptyList(), emptyList(), 1)
            var feedList = listOf(FeedDefinition("Example", "https://example.org/rss"))
            val feeds = object : FeedSource { override suspend fun enabledFeeds() = feedList }
            runs.started(raw, date.toString(), 1)
            suspend fun snapshot(name: String, content: String) = store.write("source", name, content.toByteArray())
            snapshot("raw.json", ArtifactJson.codec.encodeToString(raw))
            snapshot("run_config.json", ArtifactJson.codec.encodeToString(config))
            snapshot("run_feeds.json", ArtifactJson.codec.encodeToString(feedList))
            runs.finished(RunExecutionResult.Failed(date, "source", "editorial", "interrupted"))
            // A new repository/store instance reads persisted state rather than retained objects.
            val recovery = RunRecoveryRepository(database, ArtifactStore(database), RunRepository(database), feeds)
            val resumed = recovery.recover("source", date, config)
            assertEquals("FAILED", database.runs().get("source")?.status)
            assertEquals(raw.articles, resumed.articles)
            assertEquals(raw.feedResults, resumed.feedResults)
            assertNotEquals(raw.meta.runId, resumed.meta.runId)
            assertEquals("RUNNING", database.runs().get(resumed.meta.runId)?.status)
            assertEquals(2, database.runs().get(resumed.meta.runId)?.attempt)
            val drift = assertFailsWith<RecoveryRejectedException> { recovery.recover("source", date, config.copy(part1MaxItems = 20)) }
            assertEquals("FAILED", database.runs().get(drift.failureRunId)?.status)
            feedList = listOf(feedList.single().copy(url = "https://example.org/changed"))
            assertFailsWith<RecoveryRejectedException> { recovery.recover("source", date, config) }
            feedList = listOf(FeedDefinition("Example", "https://example.org/rss"))
            snapshot("raw.json", "{truncated")
            assertFailsWith<RecoveryRejectedException> { recovery.recover("source", date, config) }
            snapshot("raw.json", ArtifactJson.codec.encodeToString(raw))
            assertFailsWith<RecoveryRejectedException> { recovery.recover("missing-run", date, config) }
            assertFailsWith<RecoveryRejectedException> { recovery.recover("source", date.plusDays(1), config) }
            database.runs().upsert(requireNotNull(database.runs().get("source")).copy(status = "SUCCESS"))
            assertFailsWith<RecoveryRejectedException> { recovery.recover("source", date, config) }
        } finally { database.close() }
        Unit
    }
}
