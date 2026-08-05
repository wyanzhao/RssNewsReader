package com.dailynews.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.work.WorkInfo
import com.dailynews.app.ui.theme.DailyNewsSpacing

/**
 * sweep（后台增量抓取）进度模型。Epic U 起从 TodayViewModel 上移到 ui/common，
 * 简报页与阅读页共用同一份进度语义。
 */
data class SweepUiProgress(
    val active: Boolean = false,
    val label: String = "",
)

fun sweepProgressFor(states: List<WorkInfo.State>): SweepUiProgress = when {
    WorkInfo.State.RUNNING in states -> SweepUiProgress(true, "正在抓取 RSS 并更新文章池…")
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
        SweepUiProgress(true, "抓取任务已排队，正在等待网络或系统调度…")
    else -> SweepUiProgress()
}

@Composable
fun SweepProgressCard(label: String) {
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
