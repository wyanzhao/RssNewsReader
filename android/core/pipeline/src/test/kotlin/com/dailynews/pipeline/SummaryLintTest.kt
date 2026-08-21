package com.dailynews.pipeline

import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.validate.QcValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * KEEP: `summaryLintErrors` 是 `AGENTS.md` 明说的那条「防止注入内容经 article_text
 * 偷渡出去」的围栏。此前长度分支在整个仓库里没有任何测试——测试里最长的摘要是
 * 六个字，把 400 改成 40000 不会有任何东西变红。
 *
 * 断言走 `validatePart1` 而不是直接调 helper，这样连接线一起钉住。
 */
class SummaryLintTest {
    @Test
    fun `part1 hard cap is enforced at the boundary`() = runBlocking {
        val context = context()
        val link = context.allArticles.first().link

        assertEquals(emptyList(), lintPart1(context, link, "字".repeat(EditorialContracts.PART1_SUMMARY_HARD_CAP)))
        val over = lintPart1(context, link, "字".repeat(EditorialContracts.PART1_SUMMARY_HARD_CAP + 1))
        assertTrue(over.any { "exceeds 400 chars (401)" in it }, over.toString())
    }

    @Test
    fun `part2 hard cap is enforced at the boundary`() {
        assertEquals(
            emptyList(),
            EditorialContracts.summaryLintErrors("字".repeat(200), "x summary_zh", EditorialContracts.PART2_SUMMARY_HARD_CAP),
        )
        assertTrue(
            EditorialContracts.summaryLintErrors("字".repeat(201), "x summary_zh", EditorialContracts.PART2_SUMMARY_HARD_CAP)
                .any { "exceeds 200 chars" in it },
        )
    }

    @Test
    fun `bare domains are rejected, version numbers are not`() {
        // 应用内是惰性文本，但每条分享路径都交给会自动链接化的聊天软件。
        listOf("详见 bit.ly/x2f", "访问 evil.com/verify 领取", "来源 www.example.org", "见 https://a.test/b")
            .forEach { assertTrue(lint(it).isNotEmpty(), "应被拒: $it") }

        // 误报会白烧一轮契约重试，所以版本号与小数必须安全。
        listOf("GPT-4.5 与 Claude 3.7 的对比", "版本 0.3.1 发布", "营收增长 12.5%", "该模型在 MMLU 上达到 88.7 分")
            .forEach { assertEquals(emptyList(), lint(it), "不该被拒: $it") }
    }

    private fun lint(text: String) = EditorialContracts.summaryLintErrors(text, "x summary_zh", 400)

    private fun lintPart1(context: com.dailynews.model.LlmContext, link: String, summary: String) =
        EditorialContracts.validatePart1(
            context,
            Part1Plan(listOf(Part1PlanItem(link, summary, emptyList())), shortfall = 9),
            topN = 10,
        )

    private suspend fun context(): com.dailynews.model.LlmContext {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        return LlmContextBuilder().build(raw, validation, "2026-04-10", "/report.md", config).llmContext
    }
}
