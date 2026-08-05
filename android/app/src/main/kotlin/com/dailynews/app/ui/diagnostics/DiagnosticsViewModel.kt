package com.dailynews.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.RunSummary
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.FeedRepository
import com.dailynews.data.repo.LlmCallRepository
import com.dailynews.data.repo.RunLogRepository
import com.dailynews.data.repo.RunRepository
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics
import com.dailynews.pipeline.orchestrate.NetworkProbe
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DiagnosticDetails(
    val logs: List<RunLogEntity> = emptyList(),
    val calls: List<LlmCallEntity> = emptyList(),
    val validation: String? = null,
    val budget: String? = null,
)

private data class ProbeState(
    val rows: List<NetworkProbe>,
    val running: Boolean,
    val message: String?,
)

data class DiagnosticsUiState(
    val runs: List<RunSummary> = emptyList(),
    val selectedRunId: String? = null,
    val logs: List<RunLogEntity> = emptyList(),
    val llmCalls: List<LlmCallEntity> = emptyList(),
    val validation: String? = null,
    val budget: String? = null,
    val probes: List<NetworkProbe> = emptyList(),
    val probing: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel(
    initialRunId: String?,
    private val savedState: SavedStateHandle,
    runs: RunRepository,
    private val runLogs: RunLogRepository,
    private val llmCalls: LlmCallRepository,
    private val artifacts: ArtifactStore,
    private val feeds: FeedRepository,
    private val networkDiagnostics: NetworkDiagnostics,
    private val networkContext: () -> Map<String, String>,
) : ViewModel() {
    private val selected = savedState.getStateFlow<String?>(SELECTED_RUN_ID, initialRunId)
    private val probes = MutableStateFlow<List<NetworkProbe>>(emptyList())
    private val probing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val recentRuns = runs.observeRecent(50)

    private val details = selected.flatMapLatest { runId ->
        if (runId == null) flow { emit(DiagnosticDetails()) }
        else combine(
            runLogs.observe(runId),
            llmCalls.observe(runId),
            flow {
                emit(
                    withContext(Dispatchers.IO) {
                        artifacts.readText(runId, "validation.json") to artifacts.readText(runId, "context_budget.json")
                    },
                )
            },
        ) { logs, calls, documents -> DiagnosticDetails(logs, calls, documents.first, documents.second) }
    }

    private val probeState = combine(probes, probing, message, ::ProbeState)

    val state: StateFlow<DiagnosticsUiState> = combine(recentRuns, selected, details, probeState) {
            runRows, selectedId, detail, probeStatus ->
        DiagnosticsUiState(
            runs = runRows,
            selectedRunId = selectedId,
            logs = detail.logs,
            llmCalls = detail.calls,
            validation = detail.validation,
            budget = detail.budget,
            probes = probeStatus.rows,
            probing = probeStatus.running,
            message = probeStatus.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    init {
        viewModelScope.launch {
            recentRuns.collect { rows ->
                if (selected.value == null || rows.none { it.runId == selected.value }) {
                    savedState[SELECTED_RUN_ID] = initialRunId ?: rows.firstOrNull()?.runId
                }
            }
        }
    }

    fun select(runId: String) { savedState[SELECTED_RUN_ID] = runId }

    fun runNetworkDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            probing.value = true
            runCatching { networkDiagnostics.run(feeds.enabledFeeds(), androidContext = networkContext()) }
                .onSuccess { probes.value = it }
                .onFailure { message.value = "诊断失败：${it.message ?: it::class.simpleName}" }
            probing.value = false
        }
    }

    fun export(write: suspend (suspend (OutputStream) -> Unit) -> Boolean) {
        val runId = selected.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                check(write { output -> artifacts.exportZip(runId, output) }) { "无法写入所选文件" }
            }
                .onSuccess { message.value = "产物已导出" }
                .onFailure { message.value = "导出失败：${it.message ?: it::class.simpleName}" }
        }
    }

    private companion object {
        const val SELECTED_RUN_ID = "selectedRunId"
    }
}
