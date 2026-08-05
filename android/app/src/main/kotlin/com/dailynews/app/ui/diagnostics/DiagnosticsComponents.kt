package com.dailynews.app.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.layout.layout
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dailynews.app.R
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.RunSummary
import com.dailynews.model.FeedResult
import com.dailynews.pipeline.orchestrate.NetworkProbe
import java.time.Duration
import java.time.Instant

/**
 * Width limit shared by every diagnostics item, so expanded windows never span full width.
 * LazyColumn.horizontalAlignment proved unreliable under Robolectric, so this slot keeps
 * the lazy item full width and deterministically centers the capped content inside it.
 */
internal val diagnosticsItemWidth: Modifier
    get() = Modifier.fillMaxWidth().layout { measurable, constraints ->
        val cappedWidth = constraints.maxWidth.coerceAtMost(DailyNewsSpacing.readingMaxWidth.roundToPx())
        val placeable = measurable.measure(constraints.copy(minWidth = cappedWidth, maxWidth = cappedWidth))
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative((constraints.maxWidth - placeable.width).coerceAtLeast(0) / 2, 0)
        }
    }

/**
 * Diagnostics-only verdict badge. Deliberately separate from CommonUi.StatusBadge:
 * EXPECTED_BLOCK (system blocked it by rule) must not look like UNEXPECTED_ERROR
 * (a real failure the user has to act on).
 */
