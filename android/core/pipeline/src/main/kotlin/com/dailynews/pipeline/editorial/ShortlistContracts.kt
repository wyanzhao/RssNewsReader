package com.dailynews.pipeline.editorial

import com.dailynews.model.Part1ShortlistPayload

object ShortlistContracts {
    fun errors(draft: Part1ShortlistPayload, pool: List<String>, targetMin: Int, targetMax: Int): List<String> = buildList {
        if (draft.links.isEmpty() && pool.isNotEmpty()) add("shortlist must retain at least one candidate")
        if (draft.links.size > targetMax) add("shortlist exceeds maximum $targetMax")
        val referenced = draft.links + draft.excluded.map { it.link }
        if (referenced.distinct().size != referenced.size) add("selected/excluded references overlap or repeat")
        if (referenced.any { it !in pool }) add("selected/excluded reference is outside the authoritative pool")
        draft.excluded.forEachIndexed { index, entry ->
            if (entry.reason.isBlank()) add("excluded[$index] requires a specific reason")
            addAll(EditorialContracts.summaryLintErrors(entry.reason, "excluded[$index].reason", 200))
        }
        if (draft.links.size < targetMin && referenced.toSet() != pool.toSet()) {
            add("shortlist below $targetMin requires an exclusion reason for every omitted article")
        }
    }
}
