package com.dailynews.data.repo

import androidx.room.withTransaction
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.LlmUsageMonthEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.RunLogSink
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class RunLogRepository(private val database: DailyNewsDatabase) : RunLogSink {
    override suspend fun log(runId: String, step: String, level: LogLevel, message: String) {
        database.runLogs().insert(RunLogEntity(runId = runId, step = step, level = level.name, message = message, createdAtUtc = Instant.now().toString()))
    }

    fun observe(runId: String) = database.runLogs().observe(runId)
}

class LlmCallRepository(private val database: DailyNewsDatabase) {
    suspend fun record(
        runId: String,
        role: String,
        provider: String,
        model: String,
        inputTokens: Long?,
        outputTokens: Long?,
        retryIndex: Int,
        outcome: String,
    ) {
        database.llmCalls().insert(
            LlmCallEntity(
                runId = runId,
                role = role,
                provider = provider,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                retryIndex = retryIndex,
                outcome = outcome,
                createdAtUtc = Instant.now().toString(),
            ),
        )
    }

    fun observe(runId: String) = database.llmCalls().observe(runId)

    suspend fun tokensThisMonth(now: Instant = Instant.now()): Long {
        val start = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        return database.llmCalls().tokensSince(start.toString())
    }
}

data class RetentionResult(
    val runArtifactsDeleted: Int,
    val runLogsDeleted: Int,
    val runsDeleted: Int,
    val llmCallsRolledUp: Int,
)

class RunMaintenanceRepository(private val database: DailyNewsDatabase) {
    suspend fun prune(retentionDays: Int, now: Instant = Instant.now()): RetentionResult = database.withTransaction {
        val runCutoff = now.minus(retentionDays.coerceAtLeast(1).toLong(), ChronoUnit.DAYS).toString()
        val monthStart = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toString()
        val rollups = database.llmCalls().rollupsBefore(monthStart)
        rollups.forEach { rollup ->
            val previous = database.llmUsageMonths().get(rollup.month)
            database.llmUsageMonths().upsert(
                LlmUsageMonthEntity(
                    month = rollup.month,
                    inputTokens = (previous?.inputTokens ?: 0) + rollup.inputTokens,
                    outputTokens = (previous?.outputTokens ?: 0) + rollup.outputTokens,
                    callCount = (previous?.callCount ?: 0) + rollup.callCount,
                ),
            )
        }
        val oldCalls = database.llmCalls().deleteBefore(monthStart)
        val oldArtifacts = database.runArtifacts().deleteBefore(runCutoff)
        val oldLogs = database.runLogs().deleteBefore(runCutoff)
        val oldRuns = database.runs().deleteFinishedBefore(runCutoff)
        RetentionResult(oldArtifacts, oldLogs, oldRuns, oldCalls)
    }
}
