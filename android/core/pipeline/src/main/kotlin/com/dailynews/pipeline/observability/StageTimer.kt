package com.dailynews.pipeline.observability

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.RunLogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class StageTiming(
    val stage: String,
    val elapsedMs: Long,
    val outcome: String,
)

/** Monotonic wall time includes retries within the measured operation, never API price estimates. */
class StageTimer(
    private val logs: RunLogSink,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun <T> measure(runId: String, stage: String, outcomeOf: (T) -> String = { "success" }, block: suspend () -> T): T {
        emit(runId, START_STEP, stage)
        val started = nanoTime()
        var outcome = "success"
        try {
            return block().also { outcome = outcomeOf(it) }
        } catch (error: CancellationException) {
            outcome = "cancelled"
            throw error
        } catch (error: Throwable) {
            outcome = "failed"
            throw error
        } finally {
            record(runId, stage, ((nanoTime() - started) / 1_000_000).coerceAtLeast(0), outcome)
        }
    }

    suspend fun record(runId: String, stage: String, elapsedMs: Long, outcome: String) {
        val record = StageTiming(stage, elapsedMs, outcome)
        emit(runId, LOG_STEP, ArtifactJson.compact.encodeToString(record))
    }

    private suspend fun emit(runId: String, step: String, message: String) {
        // Losing metrics must never replace the operation's result or its original exception.
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) {
                runCatching { logs.log(runId, step, LogLevel.INFO, message) }
            }
        }
    }

    companion object {
        const val LOG_STEP = "stage_timing"
        const val START_STEP = "stage_started"
    }
}
