package com.dailynews.pipeline

import com.dailynews.model.*
import com.dailynews.pipeline.flow.*
import com.dailynews.pipeline.editorial.Part2Merger
import com.dailynews.pipeline.ports.ArtifactSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class EditorialComparisonTest {
    @Test fun `both arms share sources and disabled cache and only feedback changes`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val saved = mutableMapOf<Pair<String,String>, String>()
        val snapshots = mutableListOf<Pair<List<Article>, List<String>>>()
        val runner = EditorialComparison({ factory ->
            EditorialEngine { _, context, brief, part2, _, topN, _, mode, _ ->
                snapshots += context.allArticles to brief.editorFeedback
                assertEquals(Part2Mode.LAZY, mode)
                val link = context.allArticles.first().link
                val selected = factory.build(context, listOf(link))
                assertEquals(0, selected.cacheHits)
                assertNull(selected.articles.first().cachedSummaryZh)
                EditorialOutput(Part1Plan(listOf(Part1PlanItem(link, "来源支持的研究进展", emptyList())), topN-1), Part2Merger.mergeCachedOnly(part2))
            }
        }, object : ArtifactSink {
            override suspend fun write(runId: String, relativePath: String, content: ByteArray) { saved[runId to relativePath] = content.decodeToString() }
        })
        val arms = runner.run("experiment", raw, feeds, "2026-04-10", config, "优先芯片", emptyList())
        assertEquals(2, arms.size)
        assertEquals(snapshots[0].first, snapshots[1].first)
        assertEquals(emptyList(), snapshots[0].second)
        assertEquals(listOf("优先芯片"), snapshots[1].second)
        assertEquals(2, saved.keys.count { it.second == "reviewed-report.md" })
    }

    @Test fun `second arm failure propagates without reporting a completed comparison`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val written = mutableListOf<Pair<String, String>>()
        val runner = EditorialComparison({
            EditorialEngine { runId, context, _, part2, _, topN, _, _, _ ->
                if (runId.endsWith("candidate")) error("provider failed")
                EditorialOutput(Part1Plan(listOf(Part1PlanItem(context.allArticles.first().link,
                    "来源支持的研究进展", emptyList())), topN-1), Part2Merger.mergeCachedOnly(part2))
            }
        }, object : ArtifactSink {
            override suspend fun write(runId: String, relativePath: String, content: ByteArray) { written += runId to relativePath }
        })
        assertFailsWith<IllegalStateException> {
            runner.run("experiment", raw, feeds, "2026-04-10", config, "优先芯片", emptyList())
        }
        assertEquals(listOf("experiment-baseline" to "reviewed-report.md"), written.filter { it.second == "reviewed-report.md" })
    }

    @Test fun `invalid sources stop before any model invocation`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        var called = false
        val runner = EditorialComparison({ called = true; error("must not build engine") }, object : ArtifactSink {
            override suspend fun write(runId: String, relativePath: String, content: ByteArray) = error("must not write")
        })
        assertFailsWith<IllegalArgumentException> { runner.run("experiment", raw.copy(count = raw.count + 1), feeds, "2026-04-10", config, "优先芯片", emptyList()) }
        assertFalse(called)
    }
}
