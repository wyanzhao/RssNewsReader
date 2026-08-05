package com.dailynews.app.ui.brief

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.dailynews.app.ui.common.SweepUiProgress
import com.dailynews.app.ui.common.sweepProgressFor
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.config.ProviderSettingsRepository
import com.dailynews.data.db.ReportSummary
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.RunSummary
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.ReportRepository
import com.dailynews.data.repo.RunLogRepository
import com.dailynews.data.repo.RunRepository
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TodayUiState(
    val latest: ReportSummary? = null,
    val providerConfigured: Boolean = false,
    val scheduleTime: String = "10:00",
    val missedToday: Boolean = false,
    val currentRun: RunSummary? = null,
    val runSteps: List<RunLogEntity> = emptyList(),
    val poolCount: Int = 0,
    val nextScheduledAt: String = "",
    val sweepRefreshing: Boolean = false,
    val sweepProgressLabel: String = "",
)

private data class TodayOperationalState(
    val run: RunSummary?,
    val logs: List<RunLogEntity>,
    val poolCount: Int,
    val sweep: SweepUiProgress,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    reports: ReportRepository,
    config: PipelineConfigRepository,
    providerSettings: ProviderSettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    minuteTicks: Flow<ZonedDateTime>? = null,
    runRepository: RunRepository? = null,
    runLogs: RunLogRepository? = null,
    articleRepository: ArticleRepository? = null,
    sweepWorkInfos: Flow<List<WorkInfo>> = flowOf(emptyList()),
) : ViewModel() {
    private val ticks = minuteTicks ?: minuteTicker(clock)
    private val recentRuns = runRepository?.observeRecent(1) ?: flowOf(emptyList())
    private val recentRunLogs = recentRuns.flatMapLatest { runs ->
        val runId = runs.firstOrNull()?.runId
        if (runId == null || runLogs == null) flowOf(emptyList()) else runLogs.observe(runId)
    }
    private val poolCount = ticks.flatMapLatest { now ->
        articleRepository?.observeCountSince(now.toInstant().minusSeconds(28 * 60 * 60L)) ?: flowOf(0)
    }
    private val sweepProgress = sweepWorkInfos
        .map { infos -> sweepProgressFor(infos.map { it.state }) }
    private val operational = combine(recentRuns, recentRunLogs, poolCount, sweepProgress) { runs, logs, count, sweep ->
        TodayOperationalState(runs.firstOrNull(), logs, count, sweep)
    }
    val state: StateFlow<TodayUiState> = combine(
        reports.summaries(),
        config.config,
        providerSettings.settings,
        ticks,
        operational,
    ) { reportRows, pipelineConfig, settings, now, ops ->
        TodayUiState(
            latest = reportRows.firstOrNull(),
            providerConfigured = settings.providers.isNotEmpty(),
            scheduleTime = pipelineConfig.scheduleTime,
            missedToday = isMissedToday(reportRows, pipelineConfig.scheduleTime, now),
            currentRun = ops.run,
            runSteps = ops.logs,
            poolCount = ops.poolCount,
            nextScheduledAt = nextScheduledLabel(pipelineConfig.scheduleTime, now),
            sweepRefreshing = ops.sweep.active,
            sweepProgressLabel = ops.sweep.label,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}

internal fun nextScheduledLabel(scheduleTime: String, now: ZonedDateTime): String {
    val time = runCatching { LocalTime.parse(scheduleTime) }.getOrNull() ?: return "每日 $scheduleTime"
    val date = if (now.toLocalTime().isBefore(time)) now.toLocalDate() else now.toLocalDate().plusDays(1)
    return "$date $time"
}

internal fun isMissedToday(reports: List<ReportSummary>, scheduleTime: String, now: ZonedDateTime): Boolean {
    val hasSuccess = reports.any { it.reportDate == now.toLocalDate().toString() && it.status == "SUCCESS" }
    val schedule = runCatching { LocalTime.parse(scheduleTime) }.getOrNull() ?: return false
    return !hasSuccess && !now.toLocalTime().isBefore(schedule)
}

private fun minuteTicker(clock: Clock): Flow<ZonedDateTime> = flow {
    while (true) {
        emit(ZonedDateTime.now(clock))
        val seconds = ZonedDateTime.now(clock).second
        delay(((60 - seconds).coerceAtLeast(1)) * 1_000L)
    }
}
