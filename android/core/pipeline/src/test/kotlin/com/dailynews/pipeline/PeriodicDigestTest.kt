package com.dailynews.pipeline

import com.dailynews.model.PeriodicDigest
import com.dailynews.model.PeriodicDigestSection
import com.dailynews.pipeline.editorial.PeriodicDigestContracts
import com.dailynews.pipeline.editorial.PeriodicDigestRenderer
import com.dailynews.pipeline.flow.PeriodicDigestInput
import com.dailynews.pipeline.flow.PeriodicDigestItem
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** KEEP: 周期简报只能引用已发布素材，且不得伪造任何内容。 */
class PeriodicDigestTest {
    private val items = listOf(
        PeriodicDigestItem("2026-08-03", "OpenAI opens round", "TechCrunch", "https://example/a", "摘要 A", "openai-funding"),
        PeriodicDigestItem("2026-08-05", "Round closes higher", "The Verge", "https://example/b", "摘要 B", "openai-funding"),
    )
    private val input = PeriodicDigestInput(
        period = "2026-W32",
        kind = "WEEKLY",
        periodStartDate = "2026-08-03",
        periodEndDate = "2026-08-09",
        reportDates = listOf("2026-08-03", "2026-08-05"),
        items = items,
    )
    private val links = items.mapTo(mutableSetOf()) { it.link }

    private fun digest(
        period: String = "2026-W32",
        sections: List<PeriodicDigestSection> = listOf(
            PeriodicDigestSection("OpenAI 融资进展", "本周这条线索从开启到交割。", listOf("https://example/a", "https://example/b"), listOf("openai-funding")),
        ),
    ) = PeriodicDigest(period, sections)

    @Test
    fun acceptsWellFormedDigest() {
        assertEquals(emptyList(), PeriodicDigestContracts.validate(digest(), "2026-W32", links))
    }

    @Test
    fun rejectsWrongPeriod() {
        // 模型偶尔会照抄 prompt 示例里的周期，这条是唯一能抓住它的检查。
        val errors = PeriodicDigestContracts.validate(digest(period = "2026-W31"), "2026-W32", links)
        assertTrue(errors.any { "period must be exactly" in it })
    }

    @Test
    fun rejectsFabricatedLinks() {
        val errors = PeriodicDigestContracts.validate(
            digest(sections = listOf(PeriodicDigestSection("标题", "摘要", listOf("https://invented/x")))),
            "2026-W32",
            links,
        )
        assertTrue(errors.any { "unknown link" in it })
    }

    @Test
    fun rejectsRepeatedLinksAcrossSections() {
        val errors = PeriodicDigestContracts.validate(
            digest(sections = listOf(
                PeriodicDigestSection("一", "摘要一", listOf("https://example/a")),
                PeriodicDigestSection("二", "摘要二", listOf("https://example/a")),
            )),
            "2026-W32",
            links,
        )
        assertTrue(errors.any { "repeats link" in it })
    }

    @Test
    fun rejectsSummariesCarryingLinksOrOverLength() {
        val withLink = PeriodicDigestContracts.validate(
            digest(sections = listOf(PeriodicDigestSection("标题", "见 https://evil.example", listOf("https://example/a")))),
            "2026-W32",
            links,
        )
        assertTrue(withLink.isNotEmpty())
        val tooLong = PeriodicDigestContracts.validate(
            digest(sections = listOf(PeriodicDigestSection("标题", "字".repeat(500), listOf("https://example/a")))),
            "2026-W32",
            links,
        )
        assertTrue(tooLong.isNotEmpty())
    }

    @Test
    fun rendererJoinsAuthoritativeTitlesNotModelSuppliedOnes() {
        val markdown = PeriodicDigestRenderer.render(input, digest())
        // 标题与来源来自素材，模型 schema 里根本没有这两个字段。
        assertTrue("OpenAI opens round" in markdown)
        assertTrue("TechCrunch" in markdown)
        assertTrue("2026-W32" in markdown)
        assertTrue("OpenAI 融资进展" in markdown)
        // 日期前缀让读者看出线索的时间跨度。
        assertTrue("2026-08-03 · [OpenAI opens round]" in markdown)
    }

    @Test
    fun emptyMaterialIsReportedInsteadOfCallingTheModel() {
        assertTrue(PeriodicDigestRenderer.emptyReason(emptyList())!!.isNotEmpty())
        assertNull(PeriodicDigestRenderer.emptyReason(items))
    }
}
