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
     * 打开文章：优先进应用内阅读（本地已有正文摘录，离线也能读），浏览器原文
     * 在那一屏一键可达。传 null 时退回直接开浏览器，保持旧行为可用。
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
    // 必须是 lazy-item 空间的计数（文章 + 分节头），因为下面拿来比较的
    // firstVisibleItemIndex 也在这个空间里。用纯文章数会让窗口每隔约 20 个
    // 分节就提前多涨一次 100。
    val itemCount = state.searchResults?.size ?: readerLazyItemCount(state.sections)
    // 窗口增长只在有状态壳里触发；滚动本身绝不写 readAtUtc。
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
                // 筛选 chip 钉在 topBar slot 内：滚 30 天也够得着；
                // 横向 LazyRow 与纵向 LazyColumn 轴向正交，不触碰嵌套滚动红线。
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
 * 无状态内容：供截图/语义测试直接调用。
 * 三态显式：LOADING / EMPTY / CONTENT，不复制 Today 初始态与空态不可区分的坑。
 */
/**
 * 吸顶日期头。背景必须**整宽不透明**：只给 640dp 的内层加背景，
 * 840dp 展开态下内容会从两侧的空隙里穿过吸顶头。
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
    // 卡片上的「N 天前」是相对时间。截图测试必须钉住这个时刻，
    // 否则固定 fixture 的文案会随日历自己漂，基线每过一天就红一次。
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
        // 分节视图不设 verticalArrangement.spacedBy：吸顶头必须紧贴内容，
        // 中间留空隙会让下方卡片从缝里透出来。间距改由各项自己带。
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
