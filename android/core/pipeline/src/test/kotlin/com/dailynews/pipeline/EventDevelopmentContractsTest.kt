package com.dailynews.pipeline

import com.dailynews.model.*
import com.dailynews.pipeline.context.*
import com.dailynews.pipeline.editorial.EventDevelopmentContracts
import com.dailynews.pipeline.editorial.EditorialRefs
import com.dailynews.pipeline.editorial.ArticleRefIndex
import org.junit.jupiter.api.Test
import kotlin.test.*

class EventDevelopmentContractsTest {
    private val quote = "The compiler is now generally available."
    private val article = ShortlistContextArticle("a1", "Source", "Compiler release", "https://source.example/new", "", "", quote, "")
    private val past = RecentTopNEvent("Compiler beta", "Source", "compiler", "2026-01-02", "https://source.example/old", "编译器发布测试版。")
    private val context = Part1ShortlistContext(LlmMeta("2026-04-10", "", "run", "/report.md"), 1, 0, emptyList(), listOf(article), watchedHistory = listOf(WatchedEventHistory("compiler", "2026-01-02", past)))
    private val progress = EventDevelopment("2026-01-02", "编译器从测试阶段进入正式可用阶段。", article.link, quote)
    private val item = Part1PlanItem(article.link, "本次发布正式版本。", emptyList(), "compiler", development = progress)
    private fun errors(value: Part1PlanItem, input: Part1ShortlistContext = context) = EventDevelopmentContracts.errors(Part1Plan(listOf(value), 29), input)

    @Test fun `accepts source bound comparison and resolves evidence id to unchanged link`() {
        assertTrue(errors(item).isEmpty())
        val draft = Part1PlanDraft(listOf(Part1PlanDraftItem("a1", item.summaryZh, emptyList(), "compiler", development = EventDevelopmentDraft(progress.baselineDate, progress.changeZh, "a1", quote))), 29)
        val resolved = EditorialRefs.resolvePart1(draft, ArticleRefIndex(listOf("a1" to article.link)))
        assertEquals(progress, resolved.value?.items?.single()?.development)
        assertTrue(EditorialRefs.resolvePart1(draft.copy(items = draft.items.map { it.copy(development = it.development!!.copy(evidenceRef = "a999")) }), ArticleRefIndex(listOf("a1" to article.link))).errors.isNotEmpty())
    }

    @Test fun `rejects missing comparison wrong date unrelated link and invented excerpt`() {
        assertTrue(errors(item.copy(development = null)).isNotEmpty())
        for (bad in listOf(progress.copy(baselineDate = "2026-01-01"), progress.copy(evidenceLink = past.link),
            progress.copy(evidenceQuote = "Invented speedup of one hundred percent"), progress.copy(changeZh = ""),
            progress.copy(changeZh = "参考 https://evil.example"), progress.copy(evidenceQuote = "now"))) {
            assertTrue(errors(item.copy(development = bad)).isNotEmpty(), bad.toString())
        }
    }

    @Test fun `renaming a known cached event cannot bypass comparison`() {
        val input = context.copy(articles = listOf(article.copy(cachedEventKey = "compiler")))
        assertTrue(errors(item.copy(eventKey = "renamed", development = null), input).isNotEmpty())
    }

    @Test fun `missing history never licenses a fabricated comparison`() {
        val unavailable = context.copy(watchedHistory = listOf(WatchedEventHistory("compiler", "2026-01-02")))
        assertTrue(errors(item, unavailable).isNotEmpty())
        assertTrue(errors(item.copy(development = null), unavailable).isEmpty())
        val newer = past.copy(coveredOn = "2026-04-09", summaryZh = "发布候选版本。")
        assertTrue(errors(item, context.copy(recentTopN = listOf(newer))).isNotEmpty())
    }

    @Test fun `current evidence must be in selected event even when another candidate contains quote`() {
        val other = article.copy(id = "a2", link = "https://source.example/other")
        val input = context.copy(articles = listOf(article, other))
        val withOtherEvidence = item.copy(development = progress.copy(evidenceLink = other.link))
        assertTrue(errors(withOtherEvidence, input).isNotEmpty())
        assertTrue(errors(withOtherEvidence.copy(alsoLinks = listOf(other.link)), input).isEmpty())
    }
}
