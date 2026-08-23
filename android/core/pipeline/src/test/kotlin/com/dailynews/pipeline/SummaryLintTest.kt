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
 * KEEP: `summaryLintErrors` is the fence `AGENTS.md` names as "stop injected
 * content smuggling out via article_text". Previously the length branch had
 * no test anywhere in the repo — the longest summary in tests was six
 * characters, so changing 400 to 40000 would turn nothing red.
 *
 * Assertions go through `validatePart1` rather than calling the helper
 * directly, so the wiring is pinned too.
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
        // In-app this is inert text, but every share path hands it to a chat app that auto-linkifies.
        listOf("详见 bit.ly/x2f", "访问 evil.com/verify 领取", "来源 www.example.org", "见 https://a.test/b")
            .forEach { assertTrue(lint(it).isNotEmpty(), "应被拒: $it") }

        // A false positive burns a whole contract retry, so version numbers and decimals must be safe.
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
