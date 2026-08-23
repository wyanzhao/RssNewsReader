package com.dailynews.app

import android.content.Context
import com.dailynews.pipeline.flow.PromptSource

class AssetPromptSource(private val context: Context) : PromptSource {
    override fun part1Shortlist(topN: Int): String = render(read(SHORTLIST_TEMPLATE), topN)
    override fun part1Plan(topN: Int): String = render(read(PLAN_TEMPLATE), topN)
    override fun part2Drafter(): String = read(DRAFTER_TEMPLATE)
    // Periodic info travels as structured JSON inside the user message; the template
    // itself has zero placeholders.
    override fun periodicDigest(): String = read(DIGEST_TEMPLATE)

    internal fun read(name: String): String =
        context.assets.open("prompts/$name").bufferedReader().use { it.readText() }

    companion object {
        internal const val SHORTLIST_TEMPLATE = "part1_shortlist.md"
        internal const val PLAN_TEMPLATE = "part1_plan.md"
        internal const val DRAFTER_TEMPLATE = "part2_drafter.md"
        internal const val DIGEST_TEMPLATE = "periodic_digest.md"
        internal val TEMPLATES = listOf(SHORTLIST_TEMPLATE, PLAN_TEMPLATE, DRAFTER_TEMPLATE, DIGEST_TEMPLATE)

        /**
         * Placeholder → value. This map is the single source of truth for substitution.
         * `AssetPromptContractTest` reverse-checks that every key is used by at least one
         * template and that no placeholder remains after rendering. Renaming a key without
         * updating the markdown used to fail silently: leftover literals stayed in the
         * template and the model accepted them as-is.
         */
        internal fun substitutions(topN: Int): Map<String, String> = linkedMapOf(
            "{SHORTLIST_MIN}" to (topN + 10).toString(),
            "{SHORTLIST_MAX}" to (topN + 15).toString(),
            "{N}" to topN.toString(),
        )

        internal fun render(template: String, topN: Int): String =
            substitutions(topN).entries.fold(template) { text, (key, value) -> text.replace(key, value) }
    }
}
