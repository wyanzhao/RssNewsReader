package com.dailynews.pipeline.ports

import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import java.time.LocalDate

/** Explicit recovery only: missing/corrupt/drifted input fails, never switches to a fresh fetch. */
fun interface RunRecoveryPort {
    suspend fun recover(sourceRunId: String, date: LocalDate, config: PipelineConfig): RawRun
}

interface EditorialCheckpointStore {
    suspend fun read(runId: String, stage: String): String?
    suspend fun write(runId: String, stage: String, content: String)
}

class RecoveryRejectedException(val failureRunId: String, cause: Throwable) : IllegalStateException(cause.message, cause)
