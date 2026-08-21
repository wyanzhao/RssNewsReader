package com.dailynews.pipeline.flow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 周期简报的输入素材：一段时间内**已成功发布**的每日 Top N 条目。
 *
 * 摘要是上游已经定稿并通过 lint 的中文文本，所以这里不再带 article_text 或
 * summary_en——周报是对编辑判断的二次编辑，不是重新读一遍原文。
 */
@Serializable
data class PeriodicDigestItem(
    @SerialName("report_date") val reportDate: String,
    val title: String,
    val source: String,
    val link: String,
    @SerialName("summary_zh") val summaryZh: String,
    @SerialName("event_key") val eventKey: String,
    /**
     * 短引用 id（`a1`、`a2`…）。段落只写这个，不回显 link。
     *
     * 采集端（`collectInput`）不需要关心编号，所以这里有默认值并排在最后：
     * `LlmEditorialEngine.digest` 在发出请求之前统一盖上 id，负载与解析索引出自
     * 同一次赋值，不可能失步。未盖 id 的输入会在引用解析处 fail closed，不会静默出错。
     */
    val id: String = "",
)

@Serializable
data class PeriodicDigestInput(
    /** `2026-W32` 或 `2026-08`。模型必须原样回填到输出的 `period`。 */
    val period: String,
    val kind: String,
    @SerialName("period_start_date") val periodStartDate: String,
    @SerialName("period_end_date") val periodEndDate: String,
    @SerialName("report_dates") val reportDates: List<String>,
    val items: List<PeriodicDigestItem>,
)
