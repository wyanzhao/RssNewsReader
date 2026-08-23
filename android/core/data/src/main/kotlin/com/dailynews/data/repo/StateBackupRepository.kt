package com.dailynews.data.repo

import androidx.room.withTransaction
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.DAILYNEWS_SCHEMA_VERSION
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.EditorialCacheEntity
import com.dailynews.data.db.FeedEntity
import com.dailynews.data.db.FetchLogEntity
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.LlmUsageMonthEntity
import com.dailynews.data.db.ReportEntity
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.db.RunEntity
import com.dailynews.data.db.RunArtifactEntity
import com.dailynews.data.db.RunArtifactMetadata
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.PeriodicReportEntity
import com.dailynews.data.db.SeenLinkEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PipelineConfig
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class DeviceStateBackup(
    val schemaVersion: Int = 3,
    /** Default comes from the single source of truth for the schema, so the next Room version change will not leave it behind again. */
    val databaseVersion: Int = DAILYNEWS_SCHEMA_VERSION,
    val exportedAtUtc: String,
    val pipelineConfig: PipelineConfig,
    val feeds: List<FeedEntity>,
    val articles: List<ArticleEntity>,
    val fetchLog: List<FetchLogEntity>,
    val runArtifacts: List<RunArtifactEntity> = emptyList(),
    val artifactManifest: List<StateArtifactEntry> = emptyList(),
    val runs: List<RunEntity>,
    val runLogs: List<RunLogEntity>,
    val llmCalls: List<LlmCallEntity>,
    val llmUsageMonthly: List<LlmUsageMonthEntity>,
    val reports: List<ReportEntity>,
    val reportItems: List<ReportItemEntity>,
    val editorialCache: List<EditorialCacheEntity>,
    val seenLinks: List<SeenLinkEntity>,
    val periodicReports: List<PeriodicReportEntity> = emptyList(),
)

@Serializable
data class StateArtifactEntry(
    val runId: String,
    val name: String,
    val entryName: String,
    val createdAtUtc: String,
)

data class StateBackupSummary(
    val articles: Int,
    val reports: Int,
    val favorites: Int,
)

