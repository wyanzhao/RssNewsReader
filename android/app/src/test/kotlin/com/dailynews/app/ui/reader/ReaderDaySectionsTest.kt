package com.dailynews.app.ui.reader

import com.dailynews.data.db.ReaderArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Epic V：按天分节的分组、计数与 lazy-item 空间换算。 */
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
            // 秒为 0 时 pubDateIso 会短一截，take(10) 必须仍然给出同一个日期键。
            article("b", "2026-08-05T00:00+00:00"),
            article("c", "2026-08-04T22:45:09+00:00"),
        )
        val sections = readerDaySections(articles, mapOf("2026-08-05" to 24, "2026-08-04" to 31))

        assertEquals(listOf("2026-08-05", "2026-08-04"), sections.map { it.day })
        assertEquals(listOf(2, 1), sections.map { it.articles.size })
        // 分节头写的是那天的全量条数，不是窗口内渲染的条数。
        assertEquals(listOf(24, 31), sections.map { it.totalCount })
    }

    @Test
    fun fallsBackToWindowCountWhenAggregateIsMissing() {
        val articles = listOf(article("a", "2026-08-05T06:30+00:00"))
        val sections = readerDaySections(articles, emptyMap())
        // 宁可少报，也不显示 0。
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
        // 3 篇 + 2 个分节头 = 5 个 lazy item。分页判定用的 firstVisibleItemIndex
        // 也在这个空间里，两者必须一致，否则窗口会提前增长。
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
