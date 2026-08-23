package com.dailynews.pipeline.editorial

import com.dailynews.model.PeriodicDigest
import com.dailynews.pipeline.flow.PeriodicDigestInput
import com.dailynews.pipeline.flow.PeriodicDigestItem
import com.dailynews.pipeline.text.TextUtils

/**
 * Join [PeriodicDigest] with authoritative material into markdown.
 *
 * The model only hands over heading / summary_zh / links; title, source, and date
 * are always looked up from the input material by link — same contract as
 * ReportAssembler; the model does not echo fields it is not allowed to rewrite.
 */
object PeriodicDigestRenderer {
    fun render(input: PeriodicDigestInput, digest: PeriodicDigest): String {
        val byLink = input.items.associateBy { TextUtils.cleanText(it.link) }
        return buildString {
            appendLine("# DailyNews ${if (input.kind == "WEEKLY") "周报" else "月报"} · ${input.period}")
            appendLine()
            appendLine("> ${input.periodStartDate} 至 ${input.periodEndDate} · 覆盖 ${input.reportDates.size} 份日报 · ${input.items.size} 条入选报道")
            appendLine()
            digest.sections.forEachIndexed { index, section ->
                appendLine("## ${index + 1}. ${TextUtils.cleanText(section.heading)}")
                appendLine()
                appendLine(TextUtils.cleanText(section.summaryZh))
                appendLine()
                section.links.forEach { raw ->
                    val item = byLink[TextUtils.cleanText(raw)] ?: return@forEach
                    appendLine("- ${item.reportDate} · [${escapeTitle(item.title)}](${encodeLink(item.link)}) — ${item.source}")
                }
                appendLine()
            }
            if (digest.notes.isNotEmpty()) {
                appendLine("## 编辑说明")
                appendLine()
                digest.notes.forEach { note -> appendLine("- ${TextUtils.cleanText(note)}") }
            }
        }.trimEnd() + "\n"
    }

    private fun escapeTitle(title: String): String = TextUtils.cleanText(title).replace("[", "\\[").replace("]", "\\]")

    /** Same as ReportAssembler: wrap links that contain parentheses or spaces in angle brackets so they do not break markdown. */
    private fun encodeLink(link: String): String {
        val clean = TextUtils.cleanText(link)
        return if (clean.any { it == '(' || it == ')' || it == ' ' }) "<$clean>" else clean
    }

    /** Do not start an LLM call when material is empty; callers use this to surface a concrete failure reason. */
    fun emptyReason(items: List<PeriodicDigestItem>): String? =
        if (items.isEmpty()) "这段时间没有任何已成功发布的日报条目，无法生成周期简报。" else null
}
