package com.dailynews.app.ui.article

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import com.dailynews.model.ArtifactJson
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.relativeArticleTime
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.app.ui.theme.LocalDailyNewsColors

/**
 * In-app article reading.
 *
 * It exists for one reason: `articleText` has already been fetched, paid for, and
 * persisted, yet no UI consumed it. With this screen, offline you hold 30 Chinese
 * summaries **plus** each article's body excerpt, instead of 30 links that open a
 * browser offline-error page.
 *
 * Opening reads local text only. Explicit retrieval saves extracted web text for offline use.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ArticleDetailScreen(
    viewModel: ArticleDetailViewModel,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val snackbars = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    var readingOpen by rememberSaveable { mutableStateOf(false) }
    val article = state.article
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()
    val displayedBody = state.offlineBody?.text ?: article?.articleText.orEmpty()
    val bodyBlocks = remember(displayedBody) { displayedBody.chunked(4000) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val contentKey = remember(density.density, density.fontScale, width, article?.title, article?.summaryZh, displayedBody, state.offlineBody?.fetchedAtUtc, state.reading) {
        java.security.MessageDigest.getInstance("SHA-256").digest(
            listOf(article?.title, article?.summaryZh, displayedBody, state.offlineBody?.fetchedAtUtc, state.reading.toString(), density.density.toString(), density.fontScale.toString(), width.toString()).joinToString("\u0000").toByteArray()
        ).joinToString("") { "%02x".format(it) }
    }
    var restoredKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.loaded, contentKey) {
        if (state.loaded && article != null) {
            // A changed summary/body/layout invalidates pixel-based offsets.
            val anchor = com.dailynews.pipeline.text.restoreReadingPosition(article.readingContentKey, contentKey,
                article.readingIndex, article.readingOffset,
                4 + bodyBlocks.size.coerceAtLeast(1) + (if (article.summaryZh.isNotBlank()) 1 else 0))
            scroll.scrollToItem(anchor.first, anchor.second)
            restoredKey = contentKey
        }
    }
    LaunchedEffect(restoredKey) {
        if (restoredKey == contentKey && article != null) {
            snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().debounce(250).collect { (index, offset) -> viewModel.savePosition(index, offset, contentKey) }
        }
    }
    val leave: () -> Unit = {
        scope.launch {
            if (article != null && restoredKey == contentKey) viewModel.savePosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, contentKey)
            onBack()
        }
    }
    BackHandler(onBack = leave)
    LaunchedEffect(message) { message?.let { snackbars.showSnackbar(it); if (viewModel.message.value == it) viewModel.message.value = null } }
    if (notesOpen && article != null) AnnotationDialog(article.note,
        remember(article.tagsJson) { ArtifactJson.codec.decodeFromString<List<String>>(article.tagsJson) }, viewModel::saveAnnotations, { notesOpen = false })
    if (readingOpen) ReadingDialog(state.reading, viewModel::saveReading, { readingOpen = false })
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(state.article?.source.orEmpty()) },
                navigationIcon = { TextButton(onClick = leave) { Text(stringResource(R.string.back)) } },
                actions = {
                    state.article?.let { article ->
                        TextButton(onClick = { notesOpen = true }) { Text("笔记") }
                        TextButton(onClick = { readingOpen = true }) { Text("排版") }
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                painterResource(if (article.favoritedAtUtc != null) R.drawable.ic_favorite_filled else R.drawable.ic_favorite),
                                contentDescription = stringResource(
                                    if (article.favoritedAtUtc != null) R.string.remove_favorite else R.string.favorite,
                                ),
                                tint = if (article.favoritedAtUtc != null) {
                                    LocalDailyNewsColors.current.favorite
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val article = state.article
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            article == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("这篇文章已不在本地（可能已超出保留期）", style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = scroll,
                contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
            ) {
                item("header") {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
                    ) {
                        Text(article.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOf(article.source, relativeArticleTime(article.pubDateIso, article.pubDateUtc))
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (article.summaryZh.isNotBlank()) {
                    item("summary") {
                        Text(
                            article.summaryZh,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = state.reading.fontSizeSp.sp, lineHeight = (state.reading.fontSizeSp * state.reading.lineHeightPercent / 100f).sp),
                            modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        )
                    }
                }
                item("divider") { HorizontalDivider(Modifier.widthIn(max = DailyNewsSpacing.readingMaxWidth)) }
                item("body-status") {
                    Column(verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                        Text(state.offlineBody?.let {
                            "已离线保存网页正文 · ${it.fetchedAtUtc}\n" +
                                if (it.truncated) "正文超过保存上限，已截断；不保证文章完整。" else "自动提取可能缺少图片、付费内容或部分段落，不保证文章完整。"
                        } ?: "本地正文摘录，非全文。仅点击获取时联网并保存网页文字。", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = viewModel::fetchBody, enabled = !fetching) {
                            Text(if (fetching) "正在获取…" else if (state.offlineBody == null) "获取正文并离线保存" else "重新获取正文")
                        }
                        if (state.offlineBody != null) TextButton(onClick = viewModel::removeBody, enabled = !fetching) { Text("移除离线正文") }
                    }
                }
                if (bodyBlocks.isEmpty()) item("empty-body") { Text("本地没有正文摘录。可主动获取或在浏览器打开原文。") }
                items(bodyBlocks.size, key = { "body-$it" }) { index ->
                    Text(bodyBlocks[index],
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = state.reading.fontSizeSp.sp, lineHeight = (state.reading.fontSizeSp * state.reading.lineHeightPercent / 100f).sp),
                        modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth))
                }
                item("actions") {
                    Row(
                        Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
                    ) {
                        Button(onClick = { onOpenInBrowser(article.link) }) { Text("在浏览器打开") }
                        OutlinedButton(
                            onClick = { onShare("${article.title}\n${article.link}\n${article.summaryZh}") },
                        ) { Text("分享") }
                    }
                }
            }
        }
    }
}
