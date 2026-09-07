package com.dailynews.pipeline.flow

import com.dailynews.model.ArtifactJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

@Serializable
internal data class EditorialCheckpoint(
    val version: Int = 1,
    val fingerprint: String,
    val payload: String,
    val payloadHash: String,
) {
    fun validate(expectedFingerprint: String): String {
        require(version == 1) { "unsupported recovery checkpoint version" }
        require(fingerprint == expectedFingerprint) { "recovery inputs, prompts or provider/model configuration changed; start a fresh run" }
        require(payloadHash == recoveryHash(payload)) { "recovery checkpoint checksum mismatch" }
        return payload
    }
}

internal fun checkpointJson(fingerprint: String, payload: String): String =
    ArtifactJson.compact.encodeToString(EditorialCheckpoint(fingerprint = fingerprint, payload = payload, payloadHash = recoveryHash(payload)))

internal fun recoveryHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Only run bookkeeping changes on recovery. Keep report date and every editorial input. */
internal fun stableRecoveryInput(json: String): String {
    val root = ArtifactJson.codec.parseToJsonElement(json)
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    val normalized = if (root is JsonObject && root["meta"] is JsonObject) {
        JsonObject(root + ("meta" to JsonObject((root.getValue("meta") as JsonObject).filterKeys {
            it !in setOf("run_id", "generated_at_utc", "report_path")
        })))
    } else root
    return canonical(normalized).toString()
}
