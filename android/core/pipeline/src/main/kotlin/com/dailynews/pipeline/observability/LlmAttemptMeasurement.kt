package com.dailynews.pipeline.observability

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.RunLogSink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** One physical request, including failed and repair requests. No prompt or response text. */
@Serializable
data class LlmAttemptMeasurement(
    val attemptId: String,
    val operation: String,
    val batch: String? = null,
    val contractAttempt: Int,
    val physicalAttempt: Int,
    val outcome: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val billedCostUsd: String? = null,
) {
    companion object { const val LOG_STEP = "llm_attempt_measurement" }
}

suspend fun RunLogSink.recordMeasurement(runId: String, measurement: LlmAttemptMeasurement) {
    withContext(NonCancellable) {
        withTimeoutOrNull(2_000) {
            runCatching {
                log(runId, LlmAttemptMeasurement.LOG_STEP, LogLevel.INFO, ArtifactJson.compact.encodeToString(measurement))
            }
        }
    }
}
