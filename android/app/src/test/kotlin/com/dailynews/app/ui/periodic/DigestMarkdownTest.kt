package com.dailynews.app.ui.periodic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KEEP: periodic digests previously rendered as raw markdown source — literal `##`,
 * `**`, and unclickable `[title](url)` — while the neighboring daily-report screen
 * is fully structured cards.
 *
 * The parser only accepts the one format the renderer (`PeriodicDigestRenderer`)
 * produces, so both must change together; this test is the landing point for that
 * contract. Equally important: any unrecognized line must be kept as-is. The worst
 * case is falling back to today's look, not eating the content.
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
