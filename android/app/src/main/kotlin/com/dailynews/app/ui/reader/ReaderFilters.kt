package com.dailynews.app.ui.reader

import com.dailynews.app.ui.common.feedDisplayStatus
import com.dailynews.data.db.FeedUnreadCount
import com.dailynews.data.db.ReaderArticle
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.data.repo.FeedRecord
import java.time.Instant

/** Reader filters: source (null = all) and "unread only". */
data class ReaderFilter(
    val feedName: String? = null,
    val unreadOnly: Boolean = false,
)

/** Explicit three-state: avoids the TodayViewModel pitfall where the initial state and the empty state are indistinguishable. */
enum class ReaderPhase { LOADING, EMPTY, CONTENT }

/** Feed chip model: unread count + health dot; the information doubles as navigation. */
data class FeedChipModel(
    val feedName: String,
    val unread: Int,
    val healthStatus: String,
    val healthDetail: String?,
)

internal const val READER_INITIAL_WINDOW = 100
internal const val READER_WINDOW_STEP = 100
internal const val READER_WINDOW_MAX = 1_000
internal const val READER_PREFETCH_AHEAD = 20

/**
 * Window growth without introducing Paging 3: += 100 once scrolling reaches the
 * [READER_PREFETCH_AHEAD]-th item from the end, capped at [READER_WINDOW_MAX].
 * Pure function, directly unit-testable.
 */
internal fun nextWindow(current: Int, visibleIndex: Int, itemCount: Int): Int {
    if (current >= READER_WINDOW_MAX) return current
    if (itemCount <= 0 || visibleIndex < itemCount - READER_PREFETCH_AHEAD) return current
    return (current + READER_WINDOW_STEP).coerceAtMost(READER_WINDOW_MAX)
}

/** One day's section. [totalCount] is the **full** article count for that day, independent of how many items are rendered inside the window. */
data class ReaderDaySection(
    val day: String,
    val label: String,
    val totalCount: Int,
    val articles: List<ReaderArticle>,
)

/**
 * Groups by UTC day. The grouping key `pubDateIso.take(10)` is byte-for-byte the same
 * definition as SQL's `substr(pubDateIso,1,10)` — both sides must use the same one,
 * otherwise the section header's count will not match the entries beneath it.
 *
 * The timeline itself is already sorted by pubDateIso descending, so a sequential
 * traversal keeps the day groups in order.
 */
internal fun readerDaySections(articles: List<ReaderArticle>, dayCounts: Map<String, Int>): List<ReaderDaySection> =
    articles.groupBy { it.pubDateIso.take(10) }
        .map { (day, items) ->
            // When the count is missing, fall back to the number of items in the window; better to under-report than to show 0.
            ReaderDaySection(day, readerDayLabel(day), dayCounts[day] ?: items.size, items)
        }

/** e.g. "08-05 Wednesday" (the actual UI string is in Chinese). When the date cannot be parsed, it is echoed back unchanged. */
internal fun readerDayLabel(day: String): String {
    val parsed = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return day
    val weekday = when (parsed.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "星期一"
        java.time.DayOfWeek.TUESDAY -> "星期二"
        java.time.DayOfWeek.WEDNESDAY -> "星期三"
        java.time.DayOfWeek.THURSDAY -> "星期四"
        java.time.DayOfWeek.FRIDAY -> "星期五"
        java.time.DayOfWeek.SATURDAY -> "星期六"
        java.time.DayOfWeek.SUNDAY -> "星期日"
    }
    return "${day.removePrefix("${parsed.year}-")} $weekday"
}

/**
 * The **item count** in the LazyColumn: articles + one item per section header.
 * The `listState.firstVisibleItemIndex` used for paging decisions lives in this same
 * space; the two must come from the same source, otherwise the window grows an extra
 * 100 prematurely roughly every 20 sections.
 */
internal fun readerLazyItemCount(sections: List<ReaderDaySection>): Int =
    sections.sumOf { it.articles.size } + sections.size

/** Terminal notice when the 1000 cap is reached; returns null when the cap is not hit. */
internal fun readerWindowCapNotice(window: Int, loaded: Int): String? =
    if (window >= READER_WINDOW_MAX && loaded >= READER_WINDOW_MAX) {
        "已加载最近 $READER_WINDOW_MAX 篇。更早的文章请用搜索或按来源筛选。"
    } else {
        null
    }

/**
 * The 5 branches of empty-state copy; a non-empty return value is exactly the
 * EmptyState copy to display. Branch order is the priority order: no feeds → empty
 * pool → no articles for this feed → everything read → fallback.
 */
internal fun readerEmptyReason(filter: ReaderFilter, poolCount: Int, feedCount: Int): String {
    if (feedCount == 0) return "还没有订阅源，先去「订阅」页添加来源。"
    if (poolCount == 0) return "文章池还是空的，等待下一次抓取或下拉刷新。"
    if (filter.feedName != null) {
        return if (filter.unreadOnly) "这个来源的未读文章都看完了。" else "这个来源暂无文章。"
    }
    if (filter.unreadOnly) return "全部文章都读完了。取消「只看未读」可查看全部内容。"
    return "没有匹配的文章。"
}

internal fun feedChipModels(
    feeds: List<FeedRecord>,
    unreadCounts: List<FeedUnreadCount>,
    now: Instant = Instant.now(),
): List<FeedChipModel> {
    val unreadByName = unreadCounts.associate { it.feedName to it.unread }
    return feeds.map { feed ->
        FeedChipModel(
            feedName = feed.name,
            unread = unreadByName[feed.name] ?: 0,
            healthStatus = feedDisplayStatus(feed, now),
            healthDetail = feed.lastError,
        )
    }
}

internal fun totalUnread(unreadCounts: List<FeedUnreadCount>): Int = unreadCounts.sumOf { it.unread }

/** Narrow projection → ArticleCardModel, zero structural change (saved/read are decided by timestamps). */
internal fun ReaderArticle.toCardModel() = ArticleCardModel(
    link = link,
    title = title,
    source = source,
    pubDateUtc = pubDateUtc,
    pubDateIso = pubDateIso,
    summaryZh = summaryZh,
)
