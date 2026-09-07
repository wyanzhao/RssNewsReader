package com.dailynews.pipeline

import com.dailynews.pipeline.text.readingSearchMatches
import com.dailynews.model.ArticleAnnotations
import com.dailynews.model.ReadingPreferences
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReadingSearchTest {
    @Test fun `position restores only matching content and handles removed items`() {
        assertEquals(3 to 90, com.dailynews.pipeline.text.restoreReadingPosition("same", "same", 3, 90, 6))
        assertEquals(0 to 0, com.dailynews.pipeline.text.restoreReadingPosition("old", "changed", 3, 90, 6))
        assertEquals(3 to 0, com.dailynews.pipeline.text.restoreReadingPosition("same", "same", 9, 90, 4))
        assertEquals(0 to 0, com.dailynews.pipeline.text.restoreReadingPosition("", "", 1, 90, 6))
    }

    @Test fun `mixed language terms match across fields with width and case normalization`() {
        assertTrue(readingSearchMatches("ＡＩ 编译", listOf("AI infrastructure", "研究编译器", "芯片")))
        assertFalse(readingSearchMatches("AI GPU", listOf("AI infrastructure", "编译器")))
        assertTrue(readingSearchMatches("%_", listOf("literal %_ tag")))
        assertFalse(readingSearchMatches("%_", listOf("anything")))
    }
    @Test fun `annotation limits fail explicitly and reader preferences stay in range`() {
        assertEquals(listOf("AI"), ArticleAnnotations("note", listOf(" AI ", "ai")).validated().tags)
        assertFailsWith<IllegalArgumentException> { ArticleAnnotations("x".repeat(10_001), emptyList()).validated() }
        assertFailsWith<IllegalArgumentException> { ArticleAnnotations("", (1..11).map { "$it" }).validated() }
        assertEquals(ReadingPreferences(30, 120), ReadingPreferences(100, 0).normalized())
    }
}
