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
 * Short-id ref layer: the model writes `a7`, Kotlin restores it to the authoritative link.
 *
 * The three rounds of part1_plan contract failures on 2026-08-19 were not a fetch problem; the model was making
 * mistakes at the **URL-copying** step — re-minting slugs from titles, stuffing title apostrophes into slugs,
 * dropping words from over-long slugs. Asking a cheap model to copy an 80-character string verbatim is a task it
 * cannot do, while `a7` is one it can. This layer removes that task from the contract instead of trying to guess
 * things back after the model errs.
 *
 * The link still stays in the payload sent to the model (humans need it when reading the artifact, and the index is
 * built from the very payload the model actually sees), but the prompt explicitly forbids echoing it.
 */
object EditorialRefs {
    /** Id scheme for input articles. Ids are generated from position and correspond one-to-one with the payload array order. */
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

    // If resolution fails the whole draft is voided rather than dropping the bad items and continuing: a plan
    // missing three items looks perfectly normal, and the shortfall check exists precisely to catch "silently dropped items".
    private fun <T> resolution(value: T, errors: List<String>) =
        RefResolution(value.takeIf { errors.isEmpty() }, errors)
}

/** Resolution result: a non-null `value` means every ref lands inside the source material. */
data class RefResolution<T>(val value: T?, val errors: List<String>)

/**
 * The id → link index for one call.
 *
 * Built from the payload the model actually receives (rather than re-derived from order), so the payload and the
 * index can never drift apart.
 */
class ArticleRefIndex(entries: List<Pair<String, String>>) {
    private val byId = entries.associate { (id, link) -> normalizeId(id) to link }
    private val byLink = entries.associate { (_, link) -> TextUtils.cleanText(link) to link }
    private val ids = entries.map { it.first }
    // Longest first, otherwise a link that is a prefix of another is half-replaced.
    private val idByLink = entries.map { (id, link) -> link to id }.sortedByDescending { it.first.length }

    /** Valid id range, written into retry feedback so the model knows what it can pick. */
    val idRange: String = when {
        ids.isEmpty() -> "(none)"
        ids.size == 1 -> ids.single()
        else -> "${ids.first()}-${ids.last()}"
    }

    /**
     * Wide in, strict out. Accepts synonymous forms like `a7` / `A7` / `7` / `a07`,
     * and also a **byte-for-byte correct** original link — the model still echoes
     * links occasionally, and a correct copy is no reason to reject. Mutated links
     * still fail to resolve, which is exactly what this layer is here to catch.
     */
    fun resolve(raw: String): String? {
        val cleaned = TextUtils.cleanText(raw)
        if (cleaned.isEmpty()) return null
        return byId[normalizeId(cleaned)] ?: byLink[cleaned]
    }

    /**
     * Replace authoritative links in text being sent back to the model with the ids
     * it already knows.
     *
     * Used only for retry feedback: contract-violation artifacts keep the original
     * link, which is for human diagnosis. Leaving an 80-character URL in the feedback
     * only puts the model back in front of the string it cannot copy.
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
