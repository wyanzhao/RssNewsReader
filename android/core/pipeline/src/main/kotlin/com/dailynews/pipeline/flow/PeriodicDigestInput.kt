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
