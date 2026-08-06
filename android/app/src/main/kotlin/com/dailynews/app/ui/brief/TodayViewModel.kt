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
    /** 当前显示日期的报告。**按日期精确匹配**，不再是「最新一份」——断更三天时
     *  首页显示三天前的内容而不加任何提示，是 Epic V 要修的核心问题。 */
    val current: ReportSummary? = null,
    val effectiveDate: String = "",
    /** 真实的当天日期，供日期标签区分「· 今天」。 */
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

/** ticks 与 selectedDate 先折成一个值：主 combine 已经用满 5 元强类型重载。 */
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

    /** null = 跟随当天。用户翻到往期后，跨零点也停在他选的那天，不会被时钟拽走。 */
    private val selectedDate = MutableStateFlow<String?>(null)
    private val selection = combine(ticks, selectedDate) { now, picked -> BriefSelection(now, picked) }

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
            // 精确匹配当前日期，不再 firstOrNull()：拿不到就是拿不到，
            // 由空态诚实说明，而不是不声不响地显示几天前的报告。
            current = reportRows.firstOrNull { it.reportDate == effectiveDate },
            effectiveDate = effectiveDate,
            today = today,
            isToday = isToday,
            availableDates = available,
            hasPrevious = previousReportDate(available, effectiveDate) != null,
            hasNext = nextReportDate(available, effectiveDate) != null,
            providerConfigured = settings.providers.isNotEmpty(),
            scheduleTime = pipelineConfig.scheduleTime,
            // 只在看今天时才提示补跑：翻到三天前还弹「今天没跑」纯属噪音。
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

    /** delta < 0 往前翻，> 0 往后翻。只在有报告的日期之间跳，到头即不动。 */
    fun stepDate(delta: Int) {
        val snapshot = state.value
        val target = if (delta < 0) {
            previousReportDate(snapshot.availableDates, snapshot.effectiveDate)
        } else {
            nextReportDate(snapshot.availableDates, snapshot.effectiveDate)
        } ?: return
        // 翻回当天时清空选择，这样跨零点后会继续跟随新的一天。
        selectedDate.value = target.takeIf { it != todayString() }
    }

    private fun todayString(): String = ZonedDateTime.now(clock).toLocalDate().toString()
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
