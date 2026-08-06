package com.dailynews.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.repo.ReportRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 一天一组的线索历史。 */
data class StoryDay(val reportDate: String, val items: List<ReportItemEntity>)

data class StoryUiState(
    val eventKey: String = "",
    /** null = 首次发射之前，与 ReaderPhase 的三态思路一致，避免把加载中误显示成空。 */
    val days: List<StoryDay>? = null,
    val headline: String = "",
) {
    val totalReports: Int get() = days?.sumOf { it.items.size } ?: 0
}

/**
 * 线索历史只消费 `report_items`（V4-D2 表面归属红线）。它刻意不碰文章池：
 * 池有留存期，而已发布报告的条目是永久快照。
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
                // 用最早一条的标题当线索名：那是这条线索的起点，比最新一条更稳定。
                headline = days.lastOrNull()?.items?.firstOrNull()?.title.orEmpty(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryUiState(eventKey = eventKey))
}
