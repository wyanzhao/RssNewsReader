package com.dailynews.app.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ConfirmDialog
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.app.work.DailyReportWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    startupFailure: String?,
    onRunNow: (() -> Unit)? = null,
    onOpenFeeds: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val summaryCopiedMessage = stringResource(R.string.diagnostics_summary_copied)
    var overflowExpanded by remember { mutableStateOf(false) }
    // Stage 1 default: trigger the worker straight from the screen; stage 2 lets
    // AppNavHost inject navigation-aware callbacks instead.
    val runNow: () -> Unit = onRunNow ?: { DailyReportWorker.enqueue(context, scheduled = false) }

    var confirmComparison by rememberSaveable { mutableStateOf(false) }
    var comparisonPreference by rememberSaveable { mutableStateOf("优先 AI 基础设施、芯片与编译器，减少消费数码评测") }
    var confirmRecovery by rememberSaveable { mutableStateOf(false) }
    var confirmRun by rememberSaveable { mutableStateOf(false) }
    var runsExpanded by rememberSaveable { mutableStateOf(false) }
    var onlyFailedRuns by rememberSaveable { mutableStateOf(false) }
    var runsShowAll by rememberSaveable { mutableStateOf(false) }
    var showAllLogs by rememberSaveable { mutableStateOf(false) }
    var llmExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var healthySourcesExpanded by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            viewModel.export { writeZip ->
                context.contentResolver.openOutputStream(it)?.use { output -> writeZip(output) } != null
            }
        }
    }

    LaunchedEffect(state.events) {
        val event = state.events.firstOrNull() ?: return@LaunchedEffect
        val result = snackbars.showSnackbar(event.message, event.retryTag?.let { "重试" })
        if (result == SnackbarResult.ActionPerformed) {
            when (event.retryTag) {
                "export" -> state.selectedRunId?.let { exportLauncher.launch("dailynews-$it.zip") }
                "probe" -> viewModel.runNetworkDiagnostics()
            }
        }
        viewModel.consumeEvent(event.id)
    }

    if (confirmComparison) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmComparison = false },
            title = { Text("同池偏好对比") },
            text = {
                Column {
                    Text("使用本次文章快照重新生成两组结果，仅改变下方偏好；两组都关闭摘要缓存。会消耗模型用量，结果单独保存，可从运行诊断导出。")
                    androidx.compose.material3.OutlinedTextField(value = comparisonPreference, onValueChange = { comparisonPreference = it.take(1000) }, label = { Text("测试偏好") })
                }
            },
            confirmButton = { TextButton(enabled = comparisonPreference.isNotBlank(), onClick = {
                confirmComparison = false
                val run = state.detail ?: return@TextButton
                scope.launch {
                    val message = try {
                        if (com.dailynews.app.work.EditorialComparisonWorker.enqueue(context, run.runId, run.reportDate, comparisonPreference))
                            "对比试验已排队，完成后会通知；可在首页停止任务"
                        else "已有生成或对比任务，本次试验未启动"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { "对比试验提交失败" }
                    snackbars.showSnackbar(message)
                }
            }) { Text("开始对比") } },
            dismissButton = { TextButton(onClick = { confirmComparison = false }) { Text("取消") } },
        )
    }
    if (confirmRecovery) {
        ConfirmDialog(
            title = "从原始输入恢复？",
            message = "使用这次失败运行的文章快照，复用重新校验通过的编辑阶段。配置或模型变化会停止恢复；若要获取最新文章，请重新生成。",
            confirmLabel = "恢复运行",
            onConfirm = {
                confirmRecovery = false
                state.detail?.let { run ->
                    scope.launch {
                        val message = try {
                            if (DailyReportWorker.enqueueRecovery(context, run.runId, run.reportDate)) {
                                "恢复任务已提交，可在最近运行中查看进度"
                            } else {
                                "已有生成任务正在运行或排队，本次恢复未启动；请等待完成或先停止生成"
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            "恢复任务提交失败，请稍后重试"
                        }
                        snackbars.showSnackbar(message)
                    }
                }
            },
            onDismiss = { confirmRecovery = false },
        )
    }
    if (confirmRun) {
        ConfirmDialog(
            title = stringResource(R.string.diagnostics_rerun_title),
            message = stringResource(R.string.diagnostics_rerun_message),
            confirmLabel = stringResource(R.string.diagnostics_rerun_confirm),
            onConfirm = { confirmRun = false; runNow() },
            onDismiss = { confirmRun = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                actions = {
                    Box {
                        TextButton(onClick = { overflowExpanded = true }) { Text(stringResource(R.string.diagnostics_overflow)) }
                        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                            if (state.detail != null && state.detail?.status != "RUNNING") {
                                DropdownMenuItem(text = { Text("同池偏好对比") }, onClick = { overflowExpanded = false; confirmComparison = true })
                            }
                            if (state.detail?.status == "FAILED") {
                                DropdownMenuItem(
                                    text = { Text("从原始输入恢复") },
                                    onClick = { overflowExpanded = false; confirmRecovery = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_copy_summary)) },
                                onClick = {
                                    overflowExpanded = false
                                    val summary = buildDiagnosticsSummary(state)
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DailyNews 诊断摘要", summary)))
                                        snackbars.showSnackbar(summaryCopiedMessage)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_share_summary)) },
                                onClick = {
                                    overflowExpanded = false
                                    shareText(context, buildDiagnosticsSummary(state))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (showAllLogs) stringResource(R.string.diagnostics_collapse) else stringResource(R.string.diagnostics_show_all)) },
                                onClick = {
                                    overflowExpanded = false
                                    showAllLogs = !showAllLogs
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_artifacts)) },
                                onClick = {
                                    overflowExpanded = false
                                    state.selectedRunId?.let { exportLauncher.launch("dailynews-$it.zip") }
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(DailyNewsSpacing.roomy),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            diagnosticsContent(
                state = state,
                startupFailure = startupFailure,
                onSelectRun = viewModel::select,
                onRunNow = { confirmRun = true },
                onRunProbe = viewModel::runNetworkDiagnostics,
                onExport = { state.selectedRunId?.let { exportLauncher.launch("dailynews-$it.zip") } },
                onOpenFeeds = onOpenFeeds,
                onOpenSettings = onOpenSettings,
                runsExpanded = runsExpanded,
                onlyFailedRuns = onlyFailedRuns,
                runsShowAll = runsShowAll,
                showAllLogs = showAllLogs,
                llmExpanded = llmExpanded,
                advancedExpanded = advancedExpanded,
                healthySourcesExpanded = healthySourcesExpanded,
                onToggleRunsExpanded = { runsExpanded = !runsExpanded },
                onToggleOnlyFailed = { onlyFailedRuns = !onlyFailedRuns },
                onToggleRunsShowAll = { runsShowAll = !runsShowAll },
                onToggleShowAllLogs = { showAllLogs = !showAllLogs },
                onToggleLlmExpanded = { llmExpanded = !llmExpanded },
                onToggleAdvanced = { advancedExpanded = !advancedExpanded },
                onToggleHealthySources = { healthySourcesExpanded = !healthySourcesExpanded },
            )
        }
    }
}

