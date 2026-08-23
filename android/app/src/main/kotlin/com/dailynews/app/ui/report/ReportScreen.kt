package com.dailynews.app.ui.report

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ReportGroup
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    date: String,
    viewModel: ReportViewModel,
    onOpenStory: ((String) -> Unit)? = null,
    onOpenArticle: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var overflowExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val part1 = state.items.filter { it.part == 1 }
    val visibleRank by remember(part1) {
        derivedStateOf {
            val key = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key.toString().startsWith("p1-") }?.key?.toString()
            key?.removePrefix("p1-")?.toIntOrNull()?.coerceIn(1, part1.size.coerceAtLeast(1)) ?: if (listState.firstVisibleItemIndex < 3) 1 else part1.size
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(date)
                        if (part1.isNotEmpty()) Text("$visibleRank / ${part1.size}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    TextButton(onClick = { shareText(context, state.report?.topNMarkdown.orEmpty()) }) { Text("分享 Top ${part1.size}") }
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { overflowExpanded = true }) { Text("更多") }
                        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.showRaw) "结构化阅读" else "原始 Markdown") },
                                onClick = { overflowExpanded = false; viewModel.toggleRaw() },
                            )
                            DropdownMenuItem(
                                text = { Text("分享完整报告") },
                                onClick = { overflowExpanded = false; shareText(context, state.report?.markdown.orEmpty()) },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ReportPane(
            viewModel,
            Modifier.padding(padding),
            state = state,
            listState = listState,
            onOpenStory = onOpenStory,
            onOpenArticle = onOpenArticle,
        )
    }
}

@Composable
fun ReportPane(
    viewModel: ReportViewModel,
    modifier: Modifier = Modifier,
    state: ReportUiState? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onOpenStory: ((String) -> Unit)? = null,
    onOpenArticle: ((String) -> Unit)? = null,
) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val current = state ?: observed
    val context = LocalContext.current
    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(DailyNewsSpacing.roomy),
        verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        reportContent(
            state = current,
            onToggleRaw = viewModel::toggleRaw,
            onToggleGroup = viewModel::toggleGroup,
            onMarkRead = viewModel::markRead,
            onToggleFavorite = viewModel::toggleFavorite,
            onOpen = { link ->
                onOpenArticle?.invoke(link) ?: CustomTabsIntent.Builder().build().launchUrl(context, link.toUri())
            },
            onShare = { text -> shareText(context, text) },
            onOpenStory = onOpenStory,
        )
    }
}