class StateBackupRepository(
    private val database: DailyNewsDatabase,
    private val config: PipelineConfigRepository,
) {
    suspend fun exportZip(destination: OutputStream): StateBackupSummary {
        val pipelineConfig = config.config.first()
        val (backup, artifactMetadata) = database.withTransaction {
            val metadata = database.runArtifacts().metadata()
            val manifest = metadata.mapIndexed { index, item -> item.toStateEntry(index) }
            DeviceStateBackup(
                databaseVersion = DAILYNEWS_SCHEMA_VERSION,
                exportedAtUtc = Instant.now().toString(),
                pipelineConfig = pipelineConfig,
                feeds = database.feeds().allNow(),
                articles = database.articles().allNow(),
                fetchLog = database.fetchLogs().allNow(),
                runArtifacts = emptyList(),
                artifactManifest = manifest,
                runs = database.runs().allNow(),
                runLogs = database.runLogs().allNow(),
                llmCalls = database.llmCalls().allNow(),
                llmUsageMonthly = database.llmUsageMonths().allNow(),
                reports = database.reports().allNow(),
                reportItems = database.reports().allItemsNow(),
                editorialCache = database.editorialCache().allNow(),
                seenLinks = database.seenLinks().all(),
                periodicReports = database.periodicReports().allNow(),
            ) to metadata
        }
        backup.validate()
        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NAME).apply { time = 0L })
            zip.write(ArtifactJson.compact.encodeToString(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            backup.artifactManifest.zip(artifactMetadata).forEach { (manifest, metadata) ->
                zip.putNextEntry(ZipEntry(manifest.entryName).apply { time = 0L })
                streamArtifact(zip, metadata)
                zip.closeEntry()
            }
        }
        return backup.summary()
    }

    suspend fun importZip(payload: ByteArray): StateBackupSummary {
        require(payload.size <= MAX_ZIP_BYTES) { "state backup exceeds ${MAX_ZIP_BYTES / 1_048_576} MiB" }
        val (json, artifactBodies) = ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            var state: ByteArray? = null
            val bodies = linkedMapOf<String, ByteArray>()
            var totalBytes = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.name.startsWith('/') && ".." !in entry.name.split('/')) { "unsafe zip entry" }
                if (entry.name == ENTRY_NAME) {
                    require(state == null) { "duplicate $ENTRY_NAME" }
                    state = zip.readBounded(MAX_STATE_JSON_BYTES)
                    totalBytes += state.size
                } else if (entry.name.startsWith(ARTIFACT_PREFIX)) {
                    require(entry.name !in bodies) { "duplicate ${entry.name}" }
                    val body = zip.readBounded(MAX_ARTIFACT_BYTES)
                    bodies[entry.name] = body
                    totalBytes += body.size
                }
                require(totalBytes <= MAX_STATE_BYTES) { "expanded state backup exceeds ${MAX_STATE_BYTES / 1_048_576} MiB" }
                zip.closeEntry()
            }
            requireNotNull(state) { "$ENTRY_NAME is missing" } to bodies
        }
        val backup = ArtifactJson.codec.decodeFromString<DeviceStateBackup>(json.toString(Charsets.UTF_8))
        require(backup.schemaVersion in 1..3) { "unsupported state backup schema ${backup.schemaVersion}" }
        // databaseVersion was previously written but never read. A backup from a higher
        // version may contain tables this version cannot represent; importing it silently
        // would quietly lose data, so an explicit refusal is preferred.
        require(backup.databaseVersion <= CURRENT_DATABASE_VERSION) {
            "state backup was exported from database v${backup.databaseVersion}, this build only understands v$CURRENT_DATABASE_VERSION"
        }
        backup.validate()
        val artifacts = when (backup.schemaVersion) {
            1 -> backup.runArtifacts
            else -> {
                val expectedEntries = backup.artifactManifest.mapTo(linkedSetOf(), StateArtifactEntry::entryName)
                require(artifactBodies.keys == expectedEntries) { "artifact entries do not match state manifest" }
                backup.artifactManifest.map { item ->
                    RunArtifactEntity(item.runId, item.name, artifactBodies.getValue(item.entryName), item.createdAtUtc)
                }
            }
        }
        val priorConfig = config.config.first()
        config.save(backup.pipelineConfig.normalized())
        try {
            database.withTransaction {
            database.reports().clearAllItems()
            database.reports().clearReports()
            database.runLogs().clear()
            database.llmCalls().clear()
            database.runs().clear()
            database.llmUsageMonths().clear()
            database.fetchLogs().clear()
            database.runArtifacts().clear()
            database.articles().clear()
            database.feeds().clear()
            database.editorialCache().clear()
            database.seenLinks().clear()
            database.periodicReports().clear()

            database.feeds().replaceAll(backup.feeds)
            database.articles().replaceAll(backup.articles)
            database.fetchLogs().replaceAll(backup.fetchLog)
            database.runArtifacts().replaceAll(artifacts)
            database.runs().replaceAll(backup.runs)
            database.runLogs().replaceAll(backup.runLogs)
            database.llmCalls().replaceAll(backup.llmCalls)
            database.llmUsageMonths().replaceAll(backup.llmUsageMonthly)
            database.reports().replaceReports(backup.reports)
            database.reports().insertItems(backup.reportItems)
            database.editorialCache().upsert(backup.editorialCache)
            database.seenLinks().insertAll(backup.seenLinks)
            backup.periodicReports.forEach { database.periodicReports().upsert(it) }
            }
        } catch (error: Exception) {
            runCatching { config.save(priorConfig) }
            throw error
        }
        return backup.summary()
    }

    private suspend fun streamArtifact(zip: ZipOutputStream, metadata: RunArtifactMetadata) {
        val size = requireNotNull(database.runArtifacts().bodySize(metadata.runId, metadata.name)) {
            "artifact disappeared during export: ${metadata.runId}/${metadata.name}"
        }
        var offset = 1
        var remaining = size
        while (remaining > 0) {
            val requested = minOf(ARTIFACT_CHUNK_BYTES, remaining)
            val chunk = requireNotNull(database.runArtifacts().bodyChunk(metadata.runId, metadata.name, offset, requested)) {
                "artifact disappeared during export: ${metadata.runId}/${metadata.name}"
            }
            require(chunk.isNotEmpty()) { "artifact truncated during export: ${metadata.runId}/${metadata.name}" }
            zip.write(chunk)
            offset += chunk.size
            remaining -= chunk.size
        }
    }

    private fun DeviceStateBackup.summary() = StateBackupSummary(
        articles = articles.size,
        reports = reports.size,
        favorites = articles.count { it.favoritedAtUtc != null },
    )

    private fun ZipInputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "zip entry exceeds ${maxBytes / 1_048_576} MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CURRENT_DATABASE_VERSION = DAILYNEWS_SCHEMA_VERSION
        const val ENTRY_NAME = "dailynews-state.json"
        const val ARTIFACT_PREFIX = "run-artifacts/"
        const val MAX_ZIP_BYTES = 64 * 1_048_576
        const val MAX_STATE_BYTES = 128 * 1_048_576
        const val MAX_STATE_JSON_BYTES = 128 * 1_048_576
        const val MAX_ARTIFACT_BYTES = 64 * 1_048_576
        const val ARTIFACT_CHUNK_BYTES = 512 * 1_024
    }
}

private fun RunArtifactMetadata.toStateEntry(index: Int) = StateArtifactEntry(
    runId = runId,
    name = name,
    entryName = "run-artifacts/${index.toString().padStart(8, '0')}.gz",
    createdAtUtc = createdAtUtc,
)

private fun DeviceStateBackup.validate() {
    seenLinks.forEachIndexed { index, entry ->
        require(entry.linkKey.isNotBlank()) { "seen_links[$index] has a blank linkKey" }
        require(runCatching { java.time.LocalDate.parse(entry.firstSeenDate) }.isSuccess) {
            "seen_links[$index] has invalid firstSeenDate: ${entry.firstSeenDate}"
        }
    }
    artifactManifest.forEachIndexed { index, entry ->
        require(entry.runId.isNotBlank() && entry.name.isNotBlank()) { "artifact_manifest[$index] has blank identity" }
        require(entry.entryName.startsWith("run-artifacts/") && ".." !in entry.entryName) {
            "artifact_manifest[$index] has unsafe entry name"
        }
        require(runCatching { Instant.parse(entry.createdAtUtc) }.isSuccess) {
            "artifact_manifest[$index] has invalid createdAtUtc"
        }
    }
    require(artifactManifest.map(StateArtifactEntry::entryName).distinct().size == artifactManifest.size) {
        "artifact manifest contains duplicate entries"
    }
}
