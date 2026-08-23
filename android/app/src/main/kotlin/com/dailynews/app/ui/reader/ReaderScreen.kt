package com.dailynews.app.ui.reader

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.time.Instant
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.common.ConfirmDialog
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.ReadingColumn
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.common.SweepProgressCard
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.data.db.ReaderArticle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onSweep: () -> Unit = {},
    /**
     * Open an article: prefer in-app reading (the body excerpt is already local, so it
     * reads offline); the original in the browser is one tap away on that screen.
     * Passing null falls back to opening the browser directly, keeping the old
     * behavior available.
     */
    onOpenArticle: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchActive by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var confirmMarkAllRead by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val openLink: (String) -> Unit = { link ->
        onOpenArticle?.invoke(link) ?: CustomTabsIntent.Builder().build().launchUrl(context, link.toUri())
    }
    // Must be a count in lazy-item space (articles + section headers), because the
    // firstVisibleItemIndex it is compared against below lives in the same space.
    // Using the bare article count makes the window grow an extra 100 prematurely
    // roughly every 20 sections.
    val itemCount = state.searchResults?.size ?: readerLazyItemCount(state.sections)
    // Window growth is triggered only inside the stateful shell; scrolling itself never writes readAtUtc.
    LaunchedEffect(listState.firstVisibleItemIndex, itemCount) {
        val lastVisible = listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size
        viewModel.onVisibleItem(lastVisible, itemCount)
    }
    if (confirmMarkAllRead) {
        ConfirmDialog(
            title = "全部标为已读？",
            message = if (state.filter.feedName == null) "将把当前全部未读文章标为已读，可立即撤销。"
            else "将把「${state.filter.feedName}」的全部未读文章标为已读，可立即撤销。",
            confirmLabel = "标为已读",
            onConfirm = { confirmMarkAllRead = false; viewModel.markAllRead() },
            onDismiss = { confirmMarkAllRead = false },
        )
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("阅读")
                            Text("未读 ${state.totalUnread} 篇", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            searchActive = !searchActive
                            if (!searchActive) viewModel.onSearchQuery("")
                        }) { Text(if (searchActive) "收起搜索" else "搜索") }
                        Box {
                            TextButton(onClick = { overflowExpanded = true }) { Text("更多") }
                            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("全部标为已读…") },
                                    onClick = { overflowExpanded = false; confirmMarkAllRead = true },
                                )
                                if (state.canUndoMarkAllRead) {
                                    DropdownMenuItem(
                                        text = { Text("撤销全部已读") },
                                        onClick = { overflowExpanded = false; viewModel.undoMarkAllRead() },
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (searchActive) {
                    OutlinedTextField(
                        state.searchQuery,
                        { viewModel.onSearchQuery(it) },
                        label = { Text("按标题 / 英文摘要搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DailyNewsSpacing.roomy),
                    )
                }
                // Filter chips are pinned inside the topBar slot: reachable even after scrolling 30 days;
                // the horizontal LazyRow and the vertical LazyColumn have orthogonal axes, so they don't touch the nested-scroll red line.
                ReaderFilterChips(state, viewModel::selectFeed, viewModel::toggleUnreadOnly)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.sweepRefreshing,
            onRefresh = onSweep,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            ReaderContent(
                state = state,
                listState = listState,
                onOpen = { article -> viewModel.openArticle(article); openLink(article.link) },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleRead = viewModel::toggleRead,
                onShare = { text -> shareText(context, text) },
                onOpenLink = openLink,
            )
        }
    }
}

@Composable
internal fun ReaderFilterChips(
    state: ReaderUiState,
    onSelectFeed: (String?) -> Unit,
    onToggleUnread: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = DailyNewsSpacing.roomy, vertical = DailyNewsSpacing.compact),
        horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
    ) {
        item(key = "chip-unread") {
            FilterChip(selected = state.filter.unreadOnly, onClick = onToggleUnread, label = { Text("只看未读") })
        }
        item(key = "chip-all") {
            FilterChip(
                selected = state.filter.feedName == null,
                onClick = { onSelectFeed(null) },
                label = { Text("全部 ${state.poolCount}") },
            )
        }
        items(state.chips, key = { "chip-${it.feedName}" }) { chip ->
            FilterChip(
                selected = state.filter.feedName == chip.feedName,
                onClick = { onSelectFeed(chip.feedName) },
                label = {
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text("${chip.feedName} ${chip.unread}")
                    }
                },
                leadingIcon = if (chip.healthStatus == "ERROR" || chip.healthStatus == "STALE") {
                    { StatusBadge(chip.healthStatus, chip.healthDetail) }
                } else null,
            )
        }
    }
}

