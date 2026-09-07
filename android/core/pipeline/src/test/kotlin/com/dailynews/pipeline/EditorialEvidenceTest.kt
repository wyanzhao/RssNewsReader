package com.dailynews.pipeline

import com.dailynews.model.LlmMeta
import com.dailynews.pipeline.context.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class EditorialEvidenceTest {
    @Test fun `incomplete achieved-goal clause is withheld without changing complete aims`() {
        val complete = "We aim to build an automated researcher under human supervision."
        assertEquals(complete, completeExcerptPrefix("$complete We reached the goal of having an..."))
        assertEquals("Version 2.5 improved performance.", completeExcerptPrefix("Version 2.5 improved performance. We measured 3.1…"))
        assertEquals("已公布实验数据。", completeExcerptPrefix("已公布实验数据。未来计划……"))
        assertEquals("", completeExcerptPrefix("We reached the goal of having an..."))
        assertEquals("A complete statement.", completeExcerptPrefix("A complete statement."))
        assertEquals("An unmarked fragment", completeExcerptPrefix("An unmarked fragment"))
    }

    @Test fun `projection is observable preserves authority and invalidates affected cached summaries`() {
        val article = ShortlistContextArticle("a1", "Lab", "Research report", "https://example.test/a", "", "",
            "The report presents early usage data.", "Research proceeds. We achieved an...", "已实现研究员", "research")
        val original = Part1ShortlistContext(LlmMeta("2026-09-07", "", "run", "report.md"), 1, 1, emptyList(), listOf(article))
        val result = editorialEvidenceContext(original)
        assertEquals("Research proceeds.", result.articles.single().articleText)
        assertTrue(result.articles.single().articleTextTailOmitted)
        assertEquals(article.link, result.articles.single().link)
        assertEquals(article.summaryEn, result.articles.single().summaryEn)
        assertEquals("research", result.articles.single().cachedEventKey)
        assertNull(result.articles.single().cachedSummaryZh)
        assertEquals(0, result.cacheHits)
        assertEquals(article, original.articles.single())
        assertEquals(result, editorialEvidenceContext(result))
    }
}
