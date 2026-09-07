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
class ComparisonPersistenceTest {
    @Test fun snapshotUsesProductionFeedArrayAndArmWritesPreserveSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = ArtifactStore(database)
            val raw = RawRun(RawMeta("2026-09-07T00:00:00Z", "source", "test", 1), 0, emptyList(), emptyList(), 1)
            val runs = RunRepository(database)
            runs.started(raw, "2026-09-07", 1)
            runs.finished(RunExecutionResult.Failed(LocalDate.parse("2026-09-07"), "source", "editorial", "test"))
            val feeds = listOf(FeedDefinition("Example", "https://example.org/rss"))
            val history = com.dailynews.pipeline.context.Part1ShortlistContext(
                LlmMeta("2026-09-07", raw.meta.generatedAtUtc, "source", "report.md"), 0, 0, emptyList(), emptyList())
            suspend fun save(name: String, text: String) = store.write("source", name, text.toByteArray())
            save("raw.json", ArtifactJson.codec.encodeToString(raw))
            save("run_feeds.json", ArtifactJson.codec.encodeToString(feeds))
            save("run_config.json", ArtifactJson.codec.encodeToString(PipelineConfig()))
            save("part1_shortlist_context.json", ArtifactJson.codec.encodeToString(history))
            save("reviewed-report.md", "original report")
            val repository = com.dailynews.data.repo.ComparisonRepository(database, ArtifactStore(database))
            assertEquals(feeds, repository.snapshot("source").feeds.feeds)
            val sink = repository.artifacts("source", "experiment")
            sink.write("experiment-baseline", "reviewed-report.md", "trial report".toByteArray())
            assertEquals("original report", store.readText("source", "reviewed-report.md"))
            assertEquals("trial report", store.readText("source", "comparisons/experiment/baseline/reviewed-report.md"))
            assertFailsWith<IllegalArgumentException> { sink.write("source", "raw.json", byteArrayOf()) }
            assertFailsWith<IllegalArgumentException> { sink.write("experiment-candidate", "../raw.json", byteArrayOf()) }
            repository.manifest("source", "experiment", kotlinx.serialization.json.buildJsonObject {
                put("status", kotlinx.serialization.json.JsonPrimitive("complete"))
            })
            assertEquals("\"complete\"", repository.manifest("source", "experiment")?.get("status").toString())
            assertEquals("FAILED", database.runs().get("source")?.status)
            save("run_feeds.json", "{truncated")
            assertFails { repository.snapshot("source") }
        } finally { database.close() }
        Unit
    }
}
