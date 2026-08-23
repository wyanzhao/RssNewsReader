package com.dailynews.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ReportPreview
import com.dailynews.data.db.PeriodicReportSummary
import com.dailynews.data.repo.PeriodicReportRepository
import com.dailynews.data.repo.ReportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

data class HistoryUiState(
    val query: String = "",
    val reports: List<ReportPreview> = emptyList(),
    val selectedDate: String? = null,
    /** Weekly / monthly reports. The history page is already the "past reports" semantic surface, so no new bottom-navigation item is added (V4-D1 locks the roster at 5 members). */
    val periodicReports: List<PeriodicReportSummary> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: ReportRepository,
    periodicReports: PeriodicReportRepository? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<String?>(null)
    private val results = query.debounceSearchInput().flatMapLatest(repository::previews)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val periodic = periodicReports?.observeSummaries() ?: flowOf(emptyList())

    val state: StateFlow<HistoryUiState> = combine(query, results, selected, periodic, ::HistoryUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        results.onEach { reports ->
            if (selected.value !in reports.map(ReportPreview::reportDate)) selected.value = reports.firstOrNull()?.reportDate
        }.launchIn(viewModelScope)
    }

    fun setQuery(value: String) { query.value = value }
    fun select(date: String) { selected.value = date }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<String>.debounceSearchInput(delayMillis: Long = 300): Flow<String> = transformLatest { value ->
    if (value.isNotBlank()) delay(delayMillis)
    emit(value)
}
