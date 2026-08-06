package com.dailynews.pipeline.editorial

import com.dailynews.model.PeriodicDigest
import com.dailynews.pipeline.flow.PeriodicDigestInput
import com.dailynews.pipeline.flow.PeriodicDigestItem
import com.dailynews.pipeline.text.TextUtils

/**
 * 把 [PeriodicDigest] 与权威素材连接成 markdown。
 *
 * 模型只交出 heading / summary_zh / links；标题、来源、日期一律从输入素材按 link
 * 反查——与 ReportAssembler 同一条契约，模型不回显它无权改写的字段。
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

    /** 与 ReportAssembler 同款：含括号或空格的链接用尖括号包裹，避免撑破 markdown。 */
    private fun encodeLink(link: String): String {
        val clean = TextUtils.cleanText(link)
        return if (clean.any { it == '(' || it == ')' || it == ' ' }) "<$clean>" else clean
    }

    /** 素材为空时不该发起 LLM 调用；调用方用这个判断给出明确的失败原因。 */
    fun emptyReason(items: List<PeriodicDigestItem>): String? =
        if (items.isEmpty()) "这段时间没有任何已成功发布的日报条目，无法生成周期简报。" else null
}
