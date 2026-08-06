package com.dailynews.app

import android.content.Context
import com.dailynews.pipeline.flow.PromptSource

class AssetPromptSource(private val context: Context) : PromptSource {
    override fun part1Shortlist(topN: Int): String = render(read(SHORTLIST_TEMPLATE), topN)
    override fun part1Plan(topN: Int): String = render(read(PLAN_TEMPLATE), topN)
    override fun part2Drafter(): String = read(DRAFTER_TEMPLATE)
    // 周期信息走 user 消息里的结构化 JSON，模板本身零占位符。
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
         * 占位符 → 取值。这张表是替换的唯一真相源，`AssetPromptContractTest` 按它反查
         * 每个键至少被某个模板用到、且渲染后一个占位符都不剩。改键名而不改 markdown
         * 曾经是静默失效的：模板里留下未替换的字面量，模型照单全收。
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
