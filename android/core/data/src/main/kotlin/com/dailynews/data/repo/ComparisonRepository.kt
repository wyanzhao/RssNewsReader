package com.dailynews.data.repo

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.files.ArtifactStore
import com.dailynews.model.*
import com.dailynews.pipeline.context.Part1ShortlistContext
import com.dailynews.pipeline.ports.ArtifactSink
import kotlinx.serialization.json.JsonObject

class ComparisonRepository(private val database: DailyNewsDatabase, private val store: ArtifactStore) {
    data class Snapshot(val raw: RawRun, val feeds: FeedConfigDocument, val config: PipelineConfig,
                        val date: String, val history: Part1ShortlistContext)
    suspend fun snapshot(source: String): Snapshot {
        val row = requireNotNull(database.runs().get(source)) { "source run was removed" }
        require(row.status != "RUNNING") { "source run is still active" }
        suspend fun text(name: String) = requireNotNull(store.readText(source, name)) { "missing source artifact: $name" }
        val raw = ArtifactJson.codec.decodeFromString<RawRun>(text("raw.json"))
        require(raw.meta.runId == source) { "source snapshot identity mismatch" }
        return Snapshot(raw, FeedConfigDocument(feeds = ArtifactJson.codec.decodeFromString<List<FeedDefinition>>(text("run_feeds.json"))),
            ArtifactJson.codec.decodeFromString(text("run_config.json")), row.reportDate,
            ArtifactJson.codec.decodeFromString(text("part1_shortlist_context.json")))
    }
    private fun root(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        return "comparisons/$id"
    }
    suspend fun manifest(source: String, id: String): JsonObject? = store.readText(source, "${root(id)}/manifest.json")
        ?.let { ArtifactJson.codec.decodeFromString<JsonObject>(it) }
    suspend fun manifest(source: String, id: String, value: JsonObject) {
        store.write(source, "${root(id)}/manifest.json", value.toString().toByteArray())
    }
    fun artifacts(source: String, id: String): ArtifactSink {
        val prefix = root(id)
        return object : ArtifactSink {
            override suspend fun write(runId: String, relativePath: String, content: ByteArray) {
                val arm = runId.removePrefix("$id-")
                require(runId == "$id-$arm" && arm in setOf("baseline", "candidate")) { "unexpected comparison arm" }
                store.write(source, "$prefix/$arm/$relativePath", content)
            }
        }
    }
}
