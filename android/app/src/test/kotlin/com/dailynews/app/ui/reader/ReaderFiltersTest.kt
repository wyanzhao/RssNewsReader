package com.dailynews.app.ui.reader

import com.dailynews.data.db.FeedUnreadCount
import com.dailynews.data.repo.FeedRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderFiltersTest {
    @Test
    fun nextWindowGrowsNearTheEndAndCapsAtOneThousand() {
        assertEquals(100, nextWindow(100, visibleIndex = 10, itemCount = 300))
        assertEquals(200, nextWindow(100, visibleIndex = 285, itemCount = 300))
        assertEquals(1_000, nextWindow(950, visibleIndex = 990, itemCount = 1_000))
        assertEquals(1_000, nextWindow(1_000, visibleIndex = 1_000, itemCount = 2_000))
        assertEquals(100, nextWindow(100, visibleIndex = 0, itemCount = 0))
    }

    @Test
    fun emptyReasonCoversTheFiveBranches() {
        val all = ReaderFilter()
        val unread = ReaderFilter(unreadOnly = true)
        val scoped = ReaderFilter(feedName = "Ars Technica")
        val scopedUnread = ReaderFilter(feedName = "Ars Technica", unreadOnly = true)

        assertEquals("还没有订阅源，先去「订阅」页添加来源。", readerEmptyReason(all, poolCount = 5, feedCount = 0))
        assertEquals("文章池还是空的，等待下一次抓取或下拉刷新。", readerEmptyReason(all, poolCount = 0, feedCount = 3))
        assertEquals("这个来源暂无文章。", readerEmptyReason(scoped, poolCount = 40, feedCount = 3))
        assertEquals("这个来源的未读文章都看完了。", readerEmptyReason(scopedUnread, poolCount = 40, feedCount = 3))
        assertEquals("全部文章都读完了。取消「只看未读」可查看全部内容。", readerEmptyReason(unread, poolCount = 40, feedCount = 3))
        assertEquals("没有匹配的文章。", readerEmptyReason(all, poolCount = 40, feedCount = 3))
    }

    @Test
    fun feedChipModelsCarryUnreadCountsAndHealth() {
        val feeds = listOf(
            FeedRecord(1, "Alpha", "https://a", "block", true, 0, lastStatus = "ok"),
            FeedRecord(2, "Broken", "https://b", "block", true, 1, lastStatus = "error", lastError = "timeout"),
        )
        val counts = listOf(FeedUnreadCount("Alpha", 3))

        val chips = feedChipModels(feeds, counts)

        assertEquals(FeedChipModel("Alpha", 3, "ok", null), chips[0])
        assertEquals(FeedChipModel("Broken", 0, "ERROR", "timeout"), chips[1])
        assertEquals(3, totalUnread(counts))
    }
}
