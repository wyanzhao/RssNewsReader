package com.dailynews.data.repo

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.RunEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.RawRun
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.encodeToString

class RunRepository(private val database: DailyNewsDatabase) {
    fun observeRecent(limit: Int = 50) = database.runs().observeRecent(limit.coerceIn(1, 200))
    fun observeDetail(runId: String) = database.runs().observeDetail(runId)
    suspend fun started(raw: RawRun, reportDate: String, attempt: Int, trigger: String = "unknown") {
        database.runs().upsert(
            RunEntity(
                runId = raw.meta.runId,
                reportDate = reportDate,
                status = "RUNNING",
                classification = "PENDING",
                validatorExitCode = 40,
                attempt = attempt,
                trigger = trigger,
                startedAtUtc = raw.meta.generatedAtUtc,
            ),
        )
    }

    suspend fun finished(result: RunExecutionResult) {
        val runId = result.runId ?: return
        val previous = database.runs().get(runId) ?: return
        val finished = Instant.now().toString()
        val updated = when (result) {
            is RunExecutionResult.Success -> previous.copy(
                status = "SUCCESS",
                classification = "SUCCESS",
                validatorExitCode = 0,
                warningsJson = ArtifactJson.compact.encodeToString(result.warnings),
                finishedAtUtc = finished,
            )
            is RunExecutionResult.ExpectedBlock -> previous.copy(
                status = "FAILED",
                classification = "EXPECTED_BLOCK",
                validatorExitCode = result.validatorExitCode,
                blockingReasonsJson = ArtifactJson.compact.encodeToString(result.validation.blockingReasons),
                warningsJson = ArtifactJson.compact.encodeToString(result.validation.warnings),
                countsJson = ArtifactJson.compact.encodeToString(result.validation.counts),
                finishedAtUtc = finished,
            )
            is RunExecutionResult.Failed -> previous.copy(
                status = "FAILED",
                classification = "UNEXPECTED_ERROR",
                validatorExitCode = 40,
                blockingReasonsJson = ArtifactJson.compact.encodeToString(listOf("${result.stage}: ${result.message}")),
                finishedAtUtc = finished,
            )
        }
        database.runs().upsert(updated)
    }

    suspend fun recoverInterruptedRuns() {
        database.runs().markRunningInterrupted(Instant.now().toString())
    }

    suspend fun recordPreflightFailure(
        reportDate: LocalDate,
        trigger: String,
        stage: String,
        message: String,
        attempt: Int,
    ): RunExecutionResult.Failed {
        val now = Instant.now()
        val runId = "preflight-${now.epochSecond}-${UUID.randomUUID().toString().take(8)}"
        database.runs().upsert(
            RunEntity(
                runId = runId,
                reportDate = reportDate.toString(),
                status = "FAILED",
                classification = "UNEXPECTED_ERROR",
                validatorExitCode = 40,
                attempt = attempt,
                trigger = trigger,
                blockingReasonsJson = ArtifactJson.compact.encodeToString(listOf("$stage: $message")),
                startedAtUtc = now.toString(),
                finishedAtUtc = now.toString(),
            ),
        )
        return RunExecutionResult.Failed(reportDate, runId, stage, message)
    }

    suspend fun failRunning(reportDate: LocalDate, stage: String, message: String): RunExecutionResult.Failed {
        val running = database.runs().latestRunning(reportDate.toString())
            ?: return recordPreflightFailure(reportDate, "unknown", stage, message, 1)
        val result = RunExecutionResult.Failed(reportDate, running.runId, stage, message)
        finished(result)
        return result
    }
}
