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
class RecoveryAccountingPersistenceTest {
    @Test fun parentArtifactsAndMissingRecordsAreReadFromPersistentStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = ArtifactStore(db)
            val runs = RunRepository(db)
            for (id in listOf("source", "resumed")) {
                val raw = RawRun(RawMeta("2026-09-07T00:00:00Z", id, "test", 1), 0, emptyList(), emptyList(), 1)
                runs.started(raw, "2026-09-07", 1, if (id == "resumed") "recovery" else "manual")
                db.runs().upsert(requireNotNull(db.runs().get(id)).copy(status = "SUCCESS"))
                com.dailynews.data.repo.LlmCallRepository(db).record(id,"EDITOR","test","model",1,1,0,"success")
                com.dailynews.data.repo.RunLogRepository(db).log(id, "llm_attempt_measurement", com.dailynews.pipeline.ports.LogLevel.INFO,
                    ArtifactJson.codec.encodeToString(com.dailynews.pipeline.observability.LlmAttemptMeasurement("call:0","plan",contractAttempt=0,physicalAttempt=0,outcome="success",billedCostUsd="0.02")))
            }
            assertFalse(runs.recoveryAccounting("resumed", store).complete)
            store.write("resumed", "recovery.json", "{\"source_run_id\":\"source\",\"mode\":\"frozen_input\"}".toByteArray())
            val result = RunRepository(db).recoveryAccounting("resumed", ArtifactStore(db))
            assertTrue(result.complete)
            assertEquals("0.04", result.reportedUsd)
            assertEquals(listOf("resumed", "source"), result.runIds)
            store.write("resumed", "recovery.json", "{truncated".toByteArray())
            assertFalse(runs.recoveryAccounting("resumed", store).complete)
        } finally { db.close() }
        Unit
    }
}
