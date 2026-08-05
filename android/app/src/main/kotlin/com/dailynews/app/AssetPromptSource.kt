package com.dailynews.app

import android.content.Context
import com.dailynews.pipeline.flow.PromptSource

class AssetPromptSource(private val context: Context) : PromptSource {
    override fun part1Shortlist(topN: Int): String = render(read("part1_shortlist.md"), topN)
    override fun part1Plan(topN: Int): String = render(read("part1_plan.md"), topN)
    override fun part2Drafter(): String = read("part2_drafter.md")

    private fun read(name: String): String = context.assets.open("prompts/$name").bufferedReader().use { it.readText() }

    private fun render(template: String, topN: Int): String = template
        .replace("{SHORTLIST_MIN}", (topN + 10).toString())
        .replace("{SHORTLIST_MAX}", (topN + 15).toString())
        .replace("{N}", topN.toString())
}
