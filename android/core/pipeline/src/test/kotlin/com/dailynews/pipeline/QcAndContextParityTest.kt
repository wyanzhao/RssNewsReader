package com.dailynews.pipeline

import com.dailynews.model.ArtifactJson
import com.dailynews.model.LlmContext
import com.dailynews.model.ValidatorExitClass
import com.dailynews.model.ContextBudget
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.context.CacheLookup
import com.dailynews.pipeline.editorial.Part2Merger
import com.dailynews.pipeline.ports.EditorialCacheRecord
import com.dailynews.pipeline.editorial.FallbackReportRenderer
import com.dailynews.pipeline.validate.QcValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * KEEP unless a method is explicitly labelled MIGRATION-GUARD. Synthetic
 * goldens are vendored Kotlin assets; real 2026-08-03 sidecars remain local.
 */
class QcAndContextParityTest {
    @Test
    fun `validator and llm context match frozen Kotlin golden`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val outcome = QcValidator().validate(raw, feeds)
        assertEquals(ValidatorExitClass.OK, outcome.exitClass)
        assertTrue(outcome.result.passed)
        val context = LlmContextBuilder().build(
            raw,
            outcome.result,
            "2026-04-10",
            "/tmp/DailyNews/rss-report-2026-04-10.md",
            config,
        ).llmContext
        val expected = ArtifactJson.codec.parseToJsonElement(FixtureFactory.text("fixtures/llm_context_golden.json"))
        val actual = ArtifactJson.codec.parseToJsonElement(ArtifactJson.codec.encodeToString(context))
        assertEquals(expected, actual)
    }

    @Test
    fun `context budget uses exact two-space artifact bytes and object violations`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        val artifacts = LlmContextBuilder().build(raw, validation, "2026-04-10", "/report.md", config)

        assertEquals(
            ArtifactJson.codec.encodeToString(artifacts.llmContext).toByteArray().size,
            artifacts.contextBudget.sizes.llmContextBytes,
        )
        val tiny = LlmContextBuilder().build(
            raw,
            validation,
            "2026-04-10",
            "/report.md",
            config.copy(contextBudget = config.contextBudget.copy(totalContextMaxBytes = 1)),
        )
        assertEquals("total_context_bytes", tiny.contextBudget.violations.single().size)
        assertEquals(1, tiny.contextBudget.violations.single().limit)
    }

    @Test
    fun `part2 cache hit schema and merge preserve event metadata`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        val artifacts = LlmContextBuilder().build(
            raw,
            validation,
            "2026-04-10",
            "/report.md",
            config,
            cacheLookup = CacheLookup { article ->
                EditorialCacheRecord(
                    article.link,
                    article.link,
                    article.source,
                    article.title,
                    summaryZh = "缓存摘要",
                    noiseBucket = "cached-bucket",
                    eventKey = "cached-event",
                )
            },
        )
        val encoded = ArtifactJson.codec.parseToJsonElement(ArtifactJson.codec.encodeToString(artifacts.part2Context)).jsonObject
        val first = encoded.getValue("groups").jsonArray.first().jsonObject.getValue("articles").jsonArray.first().jsonObject

        assertTrue("summary_zh" in first)
        assertTrue("summary_material" !in first)
        assertTrue("event_key" in first)
        assertTrue("noise_bucket" in first)
        val fallbackRaw = raw.copy(articles = raw.articles.mapIndexed { index, article ->
            if (index == 0) article.copy(summaryEn = "short", articleText = "article body fallback") else article
        })
        val miss = LlmContextBuilder().build(fallbackRaw, validation, "2026-04-10", "/report.md", config).part2Context
        assertEquals("article_text_fallback", miss.groups.flatMap { it.articles }.first { it.link == raw.articles.first().link }.summarySource)
        val merged = Part2Merger.merge(artifacts.part2Context, emptyList())
        assertEquals("cached-event", merged.groups.flatMap { it.articles }.first().eventKey)
        assertEquals("cached-bucket", merged.groups.flatMap { it.articles }.first().noiseBucket)

        val cacheWithoutEvent = LlmContextBuilder().build(
            raw,
            validation,
            "2026-04-10",
            "/report.md",
            config,
            cacheLookup = CacheLookup { article ->
                EditorialCacheRecord(article.link, article.link, article.source, article.title, summaryZh = "缓存摘要")
            },
        )
        val eventless = ArtifactJson.codec.parseToJsonElement(
            ArtifactJson.codec.encodeToString(cacheWithoutEvent.part2Context),
        ).jsonObject.getValue("groups").jsonArray.first().jsonObject
            .getValue("articles").jsonArray.first().jsonObject
        assertEquals("\"\"", eventless.getValue("event_key").toString())
    }

    /** MIGRATION-GUARD: reads local-only real-run sidecars. */
    @Test
    fun `real 2026 08 03 contexts fit configured budgets with exact bytes`() = runBlocking {
        val contextText = FixtureFactory.text("replay/2026-08-03/llm_context.json")
        val briefText = FixtureFactory.text("replay/2026-08-03/part1_brief.json")
        val part2Text = FixtureFactory.text("replay/2026-08-03/part2_context.json")
        val budgetText = FixtureFactory.text("replay/2026-08-03/context_budget.json")
        val budget = ArtifactJson.codec.decodeFromString<ContextBudget>(budgetText)

        assertTrue(budget.withinBudget, budget.violations.toString())
        assertEquals(contextText.toByteArray().size, budget.sizes.llmContextBytes)
        assertEquals(briefText.toByteArray().size, budget.sizes.part1BriefBytes)
        assertEquals(part2Text.toByteArray().size, budget.sizes.part2ContextBytes)
    }

    /**
     * MIGRATION-GUARD: reads local-only real-run sidecars.
     *
     * Rebuilds the real 2026-08-03 sidecars in Kotlin and diffs them against the
     * artifacts Python actually wrote. This is what pins the byte accounting:
     * comparing Python's own files to Python's own numbers proves nothing about
     * this port, and every earlier accounting bug lived exactly here.
     */
    @Test
    fun `kotlin rebuilds the real Python sidecars field for field`() = runBlocking {
        val replay = ReplayFixture.load()

        val artifacts = LlmContextBuilder().build(
            replay.raw,
            replay.validation,
            "2026-08-03",
            replay.reportPath,
            replay.config,
        )

        assertEquals(
            replay.expected("part1_brief.json"),
            ReplayFixture.normalize(ArtifactJson.codec.encodeToString(artifacts.part1Brief)),
        )
        assertEquals(
            replay.expected("part2_context.json"),
            ReplayFixture.normalize(ArtifactJson.codec.encodeToString(artifacts.part2Context)),
        )

        val pythonBudget = ArtifactJson.codec.decodeFromString<ContextBudget>(FixtureFactory.text("replay/2026-08-03/context_budget.json"))
        assertEquals(pythonBudget.sizes.part1BriefBytes, artifacts.contextBudget.sizes.part1BriefBytes)
        assertEquals(pythonBudget.sizes.llmContextBytes, artifacts.contextBudget.sizes.llmContextBytes)
        // The one accepted divergence: Python records an absolute cache file path
        // that has no Android equivalent, so part2_context is exactly that string shorter.
        val cachePathBytes = FixtureFactory.json("replay/2026-08-03/part2_context.json")
            .getValue("cache").jsonObject.getValue("path").jsonPrimitive.content.length
        assertEquals(pythonBudget.sizes.part2ContextBytes - cachePathBytes, artifacts.contextBudget.sizes.part2ContextBytes)
        assertEquals(pythonBudget.sizes.totalContextBytes - cachePathBytes, artifacts.contextBudget.sizes.totalContextBytes)
        assertTrue(artifacts.contextBudget.withinBudget)
    }

    /** MIGRATION-GUARD: reads local-only real-run sidecars. */
    @Test
    fun `preview policy stays at the Python word counts`() = runBlocking {
        val replay = ReplayFixture.load()
        val brief = ArtifactJson.codec.parseToJsonElement(
            ArtifactJson.codec.encodeToString(LlmContextBuilder().build(replay.raw, replay.validation, "2026-08-03", replay.reportPath, replay.config).part1Brief),
        ).jsonObject

        assertEquals("70", brief.getValue("article_text_preview_words").toString())
        assertEquals("100", brief.getValue("preview_policy").jsonObject.getValue("short_summary_threshold").toString())
    }

    @Test
    fun `fallback renderer is byte equal to frozen Kotlin golden`() {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val validation = QcValidator().validate(raw, feeds).result
        val actual = FallbackReportRenderer.render(raw, validation, "2026-04-10", config)
        assertEquals(FixtureFactory.text("fixtures/markdown_render_golden.md"), actual)
    }
}
