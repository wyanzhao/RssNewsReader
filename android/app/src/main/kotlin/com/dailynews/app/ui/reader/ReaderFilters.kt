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

/** 一天的分节。[totalCount] 是那天的**全量**篇数，与窗口内渲染了几条无关。 */
data class ReaderDaySection(
    val day: String,
    val label: String,
    val totalCount: Int,
    val articles: List<ReaderArticle>,
)

/**
 * 按 UTC 日分组。分组键 `pubDateIso.take(10)` 与 SQL 的 `substr(pubDateIso,1,10)`
 * 逐字节同源——两边必须用同一个定义，否则分节头的计数会对不上它下面的条目。
 *
 * 时间线本身已按 pubDateIso 降序，所以顺序遍历即可保持日期分组有序。
 */
internal fun readerDaySections(articles: List<ReaderArticle>, dayCounts: Map<String, Int>): List<ReaderDaySection> =
    articles.groupBy { it.pubDateIso.take(10) }
        .map { (day, items) ->
            // 计数缺失时退回窗口内条数，宁可少报也不显示 0。
            ReaderDaySection(day, readerDayLabel(day), dayCounts[day] ?: items.size, items)
        }

/** `08-05 星期三`。日期不可解析时原样回显。 */
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
 * LazyColumn 里的**项数**：文章 + 每个分节头各占一项。
 * 分页判定用的 `listState.firstVisibleItemIndex` 也在这个空间里，两者必须同源，
 * 否则窗口会每隔约 20 个分节就提前多涨一次 100。
 */
internal fun readerLazyItemCount(sections: List<ReaderDaySection>): Int =
    sections.sumOf { it.articles.size } + sections.size

/** 触顶 1000 时的终止提示；未触顶返回 null。 */
internal fun readerWindowCapNotice(window: Int, loaded: Int): String? =
    if (window >= READER_WINDOW_MAX && loaded >= READER_WINDOW_MAX) {
        "已加载最近 $READER_WINDOW_MAX 篇。更早的文章请用搜索或按来源筛选。"
    } else {
        null
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
