package com.dailynews.pipeline

import com.dailynews.model.MissingPart2Draft
import com.dailynews.model.MissingPart2DraftItem
import com.dailynews.model.Part1PlanDraft
import com.dailynews.model.Part1PlanDraftItem
import com.dailynews.model.PeriodicDigestDraft
import com.dailynews.model.PeriodicDigestDraftSection
import com.dailynews.pipeline.editorial.ArticleRefIndex
import com.dailynews.pipeline.editorial.EditorialRefs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * KEEP: 短 id 引用层是 2026-08-19 链接改写事故的根治手段。
 *
 * 这些用例钉的是那次事故的确切形状——按标题重造 slug、把撇号 percent-encode 进
 * slug、超长 slug 丢词——以及「便宜模型写 id 时的同义写法不该浪费重试」。
 */
class EditorialRefsTest {
    private val links = listOf(
        "https://therecord.media/hackers-knocked-off-government-breach",
        "https://semiengineering.com/why-chiplets-wont-fix-everything/",
        "https://tomshardware.com/3d-printed-cooler-review",
    )
    private val refs = ArticleRefIndex(links.mapIndexed { index, link -> EditorialRefs.articleId(index) to link })

    @Test
    fun `resolves ids and tolerates case and leading zeros`() {
        assertEquals(links[0], refs.resolve("a1"))
        assertEquals(links[1], refs.resolve("A2"))
        assertEquals(links[2], refs.resolve(" a03 "))
        // 裸数字同样无歧义：id 空间就是 a1..aN，不值得为一次漏写 a 打回整轮。
        assertEquals(links[1], refs.resolve("2"))
    }

    @Test
    fun `accepts a verbatim link but rejects every rewritten one`() {
        // 抄对了就没有理由打回。
        assertEquals(links[0], refs.resolve(links[0]))
        // 以下三条是 8-19 当天实际出现的三种改写。
        assertNull(refs.resolve("https://therecord.media/hackers-knocked-off-government-network-after-security-breach"))
        assertNull(refs.resolve("https://semiengineering.com/why-chiplets-won%E2%80%99t-fix-everything/"))
        assertNull(refs.resolve("https://tomshardware.com/cooler-review"))
    }

    @Test
    fun `an unresolvable ref voids the whole draft instead of dropping the item`() {
        val draft = Part1PlanDraft(
            listOf(
                Part1PlanDraftItem("a1", "摘要一", emptyList()),
                Part1PlanDraftItem("a9", "摘要二", listOf("a2")),
            ),
            shortfall = 28,
        )

        val resolved = EditorialRefs.resolvePart1(draft, refs)

        // 丢掉坏条目会让一份少了条目的计划看起来完全正常，而 shortfall 校验
        // 正是用来发现「悄悄丢条目」的——两者一起失效才是真正的危险。
        assertNull(resolved.value)
        assertEquals(1, resolved.errors.size)
        assertTrue("a9" in resolved.errors.single(), resolved.errors.single())
        assertTrue("a1-a3" in resolved.errors.single(), resolved.errors.single())
    }

    @Test
    fun `part1 refs and also refs both become authoritative links`() {
        val draft = Part1PlanDraft(
            listOf(Part1PlanDraftItem("a2", "摘要", listOf("a1", "3"), eventKey = "chiplets")),
            shortfall = 29,
        )

        val plan = EditorialRefs.resolvePart1(draft, refs).value!!

        assertEquals(links[1], plan.items.single().link)
        assertEquals(listOf(links[0], links[2]), plan.items.single().alsoLinks)
        assertEquals("chiplets", plan.items.single().eventKey)
        assertEquals(29, plan.shortfall)
    }

    @Test
    fun `part2 and digest resolve through the same index`() {
        val part2 = EditorialRefs.resolvePart2(
            MissingPart2Draft(listOf(MissingPart2DraftItem("a3", "摘要"))),
            refs,
        )
        assertEquals(listOf(links[2]), part2.value!!.map { it.link })

        val digest = EditorialRefs.resolveDigest(
            PeriodicDigestDraft("2026-W32", listOf(PeriodicDigestDraftSection("标题", "摘要", listOf("a1", "a2")))),
            refs,
        )
        assertEquals(listOf(links[0], links[1]), digest.value!!.sections.single().links)
        assertEquals("2026-W32", digest.value!!.period)
    }

    @Test
    fun `feedback speaks ids so the model never has to re-read a URL it cannot copy`() {
        val contractError = "part1 item 2 duplicates link ${links[1]}"

        assertEquals("part1 item 2 duplicates link a2", refs.toIdLanguage(contractError))
    }
}
