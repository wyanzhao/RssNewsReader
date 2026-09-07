package com.dailynews.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.repo.ReportRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

/** Story history grouped by day. */
data class StoryDay(val reportDate: String, val items: List<ReportItemEntity>)

data class StoryUiState(
    val eventKey: String = "",
    /** null = before the first emission; consistent with ReaderPhase's three-state idea, so loading is never mis-displayed as empty. */
    val days: List<StoryDay>? = null,
    val headline: String = "",
    val watching: Boolean = false,
    val watchBusy: Boolean = false,
    val watchError: String? = null,
) {
    val totalReports: Int get() = days?.sumOf { it.items.size } ?: 0
}

/**
 * Story history consumes only `report_items` (the V4-D2 surface-attribution red line). It
 * deliberately does not touch the article pool: the pool has a retention period, while the
 * items of a published report are a permanent snapshot.
 */
class StoryViewModel(
    reports: ReportRepository,
    private val eventKey: String,
    private val config: com.dailynews.data.config.PipelineConfigRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val state: StateFlow<StoryUiState> = combine(reports.story(eventKey), config.config, busy, error) { rows, settings, pending, failure ->
            val days = rows.groupBy(ReportItemEntity::reportDate)
                .map { (date, items) -> StoryDay(date, items.sortedBy(ReportItemEntity::position)) }
                .sortedByDescending(StoryDay::reportDate)
            StoryUiState(
                eventKey = eventKey,
                watching = settings.watches.events.any { it.eventKey == eventKey },
                watchBusy = pending,
                watchError = failure,
                days = days,
                // Use the earliest item's title as the story name: it is where this story started, and is more stable than the latest item.
                headline = days.lastOrNull()?.items?.firstOrNull()?.title.orEmpty(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryUiState(eventKey = eventKey))
    fun toggleWatch() {
        if (busy.value) return
        val current = state.value
        val date = current.days?.firstOrNull()?.reportDate ?: return
        busy.value = true
        viewModelScope.launch {
            error.value = null
            try {
                config.setEventWatch(com.dailynews.model.EventWatch(eventKey, current.headline, date), !current.watching)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message ?: "保存关注失败，请重试" }
            finally { busy.value = false }
        }
    }

}
