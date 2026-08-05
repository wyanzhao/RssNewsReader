package com.dailynews.app.ui.feeds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ConfirmDialog
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.common.feedDisplayStatus
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.data.repo.FeedRecord
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun FeedsScreen(viewModel: FeedsViewModel, expanded: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paneNavigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<FeedRecord?>(null) }
    pendingDelete?.let { feed ->
        ConfirmDialog(
            title = "删除 ${feed.name}？",
            message = "订阅源会从后续抓取中移除；已保存的历史报告与收藏不会删除。",
            confirmLabel = "删除",
            onConfirm = {
                pendingDelete = null
                viewModel.delete(feed.id)
                scope.launch {
                    if (snackbars.showSnackbar("已删除 ${feed.name}", "撤销") == SnackbarResult.ActionPerformed) viewModel.restore(feed)
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importOpml { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } } }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let {
            viewModel.exportOpml { content ->
                context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(content) }
                    ?: error("无法写入所选文件")
            }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.feeds_title)) }) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        val editor: @Composable () -> Unit = {
            FeedEditor(
                state,
                viewModel,
                onImport = { importLauncher.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                onExport = { exportLauncher.launch("dailynews-feeds.opml") },
            )
        }
        if (expanded) {
            ListDetailPaneScaffold(
                directive = paneNavigator.scaffoldDirective,
                value = paneNavigator.scaffoldValue,
                modifier = Modifier.fillMaxSize().padding(padding),
                listPane = {
                    AnimatedPane {
                        FeedList(state.feeds, Modifier.fillMaxSize(), viewModel::move, { pendingDelete = it }) { feed ->
                            viewModel.edit(feed)
                            scope.launch { paneNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, feed.id) }
                        }
                    }
                },
                detailPane = { AnimatedPane { Box(Modifier.fillMaxSize()) { editor() } } },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(DailyNewsSpacing.regular),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
            ) {
                item { editor() }
                items(state.feeds, key = FeedRecord::id) { feed ->
                    FeedRow(feed, { viewModel.edit(feed) }, { pendingDelete = feed }) { direction -> viewModel.move(feed.id, direction) }
                }
            }
        }
    }
}

@Composable
private fun FeedEditor(state: FeedsUiState, viewModel: FeedsViewModel, onImport: () -> Unit, onExport: () -> Unit) {
    val editor = state.editor
    val urlError = feedUrlError(editor.url)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Text(if (editor.editingId == null) "新增订阅源" else "编辑订阅源", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            OutlinedTextField(editor.name, viewModel::setName, label = { Text(stringResource(R.string.feed_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                editor.url,
                viewModel::setUrl,
                label = { Text(stringResource(R.string.feed_url)) },
                isError = editor.url.isNotBlank() && urlError != null,
                supportingText = { if (editor.url.isNotBlank() && urlError != null) Text(urlError) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row { Checkbox(editor.warnOnly, viewModel::setWarnOnly); Text(stringResource(R.string.feed_warn_policy), Modifier.padding(top = 12.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                Button(onClick = viewModel::save, enabled = !state.busy && editor.name.isNotBlank() && urlError == null) {
                    Text(stringResource(if (editor.editingId == null) R.string.feed_add else R.string.save_changes))
                }
                if (editor.editingId != null) OutlinedButton(onClick = viewModel::cancelEdit) { Text(stringResource(R.string.cancel_edit)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                OutlinedButton(onClick = onImport, enabled = !state.busy) { Text(stringResource(R.string.import_opml)) }
                OutlinedButton(onClick = onExport, enabled = !state.busy) { Text(stringResource(R.string.export_opml)) }
            }
            state.transferMessage?.let { Text(it) }
        }
    }
}

@Composable
private fun FeedList(
    feeds: List<FeedRecord>,
    modifier: Modifier,
    onMove: (Long, Int) -> Unit,
    onDelete: (FeedRecord) -> Unit,
    onEdit: (FeedRecord) -> Unit,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(DailyNewsSpacing.regular)) {
        items(feeds, key = FeedRecord::id) { feed -> FeedRow(feed, { onEdit(feed) }, { onDelete(feed) }) { onMove(feed.id, it) } }
    }
}

@Composable
private fun FeedRow(feed: FeedRecord, onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    var dragY by remember(feed.id) { mutableFloatStateOf(0f) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "${feed.name}，长按并上下拖动可排序" }
            .pointerInput(feed.id) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = { dragY = 0f },
                    onDragCancel = { dragY = 0f },
                ) { change, amount ->
                    change.consume()
                    dragY += amount.y
                    if (kotlin.math.abs(dragY) >= 48.dp.toPx()) {
                        onMove(if (dragY > 0) 1 else -1)
                        dragY = 0f
                    }
                }
            },
    ) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(feed.name, fontWeight = FontWeight.Bold)
            StatusBadge(feedDisplayStatus(feed), feed.lastError)
            Text(feed.url, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Text(if (feed.errorPolicy == "warn") "失败仅警告" else "失败按标准策略处理", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
