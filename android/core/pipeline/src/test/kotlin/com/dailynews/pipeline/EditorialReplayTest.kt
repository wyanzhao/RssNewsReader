package com.dailynews.pipeline

import com.dailynews.model.ArtifactJson
import com.dailynews.model.LlmContext
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2DraftGroup
import com.dailynews.model.Part2Mode
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.context.CacheLookup
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.editorial.EditorialContractException
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.editorial.ReportReviewer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** MIGRATION-GUARD: local-only 2026-08-03 report replay, retired explicitly after Phase C. */
class EditorialReplayTest {
    private val codec = ArtifactJson.codec

    @Test
    fun `real 2026-08-03 replay is byte equal`() {
        val loader = javaClass.classLoader
        assumeTrue(loader.getResource("replay/2026-08-03/llm_context.json") != null, "local ignored replay is unavailable")
        val context = codec.decodeFromString<LlmContext>(FixtureFactory.text("replay/2026-08-03/llm_context.json"))
        val validation = codec.decodeFromString<ValidationResult>(FixtureFactory.text("replay/2026-08-03/validation.json"))
        val part1 = codec.decodeFromString<Part1Plan>(FixtureFactory.text("replay/2026-08-03/part1_plan.json"))
        val part2 = codec.decodeFromString<Part2Draft>(FixtureFactory.text("replay/2026-08-03/part2_draft.json"))
        val report = ReportAssembler().assemble(context, validation, part1, part2, 30, context.meta.reportPath)
        assertEquals(FixtureFactory.text("replay/2026-08-03/rss-report-2026-08-03.md"), report.markdown)
        assertEquals(FixtureFactory.text("replay/2026-08-03/top30.md"), report.topNMarkdown)
        assertTrue(ReportReviewer.review(report.markdown, context.meta.reportPath, context, validation, part1, part2).passed)
    }

    @Test
    fun `top N twenty derives shortfall and rejects duplicates`() {
        val loader = javaClass.classLoader
        assumeTrue(loader.getResource("replay/2026-08-03/llm_context.json") != null)
        val context = codec.decodeFromString<LlmContext>(FixtureFactory.text("replay/2026-08-03/llm_context.json"))
        val source = codec.decodeFromString<Part1Plan>(FixtureFactory.text("replay/2026-08-03/part1_plan.json"))
        val plan = source.copy(items = source.items.take(20), shortfall = 0)
        assertEquals(emptyList(), EditorialContracts.validatePart1(context, plan, 20))
        val duplicate = plan.copy(items = plan.items + plan.items.first(), shortfall = 0)
        assertTrue(EditorialContracts.validatePart1(context, duplicate, 20).isNotEmpty())
    }

    @Test
    fun `lazy Part 2 accepts cached-only handoff and materializes the complete roster`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = com.dailynews.pipeline.validate.QcValidator().validate(raw, feeds).result
        val artifacts = LlmContextBuilder().build(
            raw,
            validation,
            "2026-04-10",
            "/report.md",
            config,
            cacheLookup = CacheLookup { null },
        )
        val part1 = Part1Plan(
            artifacts.llmContext.allArticles.take(10).mapIndexed { index, article ->
                Part1PlanItem(if (index == 0) "  ${article.link}  " else article.link, "中文事件摘要", emptyList())
            },
            shortfall = maxOf(0, 10 - artifacts.llmContext.allArticles.size),
        )
        val cachedOnly = Part2Draft(
            0,
            artifacts.llmContext.sourceGroups.map { group ->
                Part2DraftGroup(group.source, group.status, 0, validation.feedResults.first { it.source == group.source }.error, emptyList())
            },
        )

        val report = ReportAssembler().assemble(
            artifacts.llmContext,
            validation,
            part1,
            cachedOnly,
            topN = 10,
            reportPath = "/report.md",
            part2Mode = Part2Mode.LAZY,
        )

        assertEquals(raw.articles.size, report.items.count { it.part == 2 })
        assertEquals(artifacts.llmContext.allArticles.first().link, report.items.first().link)
        assertTrue(report.items.filter { it.part == 2 }.all { it.summaryZh.isBlank() })
        assertTrue("展开来源后生成中文摘要" in report.markdown)
        assertTrue(
            ReportReviewer.review(
                report.markdown,
                "/report.md",
                artifacts.llmContext,
                validation,
                part1,
                cachedOnly,
                topN = 10,
                part2Mode = Part2Mode.LAZY,
            ).passed,
        )
        assertFailsWith<EditorialContractException> {
            ReportAssembler().assemble(artifacts.llmContext, validation, part1, cachedOnly, topN = 10)
        }
    }
}
