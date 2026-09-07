package com.dailynews.data.repo

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.files.ArtifactStore
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.pipeline.ports.RunRecoveryPort
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RunRecoveryRepository(
    private val database: DailyNewsDatabase,
    private val artifacts: ArtifactStore,
    private val runs: RunRepository,
    private val feeds: com.dailynews.pipeline.ports.FeedSource,
) : RunRecoveryPort {
    override suspend fun recover(sourceRunId: String, date: LocalDate, config: PipelineConfig): RawRun = try {
        recoverSnapshot(sourceRunId, date, config)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val failed = runs.recordPreflightFailure(date, "recovery", "recovery", error.message ?: "recovery snapshot rejected", 1)
        throw com.dailynews.pipeline.ports.RecoveryRejectedException(requireNotNull(failed.runId), error)
    }

    private suspend fun recoverSnapshot(sourceRunId: String, date: LocalDate, config: PipelineConfig): RawRun {
        val previous = requireNotNull(database.runs().get(sourceRunId)) { "recovery run was removed" }
        require(previous.status == "FAILED") { "only failed or interrupted runs can be recovered" }
        require(previous.reportDate == date.toString()) { "recovery date does not match source run" }
        val savedConfig = ArtifactJson.codec.decodeFromString<PipelineConfig>(
            requireNotNull(artifacts.readText(sourceRunId, "run_config.json")) { "this run has no configuration snapshot; start a fresh run" },
        )
        require(savedConfig.normalized() == config.normalized()) { "pipeline configuration changed; start a fresh run" }
        val savedFeeds = ArtifactJson.codec.decodeFromString<List<com.dailynews.model.FeedDefinition>>(
            requireNotNull(artifacts.readText(sourceRunId, "run_feeds.json")) { "recovery feed snapshot is missing" },
        )
        require(savedFeeds == feeds.enabledFeeds()) { "feed configuration changed; start a fresh run" }
        val raw = ArtifactJson.codec.decodeFromString<RawRun>(
            requireNotNull(artifacts.readText(sourceRunId, "raw.json")) { "recovery input snapshot is missing" },
        )
        require(raw.meta.runId == sourceRunId) { "recovery snapshot belongs to another run" }
        val recovered = raw.copy(meta = raw.meta.copy(
            runId = "recovery-${UUID.randomUUID()}", generatedAtUtc = Instant.now().toString(),
        ))
        runs.started(recovered, previous.reportDate, previous.attempt + 1, "recovery")
        return recovered
    }
}
