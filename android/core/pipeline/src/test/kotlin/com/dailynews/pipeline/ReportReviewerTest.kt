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
 * KEEP: `ReportReviewer.review` is the last gate between "publish" and "fail".
 *
 * Previously every test reference lived in two places that did not run: a
 * machine-local file gated by assumeTrue, and a method never collected by
 * JUnit because its return type was not Unit. Orchestrator tests stubbed the
 * whole gate, and their editorial fake built the plan straight from
 * allArticles so links were always perfect — the reviewer never saw input
 * that should be rejected. Deleting review's body and returning passed=true
 * would still leave the suite all green.
 *
 * These cases walk a real assembled artifact and **do not depend on any
 * gitignored local replay data**.
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
        // LAZY input is the "not yet expanded" shape: every source is on the
        // roster with empty items, and assemble expands it into the full roster.
        // Passing an already-expanded draft with empty summaries would fail
        // validatePart2 as missing summaries.
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
