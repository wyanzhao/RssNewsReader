package com.dailynews.pipeline.editorial

import com.dailynews.model.PeriodicDigest
import com.dailynews.pipeline.text.TextUtils

/**
 * Deterministic contract for periodic digests.
 *
 * Same floor as Part 1: the model may only pick and write from **given material**,
 * must not invent links, must not write oversize summaries, and must not put links
 * in body text. The material is already published Chinese summaries from upstream,
 * so this fence is against new content introduced by the second editorial pass.
 */
object PeriodicDigestContracts {
    const val MAX_SECTIONS = 12
    const val SUMMARY_HARD_CAP = 400

    /** The prompt asks for 10–20 characters; even with headroom this is far below the summary cap. */
    const val HEADING_HARD_CAP = 60

    /** `notes[]` is the rationale for inclusion/exclusion, not body text. */
    const val NOTE_HARD_CAP = 200
    const val MAX_NOTES = 12

    fun validate(digest: PeriodicDigest, expectedPeriod: String, availableLinks: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        val period = TextUtils.cleanText(digest.period)
        // Catch "answered the wrong week": the model occasionally copies the period from a prompt example.
        if (period != expectedPeriod) {
            errors += "period must be exactly \"$expectedPeriod\" but was \"$period\""
        }
        if (digest.sections.isEmpty()) errors += "sections must not be empty"
        if (digest.sections.size > MAX_SECTIONS) {
            errors += "sections must be at most $MAX_SECTIONS but was ${digest.sections.size}"
        }
        val normalizedAvailable = availableLinks.mapTo(mutableSetOf(), TextUtils::cleanText)
        val seenLinks = mutableSetOf<String>()
        // notes[] and heading are also free-text from the model, and the material
        // originates in scraped article_text. They previously skipped lint entirely
        // while neighboring summary_zh did not — so injected content could walk these
        // two fields around the whole fence, including markdown links that summary_zh
        // would have rejected.
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
                // No invented links: a weekly digest may only cite items from this week's published reports.
                if (link !in normalizedAvailable) errors += "$label references unknown link $link"
                if (!seenLinks.add(link)) errors += "$label repeats link $link already used by an earlier section"
            }
        }
        return errors
    }
}
