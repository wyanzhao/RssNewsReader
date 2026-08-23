package com.dailynews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Model-side shortlist draft: writes only the short ids from the brief.
 *
 * The persisted [Part1ShortlistPayload] stays link-keyed — the same division of labor
 * as [Part1Plan]: the model writes ids, Kotlin resolves them back to authoritative
 * links, and the artifact shape is unchanged.
 */
@Serializable
data class Part1ShortlistDraft(val refs: List<String>)

@Serializable
data class Part1ShortlistPayload(val links: List<String>)

/**
 * Model-side Part 1 plan draft.
 *
 * Items reference input articles by short id (`a1`, `a2`…) instead of echoing URLs.
 * The three contract failures of 2026-08-19 were caused exactly by the model
 * re-inventing slugs from titles: an 80-character URL is something cheap models copy
 * wrong, `a7` is not. Kotlin resolves the ids back to authoritative links in
 * [com.dailynews.pipeline.editorial.EditorialRefs], and the persisted contract
 * [Part1Plan] remains link-keyed.
 */
@Serializable
data class Part1PlanDraftItem(
    val ref: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("also_refs") val alsoRefs: List<String>,
    @SerialName("event_key") val eventKey: String = "",
    @SerialName("noise_bucket") val noiseBucket: String = "selected",
)

@Serializable
data class Part1PlanDraft(
    val items: List<Part1PlanDraftItem>,
    val shortfall: Int,
    val notes: List<String> = emptyList(),
)

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

/** Part 2 batch draft: the same id-reference contract as Part 1; ids are numbered independently within each batch. */
@Serializable
data class MissingPart2DraftItem(
    val ref: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("noise_bucket") val noiseBucket: String = "covered",
    @SerialName("event_key") val eventKey: String = "",
)

@Serializable
data class MissingPart2Draft(val items: List<MissingPart2DraftItem>)

@Serializable
data class MissingPart2Summary(
    val link: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("noise_bucket") val noiseBucket: String = "covered",
    @SerialName("event_key") val eventKey: String = "",
)

@Serializable
data class MissingPart2Payload(val items: List<MissingPart2Summary>)

/** Periodic digest draft: sections reference material items by short id; Kotlin resolves them back to links. */
@Serializable
data class PeriodicDigestDraftSection(
    val heading: String,
    @SerialName("summary_zh") val summaryZh: String,
    val refs: List<String>,
    @SerialName("event_keys") val eventKeys: List<String> = emptyList(),
)

@Serializable
data class PeriodicDigestDraft(
    val period: String,
    val sections: List<PeriodicDigestDraftSection>,
    val notes: List<String> = emptyList(),
)

/**
 * One theme section of a periodic digest.
 *
 * Carries editorial fields only: `links` are references; title/source/time are joined
 * from `report_items` by link at render time. Same contract as the Part 1 plan — the
 * model does not echo fields it is not allowed to rewrite.
 */
@Serializable
data class PeriodicDigestSection(
    val heading: String,
    @SerialName("summary_zh") val summaryZh: String,
    val links: List<String>,
    @SerialName("event_keys") val eventKeys: List<String> = emptyList(),
)

@Serializable
data class PeriodicDigest(
    /** Must equal the requested periodKey byte-for-byte: the only way to catch "the model answered the wrong week". */
    val period: String,
    val sections: List<PeriodicDigestSection>,
    val notes: List<String> = emptyList(),
)

/**
 * Strict provider schemas live beside the serializable response models they constrain.
 *
 * Note that this constrains the **draft** types (`*Draft`), not the on-disk contract
 * types: the model emits `ref`, Kotlin resolves it back to `link`.
 */
object EditorialJsonSchemas {
    val part1Shortlist: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"refs":{"type":"array","items":{"type":"string"}}},"required":["refs"]}""",
    )
    val part1Plan: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"ref":{"type":"string"},"summary_zh":{"type":"string"},"also_refs":{"type":"array","items":{"type":"string"}},"event_key":{"type":"string"},"noise_bucket":{"type":"string"}},"required":["ref","summary_zh","also_refs","event_key","noise_bucket"]}},"shortfall":{"type":"integer"},"notes":{"type":"array","items":{"type":"string"}}},"required":["items","shortfall","notes"]}""",
    )
    val missingPart2: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"ref":{"type":"string"},"summary_zh":{"type":"string"},"noise_bucket":{"type":"string"},"event_key":{"type":"string"}},"required":["ref","summary_zh","noise_bucket","event_key"]}}},"required":["items"]}""",
    )

    val periodicDigest: JsonObject = schema(
        """{"type":"object","additionalProperties":false,"properties":{"period":{"type":"string"},"sections":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"heading":{"type":"string"},"summary_zh":{"type":"string"},"refs":{"type":"array","items":{"type":"string"}},"event_keys":{"type":"array","items":{"type":"string"}}},"required":["heading","summary_zh","refs","event_keys"]}},"notes":{"type":"array","items":{"type":"string"}}},"required":["period","sections","notes"]}""",
    )

    private fun schema(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
