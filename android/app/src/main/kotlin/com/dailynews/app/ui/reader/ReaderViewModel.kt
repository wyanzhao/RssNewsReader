package com.dailynews.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.dailynews.app.ui.common.SweepUiProgress
import com.dailynews.app.ui.common.sweepProgressFor
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.ReaderArticle
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.FavoriteRepository
import com.dailynews.data.repo.FeedEditorRepository
import com.dailynews.data.repo.FeedRecord
import com.dailynews.data.db.FeedUnreadCount
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReaderUiState(
    val phase: ReaderPhase = ReaderPhase.LOADING,
    // null = Room 首次发射之前；非 null 后才区分 EMPTY / CONTENT，显式锁死三态。
    val articles: List<ReaderArticle>? = null,
    val filter: ReaderFilter = ReaderFilter(),
    val chips: List<FeedChipModel> = emptyList(),
    val totalUnread: Int = 0,
    val poolCount: Int = 0,
    val sweepRefreshing: Boolean = false,
    val sweepProgressLabel: String = "",
    val searchQuery: String = "",
    val searchResults: List<ArticleEntity>? = null,
    val emptyReason: String = "",
    val canUndoMarkAllRead: Boolean = false,
)

private data class ReaderHeader(
    val unreadCounts: List<FeedUnreadCount>,
    val feeds: List<FeedRecord>,
    val poolCount: Int,
    val sweep: SweepUiProgress,
)

private data class ReaderSelection(
    val filter: ReaderFilter,
    val searchQuery: String,
    val searchResults: List<ArticleEntity>,
    val batchStamp: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val articles: ArticleRepository,
    feeds: FeedEditorRepository,
    private val favorites: FavoriteRepository,
    sweepWorkInfos: Flow<List<WorkInfo>> = flowOf(emptyList()),
) : ViewModel() {
    private val filter = MutableStateFlow(ReaderFilter())
    private val window = MutableStateFlow(READER_INITIAL_WINDOW)
    private val searchQuery = MutableStateFlow("")
    private val lastBatchStamp = MutableStateFlow<String?>(null)

    private val timeline = combine(filter, window) { currentFilter, limit -> currentFilter to limit }
        .flatMapLatest { (currentFilter, limit) ->
            articles.observeTimeline(currentFilter.feedName, currentFilter.unreadOnly, limit)
        }
    private val feedsFlow = feeds.observeAll()
    private val searchResults = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList()) else articles.search(query)
    }
    private val header = combine(
        articles.observeUnreadCounts(),
        feedsFlow,
        articles.observePoolCount(),
        sweepWorkInfos.map { infos -> sweepProgressFor(infos.map { it.state }) },
    ) { counts, feedRows, poolCount, sweep -> ReaderHeader(counts, feedRows, poolCount, sweep) }
    private val selection = combine(filter, searchQuery, searchResults, lastBatchStamp) { currentFilter, query, results, stamp ->
        ReaderSelection(currentFilter, query, results, stamp)
    }

    val state: StateFlow<ReaderUiState> = combine(timeline, selection, header) { rows, current, head ->
        val searching = current.searchQuery.isNotBlank()
        val hasContent = if (searching) current.searchResults.isNotEmpty() else rows.isNotEmpty()
        ReaderUiState(
            phase = if (hasContent) ReaderPhase.CONTENT else ReaderPhase.EMPTY,
            articles = rows,
            filter = current.filter,
            chips = feedChipModels(head.feeds, head.unreadCounts),
            totalUnread = totalUnread(head.unreadCounts),
            poolCount = head.poolCount,
            sweepRefreshing = head.sweep.active,
            sweepProgressLabel = head.sweep.label,
            searchQuery = current.searchQuery,
            searchResults = if (searching) current.searchResults else null,
            emptyReason = if (hasContent) "" else
                if (searching) "没有匹配的文章。" else readerEmptyReason(current.filter, head.poolCount, head.feeds.size),
            canUndoMarkAllRead = current.batchStamp != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    init {
        // 选中源被删除后回落到全部，避免永远卡在空时间线。
        viewModelScope.launch {
            feedsFlow.collect { rows ->
                val selected = filter.value.feedName
                if (selected != null && rows.none { it.name == selected }) selectFeed(null)
            }
        }
    }

    fun selectFeed(feedName: String?) {
        filter.value = filter.value.copy(feedName = feedName)
        window.value = READER_INITIAL_WINDOW
    }

    fun toggleUnreadOnly() {
        filter.value = filter.value.copy(unreadOnly = !filter.value.unreadOnly)
        window.value = READER_INITIAL_WINDOW
    }

    fun onSearchQuery(query: String) {
        searchQuery.value = query
    }

    // 只增长窗口，绝不写 readAtUtc——「只有打开原文才写已读」由 openArticle 独占。
    fun onVisibleItem(index: Int, itemCount: Int) {
        window.value = nextWindow(window.value, index, itemCount)
    }

    fun openArticle(article: ReaderArticle) {
        viewModelScope.launch { articles.markRead(article.link) }
    }

    fun toggleRead(article: ReaderArticle) {
        viewModelScope.launch {
            if (article.readAtUtc == null) articles.markRead(article.link) else articles.markUnread(article.linkKey)
        }
    }

    fun toggleFavorite(article: ReaderArticle) {
        viewModelScope.launch {
            if (article.favoritedAtUtc == null) favorites.save(article.link, article.title, article.source, article.summaryZh)
            else favorites.remove(article.link)
        }
    }

    /** 作用域 = 当前筛选；批次时间戳使撤销精确回滚，不误伤之后真正读过的文章。 */
    fun markAllRead() {
        val stamp = Instant.now().toString()
        lastBatchStamp.value = stamp
        val scopedFeed = filter.value.feedName
        viewModelScope.launch { articles.markAllRead(scopedFeed, stamp) }
    }

    fun undoMarkAllRead() {
        val stamp = lastBatchStamp.value ?: return
        lastBatchStamp.value = null
        viewModelScope.launch { articles.undoMarkAllRead(stamp) }
    }
}
