package com.dailynews.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.RunRecoveryRepository
import com.dailynews.data.repo.RunRepository
import com.dailynews.model.*
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.RecoveryRejectedException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Exercises real SQLite persistence in the library test package, never the app database. */
@RunWith(AndroidJUnit4::class)
class RecoveryDiskInstrumentedTest {
    @Test fun nonemptyFrozenInputSurvivesDiskReopenAndCorruptionIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.dailynews.data.test")
        val name = "recovery-acceptance-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DailyNewsDatabase::class.java, name).build()
        var database = open()
        try {
            val date = LocalDate.parse("2026-09-07")
            val config = PipelineConfig().normalized()
            val feeds = listOf(FeedDefinition("Example", "https://example.org/rss"))
            val raw = RawRun(
                RawMeta("2026-09-07T00:00:00Z", "source", "test", 1), 2,
                listOf(
                    Article("Example", "Compiler update", "https://example.org/a?x=1&y=2",
                        "2026-09-07 00:00 UTC", "2026-09-07T00:00:00+00:00", "Summary 中文", "Body\nwith details"),
                    Article("Example", "Chip update", "https://example.org/b",
                        "2026-09-06 23:00 UTC", "2026-09-06T23:00:00+00:00", "Second summary", ""),
                ),
                listOf(FeedResult("Example", feeds.single().url, "ok", articleCount = 2)), 1,
                uniqueSourceCount = 1, uniqueSources = listOf("Example"),
            )
            val encoded = ArtifactJson.codec.encodeToString(raw)
            RunRepository(database).started(raw, date.toString(), 1)
            val initial = ArtifactStore(database)
            initial.write("source", "raw.json", encoded.toByteArray())
            initial.write("source", "run_config.json", ArtifactJson.codec.encodeToString(config).toByteArray())
            initial.write("source", "run_feeds.json", ArtifactJson.codec.encodeToString(feeds).toByteArray())
            initial.write("source", "part1_shortlist", "{\"links\":[\"https://example.org/b\"]}")
            database.close()
            database = open()
            val runs = RunRepository(database)
            val artifacts = ArtifactStore(database)
            assertEquals("RUNNING", database.runs().get("source")?.status)
            runs.recoverInterruptedRuns()
            assertEquals("INTERRUPTED", database.runs().get("source")?.classification)
            val recovery = RunRecoveryRepository(database, artifacts, runs, object : FeedSource {
                override suspend fun enabledFeeds() = feeds
            })
            val recovered = recovery.recover("source", date, config)
            // Every frozen field, including article order, bodies and URL query, must survive.
            assertEquals(raw, recovered.copy(meta = raw.meta))
            assertNotEquals(raw.meta.runId, recovered.meta.runId)
            assertEquals(2, database.runs().get(recovered.meta.runId)?.attempt)
            assertEquals("recovery", database.runs().get(recovered.meta.runId)?.trigger)
            assertEquals(encoded, artifacts.readText("source", "raw.json"))
            assertEquals("{\"links\":[\"https://example.org/b\"]}", artifacts.read("source", "part1_shortlist"))
            artifacts.write("source", "raw.json", "{truncated".toByteArray())
            val rejection = assertFailsWith<RecoveryRejectedException> {
                recovery.recover("source", date, config)
            }
            assertEquals("FAILED", database.runs().get(rejection.failureRunId)?.status)
            assertEquals("FAILED", database.runs().get("source")?.status)
            assertEquals("RUNNING", database.runs().get(recovered.meta.runId)?.status)
        } finally {
            database.close()
            check(context.deleteDatabase(name))
        }
    }
}
