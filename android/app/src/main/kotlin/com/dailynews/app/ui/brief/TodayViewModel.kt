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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TodayUiState(
    /** Report for the currently displayed date. **Matched exactly by date**, no longer
     *  "the latest one" — when runs have been broken for three days, showing three-day-old
     *  content on the home page without any notice is the core problem Epic V set out to fix. */
    val current: ReportSummary? = null,
    val effectiveDate: String = "",
    /** The actual current-day date, used by the date label to mark the "· Today" suffix. */
    val today: String = "",
    val isToday: Boolean = true,
    val availableDates: List<String> = emptyList(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
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

/** Fold ticks and selectedDate into one value first: the main combine already uses up the 5-arg strongly-typed overload. */
private data class BriefSelection(val now: ZonedDateTime, val picked: String?)

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
    private val recentRuns = runRepository?.observeRecent(50) ?: flowOf(emptyList())
    private val poolCount = ticks.flatMapLatest { now ->
        articleRepository?.observeCountSince(now.toInstant().minusSeconds(28 * 60 * 60L)) ?: flowOf(0)
    }
    private val sweepProgress = sweepWorkInfos
        .map { infos -> sweepProgressFor(infos.map { it.state }) }
    /** null = follow today. Once the user flips to a past date, it stays on the chosen day even across midnight, not dragged away by the clock. */
    private val selectedDate = MutableStateFlow<String?>(null)
    private val selection = combine(ticks, selectedDate) { now, picked -> BriefSelection(now, picked) }
    private val displayedRun = combine(selection, recentRuns) { pick, runs ->
        val date = pick.picked ?: pick.now.toLocalDate().toString()
        runForDisplayedDate(runs, date)
    }
    private val displayedLogs = displayedRun.flatMapLatest { run ->
        val runId = run?.runId
        if (runId == null || runLogs == null) flowOf(emptyList()) else runLogs.observe(runId)
    }
    private val operational = combine(displayedRun, displayedLogs, poolCount, sweepProgress) { run, logs, count, sweep ->
        TodayOperationalState(run, logs, count, sweep)
    }

    val state: StateFlow<TodayUiState> = combine(
        reports.summaries(),
        config.config,
        providerSettings.settings,
        selection,
        operational,
    ) { reportRows, pipelineConfig, settings, pick, ops ->
        val today = pick.now.toLocalDate().toString()
        val available = reportRows.map(ReportSummary::reportDate)
        val effectiveDate = pick.picked ?: today
        val isToday = effectiveDate == today
        TodayUiState(
            // Exact match on the current date, no longer firstOrNull(): if there is
            // nothing, there is nothing — the empty state says so honestly, instead of
            // silently showing a days-old report.
            current = reportRows.firstOrNull { it.reportDate == effectiveDate },
            effectiveDate = effectiveDate,
            today = today,
            isToday = isToday,
            availableDates = available,
            hasPrevious = previousReportDate(available, effectiveDate) != null,
            hasNext = nextReportDate(available, effectiveDate) != null,
            providerConfigured = settings.providers.isNotEmpty(),
            scheduleTime = pipelineConfig.scheduleTime,
            // Only offer a makeup run when looking at today: popping "today did not run"
            // while flipped three days back is pure noise.
            missedToday = isToday && isMissedToday(reportRows, pipelineConfig.scheduleTime, pick.now),
            currentRun = ops.run,
            runSteps = ops.logs,
            poolCount = ops.poolCount,
            nextScheduledAt = nextScheduledLabel(pipelineConfig.scheduleTime, pick.now),
            sweepRefreshing = ops.sweep.active,
            sweepProgressLabel = ops.sweep.label,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun selectDate(date: String?) {
        selectedDate.value = normalizeReportDate(date)
    }

    fun backToToday() {
        selectedDate.value = null
    }

    /** delta < 0 flips backward, > 0 flips forward. Jumps only between dates that have reports; at the edge it does nothing. */
    fun stepDate(delta: Int) {
        val snapshot = state.value
        val target = if (delta < 0) {
            previousReportDate(snapshot.availableDates, snapshot.effectiveDate)
        } else {
            nextReportDate(snapshot.availableDates, snapshot.effectiveDate)
        } ?: return
        // Clear the selection when flipping back to today, so after crossing midnight it keeps following the new day.
        selectedDate.value = target.takeIf { it != todayString() }
    }

    private fun todayString(): String = ZonedDateTime.now(clock).toLocalDate().toString()
}

internal fun nextScheduledLabel(scheduleTime: String, now: ZonedDateTime): String {
    val time = runCatching { LocalTime.parse(scheduleTime) }.getOrNull() ?: return "每日 $scheduleTime"
    val date = if (now.toLocalTime().isBefore(time)) now.toLocalDate() else now.toLocalDate().plusDays(1)
    return "$date $time"
}

/**
 * Latest run for [effectiveDate] from a newest-first [runs] list.
 *
 * Brief status and failed-report diagnostics must follow the displayed day, not
 * `observeRecent(1)` globally — a today FAILED/RUNNING run must not overlay a past SUCCESS.
 */
internal fun runForDisplayedDate(runs: List<RunSummary>, effectiveDate: String): RunSummary? =
    runs.firstOrNull { it.reportDate == effectiveDate }

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
