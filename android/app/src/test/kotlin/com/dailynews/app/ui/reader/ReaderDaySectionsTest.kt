package com.dailynews.app.ui.reader

import com.dailynews.data.db.ReaderArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Epic V: grouping, counts, and lazy-item space conversion for day sections. */
class ReaderDaySectionsTest {
    private fun article(key: String, iso: String) = ReaderArticle(
        linkKey = key,
        link = key,
        title = "title $key",
        source = "Source",
        summaryZh = "摘要",
        pubDateUtc = "2026-08-05 06:30 UTC",
        pubDateIso = iso,
        readAtUtc = null,
        favoritedAtUtc = null,
    )

    @Test
    fun groupsByUtcDayUsingTheSameKeyAsSql() {
        val articles = listOf(
            article("a", "2026-08-05T06:30+00:00"),
            // When seconds are 0, pubDateIso is a character shorter; take(10) must still yield the same day key.
            article("b", "2026-08-05T00:00+00:00"),
            article("c", "2026-08-04T22:45:09+00:00"),
        )
        val sections = readerDaySections(articles, mapOf("2026-08-05" to 24, "2026-08-04" to 31))

        assertEquals(listOf("2026-08-05", "2026-08-04"), sections.map { it.day })
        assertEquals(listOf(2, 1), sections.map { it.articles.size })
        // The section header writes that day's full count, not the count rendered in the window.
        assertEquals(listOf(24, 31), sections.map { it.totalCount })
    }

    @Test
    fun fallsBackToWindowCountWhenAggregateIsMissing() {
        val articles = listOf(article("a", "2026-08-05T06:30+00:00"))
        val sections = readerDaySections(articles, emptyMap())
        // Prefer under-reporting over displaying 0.
        assertEquals(1, sections.single().totalCount)
    }

    @Test
    fun lazyItemCountIncludesHeaders() {
        val sections = readerDaySections(
            listOf(
                article("a", "2026-08-05T06:30+00:00"),
                article("b", "2026-08-04T06:30+00:00"),
                article("c", "2026-08-04T05:30+00:00"),
            ),
            emptyMap(),
        )
        // 3 articles + 2 section headers = 5 lazy items. The firstVisibleItemIndex used
        // for paging lives in this same space; they must match or the window grows early.
        assertEquals(5, readerLazyItemCount(sections))
        assertEquals(0, readerLazyItemCount(emptyList()))
    }

    @Test
    fun labelCarriesWeekdayAndDropsYear() {
        assertEquals("08-05 星期三", readerDayLabel("2026-08-05"))
        assertEquals("not-a-date", readerDayLabel("not-a-date"))
    }

    @Test
    fun windowCapNoticeOnlyAppearsAtTheCeiling() {
        assertNull(readerWindowCapNotice(READER_INITIAL_WINDOW, 100))
        assertNull(readerWindowCapNotice(READER_WINDOW_MAX, 300))
        val notice = readerWindowCapNotice(READER_WINDOW_MAX, READER_WINDOW_MAX)
        assertEquals(true, notice?.contains("1000"))
    }
}