@Composable
internal fun RunVerdictBadge(status: String, classification: String, detail: String? = null) {
    val (label, container, content) = when {
        status == "RUNNING" -> Triple("进行中", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        classification == "SUCCESS" -> Triple("正常", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        classification == "EXPECTED_BLOCK" -> Triple("按规则阻断", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        classification == "UNEXPECTED_ERROR" -> Triple("运行故障", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        classification == "INTERRUPTED" -> Triple("被中断", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(classification.ifBlank { "UNKNOWN" }, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.semantics { contentDescription = listOf(label, detail).filterNotNull().joinToString("，") },
    ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge) }
}

internal fun formatRunDuration(startedAtUtc: String, finishedAtUtc: String?): String? = runCatching {
    val end = finishedAtUtc?.let { Instant.parse(it) } ?: return null
    val seconds = Duration.between(Instant.parse(startedAtUtc), end).seconds.takeIf { it >= 0 } ?: return null
    when {
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}.getOrNull()

@Composable
internal fun VerdictCard(
    detail: RunDetail?,
    advice: DiagnosticsAdvice,
    blockingReasons: List<String>,
    stage: String?,
    onRunNow: () -> Unit,
    onRunProbe: () -> Unit,
    onExport: () -> Unit,
    onOpenFeeds: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
                RunVerdictBadge(detail?.status.orEmpty(), detail?.classification.orEmpty(), detail?.reportDate)
                detail?.let { Text(it.reportDate, style = MaterialTheme.typography.labelMedium) }
            }
            Text(advice.headline, style = MaterialTheme.typography.titleMedium)
            detail?.let { run ->
                val meta = listOfNotNull(
                    formatRunDuration(run.startedAtUtc, run.finishedAtUtc)?.let { "耗时 $it" },
                    run.trigger.ifBlank { null }?.let { "触发：$it" },
                    "exit ${run.validatorExitCode}",
                    "重试 ${run.attempt}",
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            stage?.let { Text("失败阶段：$it", style = MaterialTheme.typography.labelMedium) }
            if (blockingReasons.isNotEmpty()) {
                Text("为什么：", style = MaterialTheme.typography.labelMedium)
                blockingReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            val action = advice.action
            val callback: (() -> Unit)? = when (action) {
                DiagnosticsAction.RUN_NOW -> onRunNow
                DiagnosticsAction.RUN_PROBE -> onRunProbe
                DiagnosticsAction.EXPORT_ZIP -> onExport
                DiagnosticsAction.OPEN_FEEDS -> onOpenFeeds
                DiagnosticsAction.OPEN_PIPELINE_SETTINGS, DiagnosticsAction.OPEN_PROVIDER_SETTINGS -> onOpenSettings
                DiagnosticsAction.NONE -> null
            }
            val label = when (action) {
                DiagnosticsAction.RUN_NOW -> "重新生成"
                DiagnosticsAction.RUN_PROBE -> "运行网络探测"
                DiagnosticsAction.EXPORT_ZIP -> stringResource(R.string.export_artifacts)
                DiagnosticsAction.OPEN_FEEDS -> "打开订阅源"
                DiagnosticsAction.OPEN_PIPELINE_SETTINGS -> "打开 Pipeline 设置"
                DiagnosticsAction.OPEN_PROVIDER_SETTINGS -> "打开 Provider 设置"
                DiagnosticsAction.NONE -> null
            }
            when {
                label != null && callback != null -> Button(onClick = callback, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                label != null -> Text("建议：$label", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Unit
            }
        }
    }
}

@Composable
internal fun RunRow(run: RunSummary, selected: Boolean, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        modifier = diagnosticsItemWidth,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
                Text(run.reportDate, style = MaterialTheme.typography.titleMedium)
                RunVerdictBadge(run.status, run.classification)
            }
            Text("exit ${run.validatorExitCode} · 重试 ${run.attempt} · ${run.startedAtUtc}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FeedResultRow(result: FeedResult, stale: Boolean) {
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
                Text(result.source, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                StatusBadge(result.status, result.error)
                if (stale) Text("可能陈旧", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${result.articleCount} 篇文章", style = MaterialTheme.typography.labelMedium)
            result.error?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun TimelineRow(log: RunLogEntity) {
    val barColor = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(diagnosticsItemWidth) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(barColor))
            Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Step names stay in English on purpose: they are the search keywords for troubleshooting.
                Text("${log.level} · ${log.step}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(log.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun ProbeGroupCard(target: String, rows: List<NetworkProbe>) {
    val failed = rows.count { !it.passed }
    val summary = when {
        failed == 0 -> "全部通过"
        failed == rows.size -> "全部失败"
        else -> "部分失败"
    }
    val summaryColor = when {
        failed == 0 -> MaterialTheme.colorScheme.primary
        failed == rows.size -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
                Text(target, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Text(summary, style = MaterialTheme.typography.labelMedium, color = summaryColor)
            }
            rows.forEach { probe ->
                Text("${if (probe.passed) "✓" else "✗"} ${probe.stage}：${probe.detail}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun LlmCallRow(call: LlmCallEntity) {
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${call.role} · ${call.provider}/${call.model}", style = MaterialTheme.typography.labelMedium)
            Text("tokens ${call.inputTokens ?: "?"}+${call.outputTokens ?: "?"} · ${call.outcome}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun RawJsonBlock(title: String, payload: ArtifactPayload) {
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.regular), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val note = when (payload.status) {
                ArtifactStatus.DEGRADED -> "部分字段无法解析，已按可读字段降级"
                ArtifactStatus.UNPARSEABLE -> "无法解析，已回退到运行记录"
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
            if (payload.truncated) Text("已截断至 $MAX_RAW_CHARS 字符", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(payload.raw.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = diagnosticsItemWidth)
}

@Composable
internal fun LoadingSkeleton() {
    Card(diagnosticsItemWidth) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular)) {
            Text(stringResource(R.string.diagnostics_loading), style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun InlineProgress() {
    Row(diagnosticsItemWidth.padding(DailyNewsSpacing.regular), horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.diagnosing), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Collapsible section that mirrors ReportScreen's fold pattern: ▼/▶ text prefix on a
 * clickable Card header, child rows emitted as sibling lazy items to keep flat scrolling.
 */
internal fun LazyListScope.collapsibleSection(
    keyPrefix: String,
    titleRes: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    body: LazyListScope.() -> Unit = {},
) {
    item(key = "$keyPrefix-header") {
        Card(diagnosticsItemWidth.clickable(onClick = onToggle)) {
            Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                Text("${if (expanded) "▼" else "▶"} ${stringResource(titleRes)}", style = MaterialTheme.typography.titleLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (expanded) body()
}
