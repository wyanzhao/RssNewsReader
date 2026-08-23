package com.dailynews.pipeline.flow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input material for the periodic digest: the daily Top N items that were **successfully published** within a period.
 *
 * The summaries are Chinese text already finalized and lint-passed upstream, so no article_text or summary_en is
 * carried here — the weekly report is a second-pass edit of editorial judgment, not a re-read of the originals.
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
     * Short reference id (`a1`, `a2`, …). Sections write only this and never echo the link back.
     *
     * The collection side (`collectInput`) does not need to care about numbering, so this has a default value and is
     * placed last: `LlmEditorialEngine.digest` stamps the ids uniformly before the request goes out, and the payload
     * and the resolution index come from one assignment, so they cannot drift apart. Input without stamped ids fails
     * closed at reference resolution instead of erroring silently.
     */
    val id: String = "",
)

@Serializable
data class PeriodicDigestInput(
    /** `2026-W32` or `2026-08`. The model must echo it back verbatim into the output's `period`. */
    val period: String,
    val kind: String,
    @SerialName("period_start_date") val periodStartDate: String,
    @SerialName("period_end_date") val periodEndDate: String,
    @SerialName("report_dates") val reportDates: List<String>,
    val items: List<PeriodicDigestItem>,
)
