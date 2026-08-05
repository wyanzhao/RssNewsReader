package com.dailynews.data.repo

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.ports.EditorialCacheRecord
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StateImporter(
    private val seenLinks: SeenLinksRepository,
    private val cache: EditorialCacheRepository,
) {
    suspend fun importSeenLinks(input: InputStream): Int {
        val root = ArtifactJson.codec.parseToJsonElement(input.bufferedReader().use { it.readText() }).jsonObject
        val entries = root["entries"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
            runCatching { key to LocalDate.parse(value.jsonPrimitive.content) }.getOrNull()
        }.toMap()
        // Keep the later date on conflict, matching SeenLinks.recordReportedLinks
        // and seen_links.py. Rolling an entry backwards would break same-day
        // rerun idempotence and let the 14-day prune expire it early.
        seenLinks.mergeLatest(entries)
        return entries.size
    }

    suspend fun importEditorialCache(input: InputStream): Int {
        val root = ArtifactJson.codec.parseToJsonElement(input.bufferedReader().use { it.readText() }).jsonObject
        val entries = root["entries"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
            val item = value as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val link = item.text("link")
            if (link.isBlank()) return@mapNotNull null
            EditorialCacheRecord(
                cacheKey = key,
                link = link,
                source = item.text("source"),
                title = item.text("title"),
                summaryZh = item.text("summary_zh").ifBlank { null },
                part1SummaryZh = item.text("part1_summary_zh").ifBlank { null },
                noiseBucket = item.text("noise_bucket").ifBlank { null },
                part1NoiseBucket = item.text("part1_noise_bucket").ifBlank { null },
                eventKey = item.text("event_key").ifBlank { null },
                updatedAtUtc = item.text("updated_at_utc").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(normalizeInstant(it)) }.getOrNull() },
            )
        }
        cache.upsert(entries)
        return entries.size
    }

    private fun kotlinx.serialization.json.JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun normalizeInstant(value: String): String = if (value.endsWith("+00:00")) value.dropLast(6) + "Z" else value
}
