package com.dailynews.pipeline.editorial

import com.dailynews.model.MissingPart2Draft
import com.dailynews.model.MissingPart2Summary
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanDraft
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.PeriodicDigest
import com.dailynews.model.PeriodicDigestDraft
import com.dailynews.model.PeriodicDigestSection
import com.dailynews.pipeline.text.TextUtils

/**
 * 短 id 引用层：模型写 `a7`，Kotlin 还原成权威 link。
 *
 * 2026-08-19 的三轮 part1_plan 契约失败不是抓取问题，是模型在**复制 URL** 这一步
 * 出错——按标题重造 slug、把标题撇号塞进 slug、超长 slug 丢词。要求便宜模型逐字
 * 复制 80 字符的字符串是一个它做不到的任务，而 `a7` 是它做得到的。这一层把那个
 * 任务从契约里删掉，而不是在它出错之后想办法猜回来。
 *
 * link 依然留在发给模型的负载里（人读产物时需要，索引也从模型真正看到的那份负载
 * 构建），但 prompt 明确禁止回显。
 */
object EditorialRefs {
    /** 输入文章的 id 方案。id 由位置生成，与负载数组顺序一一对应。 */
    fun articleId(index: Int): String = "a${index + 1}"

    fun resolvePart1(draft: Part1PlanDraft, refs: ArticleRefIndex): RefResolution<Part1Plan> {
        val errors = mutableListOf<String>()
        val items = draft.items.mapIndexed { zeroIndex, item ->
            val index = zeroIndex + 1
            val link = refs.resolve(item.ref)
            if (link == null) errors += refs.unknown("part1 item $index ref", item.ref)
            val alsoLinks = item.alsoRefs.mapNotNull { raw ->
                refs.resolve(raw).also { if (it == null) errors += refs.unknown("part1 item $index also_ref", raw) }
            }
            Part1PlanItem(
                link = link.orEmpty(),
                summaryZh = item.summaryZh,
                alsoLinks = alsoLinks,
                eventKey = item.eventKey,
                noiseBucket = item.noiseBucket,
            )
        }
        return resolution(Part1Plan(items, draft.shortfall, draft.notes), errors)
    }

    fun resolvePart2(draft: MissingPart2Draft, refs: ArticleRefIndex): RefResolution<List<MissingPart2Summary>> {
        val errors = mutableListOf<String>()
        val items = draft.items.mapIndexed { zeroIndex, item ->
            val index = zeroIndex + 1
            val link = refs.resolve(item.ref)
            if (link == null) errors += refs.unknown("part2 item $index ref", item.ref)
            MissingPart2Summary(link.orEmpty(), item.summaryZh, item.noiseBucket, item.eventKey)
        }
        return resolution(items, errors)
    }

    fun resolveDigest(draft: PeriodicDigestDraft, refs: ArticleRefIndex): RefResolution<PeriodicDigest> {
        val errors = mutableListOf<String>()
        val sections = draft.sections.mapIndexed { index, section ->
            val links = section.refs.mapNotNull { raw ->
                refs.resolve(raw).also { if (it == null) errors += refs.unknown("section[$index] ref", raw) }
            }
            PeriodicDigestSection(section.heading, section.summaryZh, links, section.eventKeys)
        }
        return resolution(PeriodicDigest(draft.period, sections, draft.notes), errors)
    }

    // 解析失败就整份作废，而不是丢掉坏条目继续：一份少了三条的计划看起来完全正常，
    // 而 shortfall 校验正是用来发现「悄悄丢条目」的。
    private fun <T> resolution(value: T, errors: List<String>) =
        RefResolution(value.takeIf { errors.isEmpty() }, errors)
}

/** 解析结果：`value` 非空即代表全部引用都落在素材内。 */
data class RefResolution<T>(val value: T?, val errors: List<String>)

/**
 * 一次调用的 id → link 索引。
 *
 * 从模型实际收到的那份负载构建（而不是重新按顺序推导），所以负载与索引不可能失步。
 */
class ArticleRefIndex(entries: List<Pair<String, String>>) {
    private val byId = entries.associate { (id, link) -> normalizeId(id) to link }
    private val byLink = entries.associate { (_, link) -> TextUtils.cleanText(link) to link }
    private val ids = entries.map { it.first }
    // 最长优先，否则一个是另一个前缀的链接会被截半替换。
    private val idByLink = entries.map { (id, link) -> link to id }.sortedByDescending { it.first.length }

    /** 有效 id 区间，写进重试反馈里——模型需要知道它能选什么。 */
    val idRange: String = when {
        ids.isEmpty() -> "(none)"
        ids.size == 1 -> ids.single()
        else -> "${ids.first()}-${ids.last()}"
    }

    /**
     * 宽进严出。接受 `a7` / `A7` / `7` / `a07` 这类同义写法，也接受**逐字正确**的原始
     * link——模型偶尔仍会回显链接，抄对了就没有理由打回。被改写过的链接照样解析
     * 失败，那正是这一层要挡的东西。
     */
    fun resolve(raw: String): String? {
        val cleaned = TextUtils.cleanText(raw)
        if (cleaned.isEmpty()) return null
        return byId[normalizeId(cleaned)] ?: byLink[cleaned]
    }

    /**
     * 把要回传给模型的文字里的权威 link 换成它认识的 id。
     *
     * 只用于重试反馈：契约违规产物里保留原始 link，那是给人排查用的。反馈里留着
     * 80 字符的 URL 只会让模型重新面对它抄不对的那个字符串。
     */
    fun toIdLanguage(text: String): String =
        idByLink.fold(text) { acc, (link, id) -> acc.replace(link, id) }

    internal fun unknown(label: String, raw: String): String {
        val shown = TextUtils.cleanText(raw).take(80).ifEmpty { "<empty>" }
        return "$label \"$shown\" is not one of the supplied ids (valid: $idRange)"
    }

    private companion object {
        val ID_PATTERN = Regex("[aA]?0*(\\d+)")

        fun normalizeId(value: String): String {
            val digits = ID_PATTERN.matchEntire(value.trim())?.groupValues?.get(1)
                ?: return value.trim().lowercase()
            return "a$digits"
        }
    }
}
