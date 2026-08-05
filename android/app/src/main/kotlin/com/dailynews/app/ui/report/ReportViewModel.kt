package com.dailynews.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ReportEntity
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.repo.FavoriteRepository
import com.dailynews.data.repo.ReportRepository
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ReportGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

data class ReportUiState(
    val report: ReportEntity? = null,
    val items: List<ReportItemEntity> = emptyList(),
    val groups: List<ReportGroup> = emptyList(),
    val savedLinks: Set<String> = emptySet(),
    val readLinks: Set<String> = emptySet(),
    val showRaw: Boolean = false,
    val expandedSources: Set<String> = emptySet(),
    val generatingSources: Set<String> = emptySet(),
    val groupErrors: Map<String, String> = emptyMap(),
)

private data class ReportInteractionState(
    val showRaw: Boolean,
    val expandedSources: Set<String>?,
    val generatingSources: Set<String>,
    val groupErrors: Map<String, String>,
)

class ReportViewModel(
    date: String,
    reports: ReportRepository,
    private val favorites: FavoriteRepository,
    private val generateGroup: suspend (String) -> Unit,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val showRaw = MutableStateFlow(savedState[SHOW_RAW_KEY] ?: false)
    private val expandedSources = MutableStateFlow<Set<String>?>(
        savedState.get<ArrayList<String>>(EXPANDED_SOURCES_KEY)?.toSet(),
    )
    private val generatingSources = MutableStateFlow(emptySet<String>())
    private val groupErrors = MutableStateFlow(emptyMap<String, String>())
    private val interaction = combine(showRaw, expandedSources, generatingSources, groupErrors) { raw, expanded, generating, errors ->
        ReportInteractionState(raw, expanded, generating, errors)
    }
    val state: StateFlow<ReportUiState> = combine(
        reports.report(date),
        reports.items(date),
        favorites.observeSavedLinks(),
        favorites.observeReadLinks(),
        interaction,
    ) { report, items, saved, read, interaction ->
        val groups = runCatching {
            ArtifactJson.codec.decodeFromString<List<ReportGroup>>(report?.groupsJson.orEmpty())
        }.getOrDefault(emptyList())
        ReportUiState(
            report = report,
            items = items,
            groups = groups,
            savedLinks = saved,
            readLinks = read,
            showRaw = interaction.showRaw,
            expandedSources = interaction.expandedSources ?: emptySet(),
            generatingSources = interaction.generatingSources,
            groupErrors = interaction.groupErrors,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUiState())

    fun toggleRaw() {
        showRaw.value = !showRaw.value
        savedState[SHOW_RAW_KEY] = showRaw.value
    }

    fun toggleGroup(source: String) {
        val current = expandedSources.value ?: state.value.expandedSources
        if (source in current) {
            expandedSources.value = current - source
            savedState[EXPANDED_SOURCES_KEY] = ArrayList(current - source)
            return
        }
        expandedSources.value = current + source
        savedState[EXPANDED_SOURCES_KEY] = ArrayList(current + source)
        val needsSummary = state.value.items.any { it.part == 2 && it.source == source && it.summaryZh.isBlank() }
        if (!needsSummary || source in generatingSources.value) return
        viewModelScope.launch {
            generatingSources.value += source
            groupErrors.value -= source
            try {
                generateGroup(source)
            } catch (error: Throwable) {
                groupErrors.value += source to (error.message ?: error::class.java.simpleName)
            } finally {
                generatingSources.value -= source
            }
        }
    }

    fun toggleFavorite(item: ReportItemEntity) {
        viewModelScope.launch {
            if (item.link in state.value.savedLinks) favorites.remove(item.link)
            else favorites.save(item.link, item.title, item.source, item.summaryZh)
        }
    }

    fun markRead(link: String) {
        viewModelScope.launch { favorites.markRead(link) }
    }

    private companion object {
        const val SHOW_RAW_KEY = "show-raw"
        const val EXPANDED_SOURCES_KEY = "expanded-sources"
    }
}