fun LazyListScope.reportContent(
    state: ReportUiState,
    onToggleRaw: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onMarkRead: (String) -> Unit,
    onToggleFavorite: (ReportItemEntity) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    embedded: Boolean = false,
    onOpenDiagnostics: (() -> Unit)? = null,
    /** null = the host does not wire up story history (e.g. screenshot fixtures); in that case no entry point is shown. */
    onOpenStory: ((String) -> Unit)? = null,
) {
    val entries = state.items
    val groups = state.groups
    // Room has not emitted its first frame yet: any stats would read as 0, rendering a fake empty report.
    if (!state.loaded) {
        item(key = if (embedded) "embedded-loading" else "report-loading") {
            Box(Modifier.fillMaxWidth().padding(DailyNewsSpacing.roomy), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    // Loaded but there is no report for this day — old notifications, stale widgets, and dates cleared by retention all land here.
    // Before this branch existed, users would be stuck forever on the "UNKNOWN · Top 0" skeleton.
    if (state.report == null) {
        item(key = if (embedded) "embedded-absent" else "report-absent") {
            Column(
                Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth).padding(DailyNewsSpacing.roomy),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
            ) {
                Text("这一天没有报告", style = MaterialTheme.typography.titleMedium)
                Text(
                    "可能还没生成，也可能已超出保留期被清理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    item(key = if (embedded) "embedded-status" else "report-status") {
        Column(
            Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
        ) {
            StatusBadge(state.report?.status ?: "UNKNOWN")
            Text(
                if (PART2_SECTION_ENABLED) "${entries.count { it.part == 2 }} 篇 · ${groups.size} 个来源"
                else "Top ${entries.count { it.part == 1 }} · ${groups.size} 个来源",
                style = MaterialTheme.typography.titleMedium,
            )
            state.report?.failureReason?.takeIf(String::isNotBlank)?.let {
                Text("审校未通过：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val errors = groups.filter { it.status == "error" }
            if (errors.isNotEmpty()) Text("抓取异常：${errors.joinToString { it.source }}", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                TextButton(onClick = { onShare(state.report?.topNMarkdown.orEmpty()) }) { Text("分享 Top N") }
                TextButton(onClick = { onShare(state.report?.markdown.orEmpty()) }) { Text("分享完整报告") }
                if (embedded) TextButton(onClick = onToggleRaw) { Text(if (state.showRaw) "结构化" else "原始 Markdown") }
            }
        }
    }
    if (state.showRaw) {
        item(key = if (embedded) "embedded-raw" else "report-raw") {
            Text(state.report?.markdown ?: "报告不存在", Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth))
        }
        return
    }

    val part1 = entries.filter { it.part == 1 }
    item(key = if (embedded) "embedded-part1-title" else "part1-title") {
        Text("Part 1 · Top ${part1.size}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth))
    }
    items(part1, key = { "p1-${it.position}" }) { article ->
        ReportArticleCard(
            item = article,
            rank = article.position,
            saved = article.link in state.savedLinks,
            read = article.link in state.readLinks,
            onOpen = { onMarkRead(article.link); onOpen(article.link) },
            onToggleFavorite = { onToggleFavorite(article) },
            onShare = { onShare(articleShareText(article)) },
            onOpenRelated = onOpen,
            // Only offer the entry point when this story actually spans >= 2 days: a "history"
            // containing only its own single article is an empty promise. Depth is aggregated from report_items.
            onOpenStory = onOpenStory?.takeIf { (state.storyDepth[article.eventKey] ?: 0) >= 2 },
            storyDays = state.storyDepth[article.eventKey],
        )
    }
    if (PART2_SECTION_ENABLED) {
        part2Section(
            state = state,
            onToggleGroup = onToggleGroup,
            onMarkRead = onMarkRead,
            onToggleFavorite = onToggleFavorite,
            onOpen = onOpen,
            onShare = onShare,
            embedded = embedded,
        )
    } else {
        sourceHealthItem(state.groups, embedded, onOpenDiagnostics)
    }
    item(key = if (embedded) "embedded-stats" else "report-stats") {
        val statsText = if (PART2_SECTION_ENABLED) {
            val rosterSize = groups.ifEmpty { entries.filter { it.part == 2 }.groupBy { it.source }.map { (source, sourceItems) -> ReportGroup(source, "ok", sourceItems.size) } }.size
            "统计检查：Part 2 ${entries.count { it.part == 2 }} 篇；来源组 $rosterSize。"
        } else {
            "统计检查：Top ${part1.size} 篇；来源 ${groups.size} 个。"
        }
        Text(
            statsText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
        )
    }
}

/**
 * Part 2 (grouped by source) display section. Disabled by the [PART2_SECTION_ENABLED]
 * gate since Epic U; the function body is kept and invoked directly by
 * ReportSemanticsTest so the collapsed semantics can be restored at any time.
 */
internal fun LazyListScope.part2Section(
    state: ReportUiState,
    onToggleGroup: (String) -> Unit,
    onMarkRead: (String) -> Unit,
    onToggleFavorite: (ReportItemEntity) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    embedded: Boolean = false,
) {
    val entries = state.items
    val groups = state.groups
    item(key = if (embedded) "embedded-part2-title" else "part2-title") {
        Text("Part 2 · 按来源", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth))
    }
    val part2BySource = entries.filter { it.part == 2 }.groupBy { it.source }
    val roster = groups.ifEmpty { part2BySource.map { (source, sourceItems) -> ReportGroup(source, "ok", sourceItems.size) } }
    roster.forEach { group ->
        val sourceItems = part2BySource[group.source].orEmpty()
        val expanded = group.source in state.expandedSources
        val generating = group.source in state.generatingSources
        item(key = "source-${group.source}") {
            Card(
                Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth).clickable { onToggleGroup(group.source) },
            ) {
                Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    Text("${if (expanded) "▼" else "▶"} ${group.source} · ${group.articleCount} 篇", style = MaterialTheme.typography.titleLarge)
                    StatusBadge(group.status, group.errorText)
                    if (group.status == "error") Text(group.errorText ?: "抓取失败", color = MaterialTheme.colorScheme.error)
                    if (generating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在生成这个来源的中文摘要…", style = MaterialTheme.typography.bodySmall)
                    }
                    state.groupErrors[group.source]?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("摘要生成失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onToggleGroup(group.source); onToggleGroup(group.source) }) { Text("重试") }
                        }
                    }
                }
            }
        }
        if (expanded) {
            items(sourceItems, key = { "p2-${it.link}" }) { article ->
                ReportArticleCard(
                    item = article,
                    saved = article.link in state.savedLinks,
                    read = article.link in state.readLinks,
                    onOpen = { onMarkRead(article.link); onOpen(article.link) },
                    onToggleFavorite = { onToggleFavorite(article) },
                    onShare = { onShare(articleShareText(article)) },
                    onOpenRelated = onOpen,
                    generatingSummary = generating,
                )
            }
        }
    }
}

/**
 * The "Source Health" card on the report page. Its data source is reports.groupsJson
 * ([ReportUiState.groups]), semantically a snapshot taken "at this report's fetch time";
 * that differs from the "most recent fetch" feedDisplayStatus semantics on the reader
 * page's chip, so the copy must explicitly distinguish the two.
 */
internal fun LazyListScope.sourceHealthItem(
    groups: List<ReportGroup>,
    embedded: Boolean,
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    if (groups.isEmpty()) return
    val errors = groups.filter { it.status == "error" }
    item(key = if (embedded) "embedded-source-health" else "source-health") {
        Card(Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth)) {
            Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                Text("来源健康（本次报告抓取时）", style = MaterialTheme.typography.titleLarge)
                if (errors.isEmpty()) {
                    Text("全部 ${groups.size} 个来源抓取正常。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    errors.forEach { group ->
                        Text("${group.source}：${group.errorText ?: "抓取失败"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    onOpenDiagnostics?.let { open ->
                        TextButton(onClick = open) { Text("打开诊断") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportArticleCard(
    item: ReportItemEntity,
    saved: Boolean,
    read: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onOpenRelated: (String) -> Unit,
    rank: Int? = null,
    generatingSummary: Boolean = false,
    onOpenStory: ((String) -> Unit)? = null,
    storyDays: Int? = null,
) {
    val alsoLinks = remember(item.alsoLinksJson) {
        runCatching { ArtifactJson.codec.decodeFromString<List<String>>(item.alsoLinksJson) }.getOrDefault(emptyList())
    }
    ArticleCard(
        article = ArticleCardModel(
            item.link, item.title, item.source, item.pubDateUtc, item.pubDateIso, item.summaryZh,
            rank, alsoLinks, storyDays,
        ),
        saved = saved,
        read = read,
        onOpen = onOpen,
        onToggleFavorite = onToggleFavorite,
        onShare = onShare,
        onOpenRelated = onOpenRelated,
        generatingSummary = generatingSummary,
        extraMenuItem = onOpenStory?.let { open ->
            {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.story_open)) },
                    onClick = { open(item.eventKey) },
                )
            }
        },
        onOpenStory = onOpenStory?.let { open -> { open(item.eventKey) } },
    )
}

internal fun articleShareText(item: ReportItemEntity): String = "${item.title}\n${item.link}\n${item.summaryZh}"
