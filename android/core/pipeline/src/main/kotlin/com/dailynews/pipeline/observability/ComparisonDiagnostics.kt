package com.dailynews.pipeline.observability

import com.dailynews.model.ArtifactJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ComparisonTruncation(val arm: String, val operation: String, val outputTokens: Long?)

data class ComparisonDiagnostics(
    val status: String,
    val preference: String,
    val truncations: List<ComparisonTruncation> = emptyList(),
    val unreadableTelemetry: Boolean = false,
) {
    companion object {
        /** Only typed measurement fields are exposed; never render raw provider error text. */
        fun parse(manifestText: String, telemetry: List<Pair<String, String?>>): ComparisonDiagnostics {
            val manifest = ArtifactJson.codec.decodeFromString<JsonObject>(manifestText)
            fun field(name: String) = (manifest[name] as? JsonPrimitive)?.content.orEmpty()
            var unreadable = false
            val truncated = telemetry.mapNotNull { (arm, text) ->
                try {
                    require(arm in setOf("baseline", "candidate"))
                    val row = ArtifactJson.codec.decodeFromString<JsonObject>(requireNotNull(text))
                    if ((row["step"] as? JsonPrimitive)?.content != LlmAttemptMeasurement.LOG_STEP) return@mapNotNull null
                    val measurement = ArtifactJson.codec.decodeFromString<LlmAttemptMeasurement>(
                        requireNotNull((row["message"] as? JsonPrimitive)?.content),
                    )
                    require(measurement.outputTokens == null || measurement.outputTokens >= 0)
                    if (measurement.outcome in setOf("truncated", "repair_truncated")) {
                        ComparisonTruncation(arm, measurement.operation, measurement.outputTokens)
                    } else null
                } catch (_: Exception) {
                    unreadable = true
                    null
                }
            }.distinct()
            return ComparisonDiagnostics(field("status").ifBlank { "unknown" }, field("preference"), truncated, unreadable)
        }
    }
}