/** Sources named by a "N stale feed(s): a, b" validator warning. */
internal fun staleSourcesFrom(warnings: List<String>): Set<String> =
    warnings.filter { "stale feed(s)" in it }
        .flatMap { it.substringAfter(": ").split(", ").map(String::trim) }
        .filter(String::isNotBlank)
        .toSet()

/** Default timeline noise reduction: WARN/ERROR plus the first and last entry. */
internal fun <T> reducedTimeline(logs: List<T>, isNotable: (T) -> Boolean): List<T> {
    if (logs.size <= 2) return logs
    return (listOfNotNull(logs.first()) + logs.filter(isNotable) + listOfNotNull(logs.last())).distinct()
}

private val abnormalClassifications = setOf("EXPECTED_BLOCK", "UNEXPECTED_ERROR", "INTERRUPTED")

/**
 * Whole-screen content as a LazyListScope extension so semantics and screenshot tests
 * can drive any fixture state without touching Room, mirroring reportContent.
 */
fun LazyListScope.diagnosticsContent(
    state: DiagnosticsUiState,
    startupFailure: String? = null,
    onSelectRun: (String) -> Unit = {},
    onRunNow: () -> Unit = {},
    onRunProbe: () -> Unit = {},
    onExport: () -> Unit = {},
    onOpenFeeds: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    runsExpanded: Boolean = false,
    onlyFailedRuns: Boolean = false,
    runsShowAll: Boolean = false,
    showAllLogs: Boolean = false,
    llmExpanded: Boolean = false,
    advancedExpanded: Boolean = false,
    healthySourcesExpanded: Boolean = false,
    onToggleRunsExpanded: () -> Unit = {},
    onToggleOnlyFailed: () -> Unit = {},
    onToggleRunsShowAll: () -> Unit = {},
    onToggleShowAllLogs: () -> Unit = {},
    onToggleLlmExpanded: () -> Unit = {},
    onToggleAdvanced: () -> Unit = {},
    onToggleHealthySources: () -> Unit = {},
) {
    // 0 — startup failure banner
    startupFailure?.takeIf(String::isNotBlank)?.let { failure ->
        item(key = "startup-failure") {
            Card(diagnosticsItemWidth, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("启动初始化失败：$failure", Modifier.padding(DailyNewsSpacing.roomy), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    // Loading skeleton: the verdict card only needs Room data, never disk IO.
    if (state.loading) {
        items(2, key = { "skeleton-$it" }) { LoadingSkeleton() }
        return
    }

    // Empty database
    if (state.runs.isEmpty() && state.selectedRunId == null) {
        item(key = "empty-state") {
            Column(diagnosticsItemWidth) {
                EmptyState(
                    title = "还没有运行记录",
                    message = stringResource(R.string.diagnostics_empty_message),
                    actionLabel = stringResource(R.string.run_now),
                    onAction = onRunNow,
                )
            }
        }
        return
    }

    // 1 — verdict card
    item(key = "verdict") {
        if (state.detail != null) {
            VerdictCard(
                detail = state.detail,
                advice = state.advice,
                blockingReasons = state.blockingReasons,
                stage = state.stage,
                onRunNow = onRunNow,
                onRunProbe = onRunProbe,
                onExport = onExport,
                onOpenFeeds = onOpenFeeds,
                onOpenSettings = onOpenSettings,
            )
        } else {
            Card(diagnosticsItemWidth) {
                Text(stringResource(R.string.diagnostics_artifacts_cleaned), Modifier.padding(DailyNewsSpacing.roomy))
            }
        }
    }

    val chainCosts = recoveryCostSummary(state)
    if (chainCosts.isNotEmpty()) {
        item(key = "recovery-chain-cost") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy)) {
                    chainCosts.forEach { Text(it) }
                }
            }
        }
    }

    // 2 — recent runs picker
    val filteredRuns = if (onlyFailedRuns) state.runs.filter { it.classification in abnormalClassifications } else state.runs
    collapsibleSection(
        keyPrefix = "runs",
        titleRes = R.string.diagnostics_recent_runs,
        expanded = runsExpanded,
        onToggle = onToggleRunsExpanded,
        subtitle = "${state.runs.size} 条记录",
    ) {
        item(key = "runs-filter") {
            Row(diagnosticsItemWidth) {
                FilterChip(selected = onlyFailedRuns, onClick = onToggleOnlyFailed, label = { Text(stringResource(R.string.diagnostics_only_failed)) })
            }
        }
        val visibleRuns = filteredRuns.take(if (runsShowAll) filteredRuns.size else 5)
        items(visibleRuns, key = { "run-${it.runId}" }) { run ->
            RunRow(run, selected = run.runId == state.selectedRunId, onSelect = { onSelectRun(run.runId) })
        }
        if (!runsShowAll && filteredRuns.size > 5) {
            item(key = "runs-show-all") {
                TextButton(onClick = onToggleRunsShowAll, modifier = diagnosticsItemWidth) {
                    Text("${stringResource(R.string.diagnostics_show_all_runs)}(${filteredRuns.size})")
                }
            }
        }
    }

    // 3 — quick stats
    state.counts?.let { counts ->
        item(key = "stats") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    Text(stringResource(R.string.diagnostics_stats), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "配置 ${counts.configured} · 正常 ${counts.ok} · 空 ${counts.empty} · 失败 ${counts.error} · 文章 ${counts.articles}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.warnings.forEach { warning ->
                        Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }

    // 4 — source health, from validation.json feed_results (per-run, not the global fetch_log)
    if (state.feedResults.isNotEmpty()) {
        val staleSources = staleSourcesFrom(state.warnings)
        val abnormal = state.feedResults.filter { it.status != "ok" || it.source in staleSources }
        val healthy = state.feedResults.size - abnormal.size
        item(key = "feeds-header") { SectionHeader(stringResource(R.string.diagnostics_source_health)) }
        items(abnormal, key = { "feed-${it.source}" }) { result ->
            FeedResultRow(result, stale = result.source in staleSources)
        }
        if (healthy > 0) {
            item(key = "feeds-healthy") {
                TextButton(onClick = onToggleHealthySources, modifier = diagnosticsItemWidth) {
                    Text("${if (healthySourcesExpanded) "▼" else "▶"} ${stringResource(R.string.diagnostics_healthy_sources, healthy)}")
                }
            }
            if (healthySourcesExpanded) {
                val healthyRows = state.feedResults.filter { it.status == "ok" && it.source !in staleSources }
                items(healthyRows, key = { "feed-healthy-${it.source}" }) { result -> FeedResultRow(result, stale = false) }
            }
        }
    }

    // 5 — step timeline
    if (state.logs.isNotEmpty()) {
        val notableCount = state.logs.count { it.level == "WARN" || it.level == "ERROR" }
        item(key = "timeline-header") {
            Column(diagnosticsItemWidth) {
                Text(stringResource(R.string.diagnostics_timeline), style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.logs.size} 条日志，其中 $notableCount 条警告/错误", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onToggleShowAllLogs) {
                        Text(if (showAllLogs) stringResource(R.string.diagnostics_collapse) else "${stringResource(R.string.diagnostics_show_all)}(${state.logs.size})")
                    }
                }
            }
        }
        val visibleLogs = if (showAllLogs) state.logs else reducedTimeline(state.logs) { it.level == "WARN" || it.level == "ERROR" }
        items(visibleLogs, key = { "log-${it.id}" }) { log -> TimelineRow(log) }
    }

    // 6 — network probes
    item(key = "probes-header") {
        Column(diagnosticsItemWidth, verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostics_probes), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = onRunProbe, enabled = !state.probing) {
                    Text(stringResource(if (state.probing) R.string.diagnosing else R.string.run_network_diagnostics))
                }
            }
            Text(stringResource(R.string.diagnostics_probe_limit_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.probing) {
        item(key = "probes-progress") { InlineProgress() }
    }
    if (state.probeSuggested) {
        item(key = "probes-suggested") {
            Card(diagnosticsItemWidth, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(stringResource(R.string.diagnostics_probe_suggested), Modifier.padding(DailyNewsSpacing.roomy), color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
    if (state.probes.isNotEmpty()) {
        val groups = state.probes.groupBy { it.target }
        groups["android"]?.let { rows ->
            item(key = "probes-android") { ProbeGroupCard("设备网络环境", rows) }
        }
        groups.filterKeys { it != "android" }.forEach { (target, rows) ->
            item(key = "probes-$target") { ProbeGroupCard(target, rows) }
        }
    }

    state.comparisons.forEachIndexed { index, comparison ->
        item(key = "comparison-$index") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy)) {
                    val label = when (comparison.status) {
                        "complete" -> "已完成"
                        "running" -> "进行中（尚无完整结果）"
                        "cancelled" -> "已取消"
                        "timeout" -> "已超时"
                        "failed", "incomplete" -> "未完成，请发起新试验"
                        else -> "状态无法读取"
                    }
                    Text("偏好对比：$label", style = MaterialTheme.typography.titleLarge)
                    Text(comparison.preference)
                    Text("通过菜单导出产物；只有已完成试验可用于两组对比。")
                }
            }
        }
    }
    if (state.shortlistAuditError) {
        item(key = "shortlist-audit-error") { Text("选题排除清单无法读取，请导出产物检查", color = MaterialTheme.colorScheme.error) }
    }
    state.shortlistAudit?.takeIf { it.excluded.isNotEmpty() }?.let { audit ->
        item(key = "shortlist-audit-heading") { Text("选题说明：入选 ${audit.links.size} 篇，排除 ${audit.excluded.size} 篇", style = MaterialTheme.typography.titleLarge) }
        items(audit.excluded.size, key = { "shortlist-excluded-$it" }) { index ->
            val excluded = audit.excluded[index]
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy)) {
                    Text(excluded.reason)
                    Text(excluded.link, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (state.finalPlanError) {
        item(key = "final-plan-error") { Text("最终选题清单无法读取，请导出产物检查", color = MaterialTheme.colorScheme.error) }
    }
    state.finalPlan?.takeIf { it.excluded.isNotEmpty() }?.let { plan ->
        item(key = "final-excluded-heading") { Text("最终取舍：${plan.excluded.size} 篇入围文章未采用", style = MaterialTheme.typography.titleLarge) }
        items(plan.excluded.size, key = { "final-excluded-$it" }) { index ->
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy)) {
                    Text(plan.excluded[index].reason)
                    Text(plan.excluded[index].link, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val measurements = llmMeasurementSummary(state)
    if (measurements.isNotEmpty()) {
        item(key = "llm-measurements") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    Text("生成质量与费用", style = MaterialTheme.typography.titleLarge)
                    measurements.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    // 7 — LLM calls
    val timings = stageTimingsFor(state.logs)
    if (timings.isNotEmpty()) {
        item(key = "stage-timings") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    Text("阶段耗时", style = MaterialTheme.typography.titleLarge)
                    Text("编辑全流程包含模型调用；模型耗时包含内部重试。", style = MaterialTheme.typography.bodySmall)
                    timings.forEach { Text(stageTimingText(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    if (state.llmCalls.isNotEmpty()) {
        val totals = state.llmTotals
        collapsibleSection(
            keyPrefix = "llm",
            titleRes = R.string.llm_calls,
            expanded = llmExpanded,
            onToggle = onToggleLlmExpanded,
            subtitle = "${totals.calls} 次 · ${llmUsageText(state)} · ${totals.failed} 次失败",
        ) {
            items(state.llmCalls, key = { "llm-${it.id}" }) { call -> LlmCallRow(call) }
        }
    }

    // 8 — context budget, only when it actually blocked or violated something
    state.budget?.takeIf { !it.withinBudget || it.violations.isNotEmpty() }?.let { budget ->
        item(key = "budget") {
            Card(diagnosticsItemWidth) {
                Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    Text(stringResource(R.string.diagnostics_budget), style = MaterialTheme.typography.titleLarge)
                    budget.violations.forEach { violation ->
                        Text("超出：${violation.size} ${violation.actual} > ${violation.limit}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "llm_context ${budget.sizes.llmContextBytes}/${budget.limits.llmContextMaxBytes} B · " +
                            "part1 ${budget.sizes.part1BriefBytes}/${budget.limits.part1BriefMaxBytes} B · " +
                            "part2 ${budget.sizes.part2ContextBytes}/${budget.limits.part2ContextMaxBytes} B",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 9 — advanced / raw artifacts
    collapsibleSection(
        keyPrefix = "advanced",
        titleRes = R.string.diagnostics_advanced,
        expanded = advancedExpanded,
        onToggle = onToggleAdvanced,
    ) {
        if (state.artifactsLoading) {
            item(key = "advanced-loading") { InlineProgress() }
        } else {
            // Contract violations go first: once this run has been sent back, these are the only things
            // that explain what went wrong. Previously they existed only inside the export ZIP, so troubleshooting required leaving the phone.
            state.contractViolations.forEach { (name, body) ->
                item(key = "advanced-violation-$name") {
                    RawJsonBlock(name.removePrefix("contract_violations/"), ArtifactPayload(raw = body, status = ArtifactStatus.PARSED))
                }
            }
            if (state.validationArtifact.raw != null) {
                item(key = "advanced-validation") { RawJsonBlock("validation.json", state.validationArtifact) }
            }
            if (state.budgetArtifact.raw != null) {
                item(key = "advanced-budget") { RawJsonBlock("context_budget.json", state.budgetArtifact) }
            }
            if (state.validationArtifact.raw == null && state.budgetArtifact.raw == null && state.contractViolations.isEmpty()) {
                item(key = "advanced-missing") {
                    Text("产物不存在或已按保留期清理", diagnosticsItemWidth, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "advanced-export") {
                OutlinedButton(onClick = onExport, enabled = state.selectedRunId != null, modifier = diagnosticsItemWidth.padding(vertical = DailyNewsSpacing.compact)) {
                    Text(stringResource(R.string.export_artifacts))
                }
            }
        }
    }
}