/**
 * Stateless content: called directly by screenshot/semantics tests.
 * Explicit three-state: LOADING / EMPTY / CONTENT — does not replicate Today's pitfall
 * where the initial state and the empty state are indistinguishable.
 */
/**
 * Sticky date header. The background must be **opaque across the full width**: if the
 * background were only on the 640dp inner layer, in the 840dp expanded state content
 * would show through the gaps on both sides of the sticky header.
 */
@Composable
private fun ReaderDayHeader(section: ReaderDaySection) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            "${section.label} · ${section.totalCount} 篇",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = DailyNewsSpacing.readingMaxWidth)
                .padding(vertical = DailyNewsSpacing.compact),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderContent(
    state: ReaderUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOpen: (ReaderArticle) -> Unit = {},
    onToggleFavorite: (ReaderArticle) -> Unit = {},
    onToggleRead: (ReaderArticle) -> Unit = {},
    onShare: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    // The "N days ago" on cards is a relative time. Screenshot tests must pin this
    // instant, otherwise the fixed-fixture copy drifts with the calendar and the
    // baseline goes red once a day.
    now: Instant = Instant.now(),
) {
    val searchResults = state.searchResults
    when {
        state.articles == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        searchResults != null -> LazyColumn(
            modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(DailyNewsSpacing.roomy),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(searchResults, key = { "search-${it.linkKey}" }) { entity ->
                ReadingColumn {
                    ArticleCard(
                        article = ArticleCardModel(
                            entity.link, entity.title, entity.feedName,
                            entity.pubDateUtc, entity.pubDateIso, entity.summaryEn,
                        ),
                        saved = entity.favoritedAtUtc != null,
                        read = entity.readAtUtc != null,
                        onOpen = { onOpenLink(entity.link) },
                        onToggleFavorite = {},
                        onShare = { onShare("${entity.title}\n${entity.link}") },
                        onOpenRelated = onOpenLink,
                        blankSummaryText = "无英文摘要",
                        now = now,
                    )
                }
            }
        }
        state.phase == ReaderPhase.EMPTY -> Box(modifier.fillMaxSize().padding(DailyNewsSpacing.roomy), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxWidth()) {
                EmptyState(title = "这里暂时没有内容", message = state.emptyReason)
                if (state.sweepRefreshing) {
                    Box(Modifier.padding(top = DailyNewsSpacing.regular)) {
                        SweepProgressCard(state.sweepProgressLabel.ifBlank { "正在启动抓取任务…" })
                    }
                }
            }
        }
        // The sectioned view does not set verticalArrangement.spacedBy: the sticky header
        // must sit flush against the content; a gap in between would let the cards below
        // show through the seam. Spacing is carried by each item itself instead.
        else -> LazyColumn(
            modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = DailyNewsSpacing.roomy, vertical = DailyNewsSpacing.compact),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.sections.forEach { section ->
                stickyHeader(key = "day-${section.day}") {
                    ReaderDayHeader(section)
                }
                items(section.articles, key = { it.linkKey }) { article ->
                    ReadingColumn(Modifier.padding(bottom = DailyNewsSpacing.regular)) {
                        ArticleCard(
                            article = article.toCardModel(),
                            saved = article.favoritedAtUtc != null,
                            read = article.readAtUtc != null,
                            onOpen = { onOpen(article) },
                            onToggleFavorite = { onToggleFavorite(article) },
                            onShare = { onShare("${article.title}\n${article.link}\n${article.summaryZh}") },
                            onOpenRelated = onOpenLink,
                            extraMenuItem = {
                                DropdownMenuItem(
                                    text = { Text(if (article.readAtUtc == null) "标记为已读" else "标记为未读") },
                                    onClick = { onToggleRead(article) },
                                )
                            },
                            now = now,
                        )
                    }
                }
            }
            state.windowNotice?.let { notice ->
                item(key = "window-cap") {
                    ReadingColumn {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = DailyNewsSpacing.regular),
                        )
                    }
                }
            }
        }
    }
}
