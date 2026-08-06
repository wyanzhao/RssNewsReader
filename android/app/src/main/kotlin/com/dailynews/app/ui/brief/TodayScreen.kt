package com.dailynews.app.ui.brief

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ConfirmDialog
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.InfoCard
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.common.SweepProgressCard
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
    onOpenDiagnostics: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReport: (String) -> Unit,
    reportViewModel: @Composable (String) -> ReportViewModel,
    onOpenHistory: () -> Unit = {},
    onOpenStory: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRun by remember { mutableStateOf(false) }
    var refreshAcknowledged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isRefreshing = refreshAcknowledged || state.sweepRefreshing
    val current = state.current
    val embeddedViewModel = current?.takeIf { it.status == "SUCCESS" }?.let { reportViewModel(it.reportDate) }
    val embeddedState = embeddedViewModel?.state?.collectAsStateWithLifecycle()?.value
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showDatePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 内嵌报告的 item key 是 p1-1..p1-N，跨日期会被复用，不重置会停在上一天的滚动位置。
    LaunchedEffect(state.effectiveDate) { listState.scrollToItem(0) }
    if (confirmRun) {
        ConfirmDialog(
            title = "立即生成今日报告？",
            message = "这会执行完整抓取、校验与 LLM 编辑流程，并消耗所配置 provider 的 token。",
            confirmLabel = "开始生成",
            onConfirm = { confirmRun = false; onRunNow() },
            onDismiss = { confirmRun = false },
        )
    }
    if (showDatePicker) {
        BriefDatePickerSheet(
            dates = state.availableDates,
            selected = state.effectiveDate,
            today = state.today,
            onPick = { date ->
                showDatePicker = false
                viewModel.selectDate(date)
            },
            onDismiss = { showDatePicker = false },
        )
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 日期步进条钉在 topBar slot 内的独立行，不放进 LargeTopAppBar 的 title：
            // title 在展开/折叠两态都会被渲染，塞进可交互控件会产生两个命中目标
            // 和两次 TalkBack 播报。形态与 ReaderScreen 的 chip 行一致。
            Column {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name))
                            Text("文章池 ${state.poolCount} 篇 · 下次 ${state.nextScheduledAt}", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        TextButton(onClick = { confirmRun = true }) { Text(stringResource(R.string.run_now)) }
                        IconButton(onClick = onOpenHistory) {
                            Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.history_title))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                BriefDateBar(
                    state = state,
                    onStep = viewModel::stepDate,
                    onPickDate = { showDatePicker = true },
                    onBackToToday = viewModel::backToToday,
                )
            }
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
                state = listState,
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
                if (current == null) {
                    item(key = "no-report") {
                        Column(Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth)) {
                            EmptyState(
                                title = briefEmptyTitle(state.isToday),
                                message = briefEmptyMessage(state.isToday, state.effectiveDate, state.nextScheduledAt, state.providerConfigured),
                                // 往期空白日给不出补跑，只能翻走：给个按钮只会让人白等。
                                actionLabel = when {
                                    !state.isToday -> "回到今天"
                                    state.providerConfigured -> "立即生成"
                                    else -> "配置 Provider"
                                },
                                onAction = when {
                                    !state.isToday -> viewModel::backToToday
                                    state.providerConfigured -> ({ confirmRun = true })
                                    else -> onOpenSettings
                                },
                            )
                        }
                    }
                } else if (current.status != "SUCCESS") {
                    item(key = "failed-report") {
                        Card(
                            Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth).clickable { onOpenDiagnostics(state.currentRun?.runId) },
                        ) {
                            Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                                Text("${current.reportDate} 生成失败", style = MaterialTheme.typography.titleLarge)
                                Text(current.failureReason ?: "打开诊断查看失败阶段与重试入口。")
                                TextButton(onClick = { onOpenDiagnostics(state.currentRun?.runId) }) { Text("查看诊断") }
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
                        onOpenDiagnostics = { onOpenDiagnostics(state.currentRun?.runId) },
                        onOpenStory = onOpenStory,
                    )
                }
            }
        }
    }
}

@Composable
private fun BriefDateBar(
    state: TodayUiState,
    onStep: (Int) -> Unit,
    onPickDate: () -> Unit,
    onBackToToday: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DailyNewsSpacing.compact, vertical = DailyNewsSpacing.compact / 2),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }, enabled = state.hasPrevious) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.brief_prev_report))
        }
        TextButton(onClick = onPickDate, modifier = Modifier.semantics { contentDescription = "选择报告日期" }) {
            Text(briefDateLabel(state.effectiveDate, state.today))
        }
        IconButton(onClick = { onStep(1) }, enabled = state.hasNext) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.brief_next_report))
        }
        if (!state.isToday) {
            TextButton(onClick = onBackToToday) { Text(stringResource(R.string.brief_back_to_today)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BriefDatePickerSheet(
    dates: List<String>,
    selected: String,
    today: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 用有报告的日期列表而不是 Material3 DatePicker：日历必然显示大量
    // 从未生成过报告的空白自然日，点进去只会得到空态。
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = DailyNewsSpacing.section),
        ) {
            item(key = "picker-title") {
                Text(
                    stringResource(R.string.brief_pick_date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = DailyNewsSpacing.roomy, vertical = DailyNewsSpacing.compact),
                )
            }
            items(dates, key = { it }) { date ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(date) }
                        .padding(horizontal = DailyNewsSpacing.roomy, vertical = DailyNewsSpacing.regular),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        briefDateLabel(date, today),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (date == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayStatusCard(
    state: TodayUiState,
    onOpenDiagnostics: (String?) -> Unit,
    onOpenReport: (String) -> Unit,
) {
    val run = state.currentRun
    val failed = run?.status == "FAILED"
    Card(
        Modifier
            .fillMaxWidth()
            .widthIn(max = DailyNewsSpacing.readingMaxWidth)
            .then(if (failed) Modifier.clickable { onOpenDiagnostics(run?.runId) } else Modifier),
    ) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Text(if (state.isToday) "今日生成状态" else "这一天的状态", style = MaterialTheme.typography.titleLarge)
            StatusBadge(run?.status ?: state.current?.status ?: "EMPTY")
            when {
                run?.status == "RUNNING" -> {
                    Text("正在执行 ${run.classification.lowercase()} 流程")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.runSteps.takeLast(5).forEach { log -> Text("• ${log.step} · ${log.message}", style = MaterialTheme.typography.bodySmall) }
                }
                failed -> {
                    Text("${run.classification} · validator ${run.validatorExitCode}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onOpenDiagnostics(run?.runId) }) { Text("打开诊断与重试") }
                }
                state.current != null -> {
                    Text("当前报告：${state.current.reportDate}")
                    TextButton(onClick = { onOpenReport(state.current.reportDate) }) { Text("打开独立报告页") }
                }
                else -> Text("等待首次生成；下次计划 ${state.nextScheduledAt}")
            }
        }
    }
}
