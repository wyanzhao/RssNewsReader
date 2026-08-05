package com.dailynews.app.ui.reader

import com.dailynews.app.ui.common.feedDisplayStatus
import com.dailynews.data.db.FeedUnreadCount
import com.dailynews.data.db.ReaderArticle
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.data.repo.FeedRecord
import java.time.Instant

/** 阅读器筛选：来源（null = 全部）与「只看未读」。 */
data class ReaderFilter(
    val feedName: String? = null,
    val unreadOnly: Boolean = false,
)

/** 显式三态：避免 TodayViewModel 那种初始态与空态不可区分的坑。 */
enum class ReaderPhase { LOADING, EMPTY, CONTENT }

/** 源 chip 模型：未读数 + 健康点，信息即导航。 */
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
 * 不引入 Paging 3 的窗口增长：滚到倒数第 [READER_PREFETCH_AHEAD] 项时 += 100，
 * 封顶 [READER_WINDOW_MAX]。纯函数可直接单测。
 */
internal fun nextWindow(current: Int, visibleIndex: Int, itemCount: Int): Int {
    if (current >= READER_WINDOW_MAX) return current
    if (itemCount <= 0 || visibleIndex < itemCount - READER_PREFETCH_AHEAD) return current
    return (current + READER_WINDOW_STEP).coerceAtMost(READER_WINDOW_MAX)
}

/**
 * 空态文案的 5 个分支；非空返回值即为应展示的 EmptyState 文案。
 * 分支顺序即优先级：无订阅源 → 池空 → 该源无文章 → 都读完了 → 兜底。
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

/** 窄投影 → ArticleCardModel，结构零改动（saved/read 由时间戳判定）。 */
internal fun ReaderArticle.toCardModel() = ArticleCardModel(
    link = link,
    title = title,
    source = source,
    pubDateUtc = pubDateUtc,
    pubDateIso = pubDateIso,
    summaryZh = summaryZh,
)
