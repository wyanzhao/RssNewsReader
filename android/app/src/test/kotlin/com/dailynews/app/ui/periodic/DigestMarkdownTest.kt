package com.dailynews.app.ui.periodic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KEEP: 周期简报此前整段以 markdown 源码呈现——字面的 `##`、`**` 与不可点的
 * `[标题](url)`，而隔壁一屏的日报是完整的结构化卡片。
 *
 * 解析端只认渲染端（`PeriodicDigestRenderer`）产出的那一种格式，所以两者必须一起
 * 改；这条用例就是那个约定的落点。另一条同样重要：任何不认识的行都必须原样保留，
 * 最坏情况是回到今天的样子，而不是把内容吃掉。
 */
class DigestMarkdownTest {
    private val sample = """
        # 2026-W32 周报

        ## 1. OpenAI 融资进展

        本周这条线索从开启到交割，估值抬到 3000 亿美元。

        - 2026-08-03 · [OpenAI opens round](https://example.com/a) · TechCrunch
        - 2026-08-05 · [Round closes higher](https://example.com/b) · The Verge

        ## 2. 芯片出口管制

        新规把先进制程设备纳入清单。

        - 2026-08-06 · [Export rules tighten](https://example.com/c) · Reuters
    """.trimIndent()

    @Test
    fun parsesTitleSectionsBodiesAndLinks() {
        val parsed = parseDigestMarkdown(sample)

        assertEquals("2026-W32 周报", parsed.title)
        assertEquals(listOf("1. OpenAI 融资进展", "2. 芯片出口管制"), parsed.sections.map { it.heading })
        assertTrue("估值抬到 3000 亿美元" in parsed.sections.first().body)

        val first = parsed.sections.first().links
        assertEquals(listOf("OpenAI opens round", "Round closes higher"), first.map { it.title })
        assertEquals("https://example.com/a", first.first().url)
        assertEquals("2026-08-03 · TechCrunch", first.first().meta)
        assertEquals(1, parsed.sections[1].links.size)
    }

    @Test
    fun unknownLinesSurviveInsteadOfBeingDropped() {
        val parsed = parseDigestMarkdown("完全不认识的一行\n\n> 引用块\n")

        assertEquals("", parsed.title)
        assertEquals(emptyList(), parsed.sections)
        assertTrue("完全不认识的一行" in parsed.trailing)
        assertTrue("引用块" in parsed.trailing)
    }

    @Test
    fun emphasisMarkersNeverReachTheReader() {
        val parsed = parseDigestMarkdown("# 标题\n\n## **重点**段落\n\n正文含 **强调** 字样。\n")

        assertEquals("重点段落", parsed.sections.single().heading)
        assertTrue("正文含 强调 字样。" in parsed.sections.single().body)
    }
}
