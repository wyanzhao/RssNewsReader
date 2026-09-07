package com.dailynews.pipeline

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.StageTimer
import com.dailynews.pipeline.observability.StageTiming
import com.dailynews.pipeline.ports.RunLogSink
import com.dailynews.pipeline.ports.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class StageTimerTest {
    @Test
    fun `monotonic duration records successful failed and cancelled work`() = runBlocking {
        val records = mutableListOf<StageTiming>()
        val sink = object : RunLogSink {
            override suspend fun log(runId: String, step: String, level: LogLevel, message: String) {
                assertEquals("run", runId)
                assertEquals(StageTimer.LOG_STEP, step)
                records += ArtifactJson.codec.decodeFromString<StageTiming>(message)
            }
        }
        var ticks = 0L
        val timer = StageTimer(sink) { ticks }
        assertEquals(42, timer.measure("run", "test") { ticks += 123_000_000; 42 })
        val failed = IllegalStateException("original")
        assertSame(failed, assertFailsWith<IllegalStateException> {
            timer.measure("run", "test") { ticks += 7_000_000; throw failed }
        })
        assertFailsWith<CancellationException> { timer.measure("run", "test") { throw CancellationException("stopped") } }
        assertEquals(listOf(123L, 7L, 0L), records.map { it.elapsedMs })
        assertEquals(listOf("success", "failed", "cancelled"), records.map { it.outcome })
    }

    @Test
    fun `broken telemetry does not change the operation result`() = runBlocking {
        val sink = object : RunLogSink {
            override suspend fun log(runId: String, step: String, level: LogLevel, message: String) { error("disk unavailable") }
        }
        assertEquals("report", StageTimer(sink).measure("run", "publish") { "report" })
    }
}
