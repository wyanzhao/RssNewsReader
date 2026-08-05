package com.dailynews.app.ui.today

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ConfirmDialog
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.InfoCard
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.report.ReportViewModel
import com.dailynews.app.ui.report.reportContent
import com.dailynews.app.ui.theme.DailyNewsSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onRunNow: () -> Unit,
    onSweep: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReport: (String) -> Unit,
    reportViewModel: @Composable (String) -> ReportViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRun by remember { mutableStateOf(false) }
    var refreshAcknowledged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isRefreshing = refreshAcknowledged || state.sweepRefreshing
    val latest = state.latest
    val embeddedViewModel = latest?.takeIf { it.status == "SUCCESS" }?.let { reportViewModel(it.reportDate) }
    val embeddedState = embeddedViewModel?.state?.collectAsStateWithLifecycle()?.value
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    if (confirmRun) {
        ConfirmDialog(
            title = "立即生成今日报告？",
            message = "这会执行完整抓取、校验与 LLM 编辑流程，并消耗所配置 provider 的 token。",
            confirmLabel = "开始生成",
            onConfirm = { confirmRun = false; onRunNow() },
            onDismiss = { confirmRun = false },
        )
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text("文章池 ${state.poolCount} 篇 · 下次 ${state.nextScheduledAt}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = { TextButton(onClick = { confirmRun = true }) { Text(stringResource(R.string.run_now)) } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                refreshAcknowledged = true
                onSweep()
                // Keep the acknowledgement visible until WorkManager publishes
                // its queued/running state. The worker flow then owns the rest
                // of the indicator lifetime through success or failure.
                scope.launch {
                    delay(1_500)
                    refreshAcknowledged = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isRefreshing) {
                    item(key = "sweep-progress") {
                        SweepProgressCard(state.sweepProgressLabel.ifBlank { "正在启动抓取任务…" })
                    }
                }
                item(key = "today-status") {
                    TodayStatusCard(state, onOpenDiagnostics, onOpenReport)
                }
                if (!state.providerConfigured) {
                    item(key = "provider-missing") {
                        Column(Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth)) {
                            InfoCard(stringResource(R.string.provider_missing), stringResource(R.string.configure), onOpenSettings)
                        }
                    }
                }
                if (state.missedToday) {
                    item(key = "missed-today") {
                        Column(Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth)) {
                            InfoCard(stringResource(R.string.missed_today, state.scheduleTime), stringResource(R.string.run_makeup)) { confirmRun = true }
                        }
                    }
                }
                if (latest == null) {
                    item(key = "no-report") {
                        Column(Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth)) {
                            EmptyState(
                                title = "还没有报告",
                                message = "下次计划：${state.nextScheduledAt}。${if (state.providerConfigured) "也可以现在生成。" else "请先配置 provider，编辑分支会保持 fail closed。"}",
                                actionLabel = if (state.providerConfigured) "立即生成" else "配置 Provider",
                                onAction = if (state.providerConfigured) ({ confirmRun = true }) else onOpenSettings,
                            )
                        }
                    }
                } else if (latest.status != "SUCCESS") {
                    item(key = "failed-report") {
                        Card(
                            Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth).clickable { onOpenDiagnostics() },
                        ) {
                            Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                                Text("${latest.reportDate} 生成失败", style = MaterialTheme.typography.titleLarge)
                                Text(latest.failureReason ?: "打开诊断查看失败阶段与重试入口。")
                                TextButton(onClick = onOpenDiagnostics) { Text("查看诊断") }
                            }
                        }
                    }
                } else if (embeddedState != null) {
                    val reportVm = requireNotNull(embeddedViewModel)
                    reportContent(
                        state = embeddedState,
                        onToggleRaw = reportVm::toggleRaw,
                        onToggleGroup = reportVm::toggleGroup,
                        onMarkRead = reportVm::markRead,
                        onToggleFavorite = reportVm::toggleFavorite,
                        onOpen = { link -> CustomTabsIntent.Builder().build().launchUrl(context, link.toUri()) },
                        onShare = { text -> shareText(context, text) },
                        embedded = true,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SweepProgressCard(label: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .widthIn(max = DailyNewsSpacing.readingMaxWidth)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            Modifier.padding(DailyNewsSpacing.regular),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TodayStatusCard(
    state: TodayUiState,
    onOpenDiagnostics: () -> Unit,
    onOpenReport: (String) -> Unit,
) {
    val run = state.currentRun
    val failed = run?.status == "FAILED"
    Card(
        Modifier
            .fillMaxWidth()
            .widthIn(max = DailyNewsSpacing.readingMaxWidth)
            .then(if (failed) Modifier.clickable(onClick = onOpenDiagnostics) else Modifier),
    ) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Text("今日生成状态", style = MaterialTheme.typography.titleLarge)
            StatusBadge(run?.status ?: state.latest?.status ?: "EMPTY")
            when {
                run?.status == "RUNNING" -> {
                    Text("正在执行 ${run.classification.lowercase()} 流程")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.runSteps.takeLast(5).forEach { log -> Text("• ${log.step} · ${log.message}", style = MaterialTheme.typography.bodySmall) }
                }
                failed -> {
                    Text("${run.classification} · validator ${run.validatorExitCode}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenDiagnostics) { Text("打开诊断与重试") }
                }
                state.latest != null -> {
                    Text("最近报告：${state.latest.reportDate}")
                    TextButton(onClick = { onOpenReport(state.latest.reportDate) }) { Text("打开独立报告页") }
                }
                else -> Text("等待首次生成；下次计划 ${state.nextScheduledAt}")
            }
        }
    }
}
