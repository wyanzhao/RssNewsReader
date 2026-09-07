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
import kotlinx.coroutines.flow.first

class RunRepository(private val database: DailyNewsDatabase) {
    suspend fun get(runId: String) = database.runs().get(runId)
    suspend fun recoveryAccounting(runId: String, artifacts: com.dailynews.data.files.ArtifactStore) =
        com.dailynews.pipeline.observability.recoveryAccounting(runId) load@{ id ->
            val row = database.runs().get(id) ?: return@load null
            val issues = mutableListOf<String>()
            val provenance = artifacts.readText(id, "recovery.json")
            val parent = if (provenance == null) {
                if (row.trigger == "recovery") issues += "missing_provenance"
                null
            } else {
                val decoded = runCatching {
                    val obj = ArtifactJson.codec.decodeFromString<kotlinx.serialization.json.JsonObject>(provenance)
                    require((obj["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "frozen_input")
                    requireNotNull((obj["source_run_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content).also { require(it.isNotBlank()) }
                }
                if (decoded.isFailure) issues += "invalid_provenance"
                decoded.getOrNull()
            }
            val logs = database.runLogs().observe(id).first()
            val measurements = logs.filter { it.step == com.dailynews.pipeline.observability.LlmAttemptMeasurement.LOG_STEP }.mapNotNull {
                runCatching { ArtifactJson.codec.decodeFromString<com.dailynews.pipeline.observability.LlmAttemptMeasurement>(it.message) }
                    .getOrElse { issues += "invalid_measurement"; null }
            }
            val starts = logs.count { it.step == "stage_started" && it.message.startsWith("llm.") }
            val finishes = logs.count { log ->
                log.step == "stage_timing" && runCatching {
                    val obj = ArtifactJson.codec.decodeFromString<kotlinx.serialization.json.JsonObject>(log.message)
                    (obj["stage"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith("llm.") == true
                }.getOrDefault(false)
            }
            com.dailynews.pipeline.observability.AccountingRun(id, row.reportDate, row.status, parent,
                database.llmCalls().count(id), measurements, issues,
                mayHaveUnrecordedCalls = starts > finishes || (starts > 0 && measurements.isEmpty()) || row.classification.contains("INTERRUPT", ignoreCase = true) ||
                    (row.status == "FAILED" && logs.any { it.message.contains("cancel", ignoreCase = true) }))
        }

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

    /**
     * A scheduled trigger that never got a usable network. The pipeline did not run, so
     * this is not a failure of it: recording UNEXPECTED_ERROR / exit 40 here would put a
     * red "run failure" row in the history for what is really "the OS held us back". Written
     * as its own terminal state instead, so the diagnostics advisor and the history filter
     * can tell a deferral apart from a run that started and broke.
     *
     * Deliberately separate from [recordPreflightFailure], which still backs the
     * [failRunning] fallback — a watchdog or a WorkManager stop *is* a real failure.
     *
     * @return the synthetic run id, for attaching run logs to.
     */
    suspend fun recordPreflightDeferral(
        reportDate: LocalDate,
        trigger: String,
        stage: String,
        message: String,
        attempt: Int,
    ): String {
        val now = Instant.now()
        val runId = "preflight-${now.epochSecond}-${UUID.randomUUID().toString().take(8)}"
        database.runs().upsert(
            RunEntity(
                runId = runId,
                reportDate = reportDate.toString(),
                status = STATUS_SKIPPED,
                classification = CLASSIFICATION_DEFERRED,
                // The validator never ran; 40 would claim a pipeline crash that never happened.
                validatorExitCode = 0,
                attempt = attempt,
                trigger = trigger,
                blockingReasonsJson = ArtifactJson.compact.encodeToString(listOf("$stage: $message")),
                startedAtUtc = now.toString(),
                finishedAtUtc = now.toString(),
            ),
        )
        return runId
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

    companion object {
        /** Terminal run state for a trigger that never started the pipeline. */
        const val STATUS_SKIPPED = "SKIPPED"
        const val CLASSIFICATION_DEFERRED = "DEFERRED"
    }
}
