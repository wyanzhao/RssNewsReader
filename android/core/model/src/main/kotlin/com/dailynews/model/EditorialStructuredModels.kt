package com.dailynews.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 模型侧的短名单草稿：只写 brief 里的短 id。
 *
 * 落盘的 [Part1ShortlistPayload] 仍是 link-keyed——与 [Part1Plan] 同一条分工：
 * 模型写 id，Kotlin 解析回权威 link，产物形状不变。
 */
@Serializable
data class Part1ShortlistDraft(val refs: List<String>)

@Serializable
data class Part1ShortlistPayload(val links: List<String>)

/**
 * 模型侧的 Part 1 计划草稿。
 *
 * 条目用短 id（`a1`、`a2`…）引用输入文章，而不是回显 URL。2026-08-19 的三轮契约
 * 失败正是模型按标题重造 slug 造成的：80 字符的 URL 是便宜模型抄不对的东西，`a7`
 * 不是。Kotlin 在 [com.dailynews.pipeline.editorial.EditorialRefs] 里把 id 解析回权威
 * link，落盘契约 [Part1Plan] 仍然是 link-keyed。
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

/** Part 2 批次草稿：与 Part 1 同一条 id 引用契约，id 在每个批次内独立编号。 */
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

/** 周期简报草稿：段落用短 id 引用素材条目，Kotlin 解析回 link。 */
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
 * 周期简报的一个主题段落。
 *
 * 只承载编辑字段：`links` 是引用，标题/来源/时间在渲染时由 Kotlin 从 `report_items`
 * 按 link 连接权威值。与 Part 1 plan 同一条契约——模型不回显它无权改写的字段。
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
    /** 必须逐字等于请求的 periodKey：这是抓「模型答错了周」的唯一手段。 */
    val period: String,
    val sections: List<PeriodicDigestSection>,
    val notes: List<String> = emptyList(),
)

/**
 * Strict provider schemas live beside the serializable response models they constrain.
 *
 * 注意这里约束的是**草稿**类型（`*Draft`），不是落盘契约类型：模型输出 `ref`，
 * Kotlin 解析回 `link`。
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
