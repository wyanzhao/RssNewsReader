package com.dailynews.pipeline.editorial

import com.dailynews.model.Part1ShortlistPayload

object ShortlistContracts {
    /** Final selection must account for every shortlisted source, including merged coverage. */
    fun finalPlanErrors(plan: com.dailynews.model.Part1Plan, shortlist: List<String>): List<String> {
        val retained = plan.items.flatMap { listOf(it.link) + it.alsoLinks }
        return errors(Part1ShortlistPayload(retained, plan.excluded), shortlist, 0, shortlist.size, requireComplete = true)
    }

    fun errors(draft: Part1ShortlistPayload, pool: List<String>, targetMin: Int, targetMax: Int, requireComplete: Boolean = false): List<String> = buildList {
        if (draft.links.isEmpty() && pool.isNotEmpty()) add("shortlist must retain at least one candidate")
        if (draft.links.size > targetMax) add("shortlist exceeds maximum $targetMax")
        val referenced = draft.links + draft.excluded.map { it.link }
        val duplicates = referenced.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) add("selected/excluded references overlap or repeat: ${duplicates.joinToString()}")
        val foreign = referenced.filter { it !in pool }
        if (foreign.isNotEmpty()) add("selected/excluded reference is outside the authoritative pool: ${foreign.joinToString()}")
        draft.excluded.forEachIndexed { index, entry ->
            if (entry.reason.isBlank()) add("excluded[$index] requires a specific reason")
            addAll(EditorialContracts.summaryLintErrors(entry.reason, "excluded[$index].reason", 200))
        }
        if ((requireComplete || draft.links.size < targetMin) && referenced.toSet() != pool.toSet()) {
            add(if (requireComplete) "final plan requires an exclusion reason for omitted shortlisted articles: ${(pool.toSet() - referenced.toSet()).joinToString()}"
                else "shortlist below $targetMin requires an exclusion reason for omitted articles: ${(pool.toSet() - referenced.toSet()).joinToString()}")
        }
    }
}
