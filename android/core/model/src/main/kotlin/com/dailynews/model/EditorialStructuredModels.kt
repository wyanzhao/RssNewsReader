package com.dailynews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class Part1ShortlistPayload(val links: List<String>)

@Serializable
data class Part1PlanItem(
    val link: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("also_links") val alsoLinks: List<String>,
    @SerialName("event_key") val eventKey: String = "",
    @SerialName("noise_bucket") val noiseBucket: String = "selected",
)

@Serializable
data class Part1Plan(
    val items: List<Part1PlanItem>,
    val shortfall: Int,
    val notes: List<String> = emptyList(),
)

@Serializable
data class MissingPart2Summary(
    val link: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("noise_bucket") val noiseBucket: String = "covered",
    @SerialName("event_key") val eventKey: String = "",
)

@Serializable
data class MissingPart2Payload(val items: List<MissingPart2Summary>)

/** Strict provider schemas live beside the serializable response models they constrain. */
object EditorialJsonSchemas {
    val part1Shortlist: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"links":{"type":"array","items":{"type":"string"}}},"required":["links"]}""",
    )
    val part1Plan: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"link":{"type":"string"},"summary_zh":{"type":"string"},"also_links":{"type":"array","items":{"type":"string"}},"event_key":{"type":"string"},"noise_bucket":{"type":"string"}},"required":["link","summary_zh","also_links","event_key","noise_bucket"]}},"shortfall":{"type":"integer"},"notes":{"type":"array","items":{"type":"string"}}},"required":["items","shortfall","notes"]}""",
    )
    val missingPart2: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"link":{"type":"string"},"summary_zh":{"type":"string"},"noise_bucket":{"type":"string"},"event_key":{"type":"string"}},"required":["link","summary_zh","noise_bucket","event_key"]}}},"required":["items"]}""",
    )

    private fun schema(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
