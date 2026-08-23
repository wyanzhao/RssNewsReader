package com.dailynews.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.repo.ReportRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Story history grouped by day. */
data class StoryDay(val reportDate: String, val items: List<ReportItemEntity>)

data class StoryUiState(
    val eventKey: String = "",
    /** null = before the first emission; consistent with ReaderPhase's three-state idea, so loading is never mis-displayed as empty. */
    val days: List<StoryDay>? = null,
    val headline: String = "",
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
) : ViewModel() {
    val state: StateFlow<StoryUiState> = reports.story(eventKey)
        .map { rows ->
            val days = rows.groupBy(ReportItemEntity::reportDate)
                .map { (date, items) -> StoryDay(date, items.sortedBy(ReportItemEntity::position)) }
                .sortedByDescending(StoryDay::reportDate)
            StoryUiState(
                eventKey = eventKey,
                days = days,
                // Use the earliest item's title as the story name: it is where this story started, and is more stable than the latest item.
                headline = days.lastOrNull()?.items?.firstOrNull()?.title.orEmpty(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryUiState(eventKey = eventKey))
}
