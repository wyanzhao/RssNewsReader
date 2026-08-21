package com.dailynews.pipeline

import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.Part2Mode
import com.dailynews.pipeline.context.CacheLookup
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.editorial.ReportReviewer
import com.dailynews.pipeline.validate.QcValidator
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * KEEP: `ReportReviewer.review` 是「发布还是判失败」的最后一道闸。
 *
 * 此前它的全部测试引用都在两个不生效的地方：一个 assumeTrue 门控的机器本地文件，
 * 和一个因返回类型非 Unit 而从未被 JUnit 收录的方法。编排器测试则把整个 gate 换成
 * 桩，且它的编辑 fake 直接从 allArticles 造 plan，链接天生完美——复核器永远见不到
 * 该被拒的输入。删掉 review 的函数体、直接 return passed=true，整套测试仍然全绿。
 *
 * 这些用例走真实装配产物，且**不依赖任何 gitignore 的本地回放数据**。
 */
class ReportReviewerTest {
    @Test
    fun `accepts a faithfully assembled report`() = runBlocking {
        val f = fixture()
        assertTrue(review(f, f.markdown).passed, review(f, f.markdown).errors.toString())
    }

    @Test
    fun `rejects a report whose link was dropped`() = runBlocking {
        val f = fixture()
        val missing = f.markdown.replace(f.firstLink, "https://example.invalid/rewritten")

        val result = review(f, missing)

        assertFalse(result.passed)
        assertTrue(result.errors.any { f.firstLink in it }, result.errors.toString())
    }

    @Test
    fun `rejects a report whose English title was mutated`() = runBlocking {
        val f = fixture()
        val retitled = f.markdown.replace(f.firstTitle, "标题被改写了")

        val result = review(f, retitled)

        assertFalse(result.passed)
    }

    private fun review(f: Fixture, markdown: String) = ReportReviewer.review(
        markdown,
        "/report.md",
        f.context,
        f.validation,
        f.part1,
        f.part2,
        topN = 10,
        part2Mode = Part2Mode.LAZY,
    )

    private class Fixture(
        val context: com.dailynews.model.LlmContext,
        val validation: com.dailynews.model.ValidationResult,
        val part1: Part1Plan,
        val part2: com.dailynews.model.Part2Draft,
        val markdown: String,
    ) {
        val firstLink: String get() = part1.items.first().link
        val firstTitle: String get() = context.allArticles.first { it.link == firstLink }.title
    }

    private suspend fun fixture(): Fixture {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        val artifacts = LlmContextBuilder()
            .build(raw, validation, "2026-04-10", "/report.md", config, cacheLookup = CacheLookup { null })
        val context = artifacts.llmContext
        val take = minOf(3, context.allArticles.size)
        val part1 = Part1Plan(
            context.allArticles.take(take).map { Part1PlanItem(it.link, "中文事件摘要", emptyList()) },
            shortfall = maxOf(0, 10 - take),
        )
        // LAZY 的入参是「还没展开」的形态：每个来源在册但条目为空，由 assemble
        // 自己展开成完整名册。传一份已展开、摘要为空的草稿会被 validatePart2 判缺摘要。
        val part2 = com.dailynews.model.Part2Draft(
            0,
            context.sourceGroups.map { group ->
                com.dailynews.model.Part2DraftGroup(
                    group.source,
                    group.status,
                    0,
                    validation.feedResults.first { it.source == group.source }.error,
                    emptyList(),
                )
            },
        )
        val report = ReportAssembler().assemble(
            context,
            validation,
            part1,
            part2,
            topN = 10,
            reportPath = "/report.md",
            part2Mode = Part2Mode.LAZY,
        )
        return Fixture(context, validation, part1, part2, report.markdown)
    }
}
