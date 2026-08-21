package com.dailynews.pipeline.editorial

import com.dailynews.model.PeriodicDigest
import com.dailynews.pipeline.text.TextUtils

/**
 * 周期简报的确定性契约。
 *
 * 与 Part 1 同一条底线：模型只能从**给定素材**里挑和写，不能造链接、不能写超长摘要、
 * 不能把链接塞进正文。素材本身已经是上游发布过的中文摘要，所以这里防的是二次编辑
 * 引入的新东西。
 */
object PeriodicDigestContracts {
    const val MAX_SECTIONS = 12
    const val SUMMARY_HARD_CAP = 400

    /** prompt 要求 10–20 字；留出余量后仍远小于摘要上限。 */
    const val HEADING_HARD_CAP = 60

    /** `notes[]` 是取舍理由，不是正文。 */
    const val NOTE_HARD_CAP = 200
    const val MAX_NOTES = 12

    fun validate(digest: PeriodicDigest, expectedPeriod: String, availableLinks: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        val period = TextUtils.cleanText(digest.period)
        // 抓「答错了周」：模型偶尔会照抄 prompt 示例里的周期。
        if (period != expectedPeriod) {
            errors += "period must be exactly \"$expectedPeriod\" but was \"$period\""
        }
        if (digest.sections.isEmpty()) errors += "sections must not be empty"
        if (digest.sections.size > MAX_SECTIONS) {
            errors += "sections must be at most $MAX_SECTIONS but was ${digest.sections.size}"
        }
        val normalizedAvailable = availableLinks.mapTo(mutableSetOf(), TextUtils::cleanText)
        val seenLinks = mutableSetOf<String>()
        // notes[] 与 heading 同样是模型自由文本，且素材源自抓取来的 article_text。
        // 它们此前完全不过 lint，而隔壁的 summary_zh 过——于是注入内容可以走这两个
        // 字段绕过整条围栏，还能塞进 summary_zh 塞不进去的 markdown 链接。
        if (digest.notes.size > MAX_NOTES) errors += "notes must be at most $MAX_NOTES but was ${digest.notes.size}"
        digest.notes.forEachIndexed { index, note ->
            errors += EditorialContracts.summaryLintErrors(note, "notes[$index]", NOTE_HARD_CAP)
        }
        digest.sections.forEachIndexed { index, section ->
            val label = "section[$index]"
            if (TextUtils.cleanText(section.heading).isEmpty()) errors += "$label heading must not be empty"
            errors += EditorialContracts.summaryLintErrors(section.heading, "$label heading", HEADING_HARD_CAP)
            errors += EditorialContracts.summaryLintErrors(section.summaryZh, "$label summary_zh", SUMMARY_HARD_CAP)
            if (section.links.isEmpty()) errors += "$label must reference at least one link"
            section.links.forEach { raw ->
                val link = TextUtils.cleanText(raw)
                // 禁止造链接：周报只能引用本周已发布报告里的条目。
                if (link !in normalizedAvailable) errors += "$label references unknown link $link"
                if (!seenLinks.add(link)) errors += "$label repeats link $link already used by an earlier section"
            }
        }
        return errors
    }
}
