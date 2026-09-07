package com.dailynews.app.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.RunSummary
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.FeedRepository
import com.dailynews.data.repo.LlmCallRepository
import com.dailynews.data.repo.RunLogRepository
import com.dailynews.data.repo.RunRepository
import com.dailynews.model.ValidationCounts
import com.dailynews.model.FeedResult
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics
import com.dailynews.pipeline.orchestrate.NetworkProbeTarget
import com.dailynews.pipeline.orchestrate.NetworkProbe
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunDetail(
    val runId: String,
    val reportDate: String,
    val status: String,
    val classification: String,
    val validatorExitCode: Int,
    val attempt: Int,
    val trigger: String,
    val startedAtUtc: String,
    val finishedAtUtc: String?,
)

data class LlmTotals(val calls: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0, val failed: Int = 0)

data class DiagnosticsEvent(val id: Long, val message: String, val error: Boolean, val retryTag: String? = null)

data class ComparisonStatus(val status: String, val preference: String)

data class DiagnosticsUiState(
    val runs: List<RunSummary> = emptyList(),
    val selectedRunId: String? = null,
    val detail: RunDetail? = null,
    val advice: DiagnosticsAdvice = DiagnosticsAdvice("正在读取运行记录…", DiagnosticsAction.NONE),
    val stage: String? = null,
    val blockingReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val counts: ValidationCounts? = null,
    val feedResults: List<FeedResult> = emptyList(),
    val budget: ContextBudgetView? = null,
    val logs: List<RunLogEntity> = emptyList(),
    val llmCalls: List<LlmCallEntity> = emptyList(),
    val llmTotals: LlmTotals = LlmTotals(),
    val probes: List<NetworkProbe> = emptyList(),
    val probing: Boolean = false,
    val probeSuggested: Boolean = false,
    val validationArtifact: ArtifactPayload = ArtifactPayload(),
    val budgetArtifact: ArtifactPayload = ArtifactPayload(),
    /** Contract-violation artifacts: name → content. An empty list means this run was never sent back. */
    val comparisons: List<ComparisonStatus> = emptyList(),
    val recoveryCosts: com.dailynews.pipeline.observability.RecoveryAccounting? = null,
    val shortlistAudit: com.dailynews.model.Part1ShortlistPayload? = null,
    val shortlistAuditError: Boolean = false,
    val finalPlan: com.dailynews.model.Part1Plan? = null,
    val finalPlanError: Boolean = false,
    val contractViolations: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = true,
    val artifactsLoading: Boolean = false,
    val events: List<DiagnosticsEvent> = emptyList(),
)

internal data class DiagnosticDetails(
    val logs: List<RunLogEntity> = emptyList(),
    val calls: List<LlmCallEntity> = emptyList(),
    val artifactsLoading: Boolean = false,
    val resolved: ResolvedArtifacts = ResolvedArtifacts(),
    val comparisons: List<ComparisonStatus> = emptyList(),
    val recoveryCosts: com.dailynews.pipeline.observability.RecoveryAccounting? = null,
    val shortlistAudit: com.dailynews.model.Part1ShortlistPayload? = null,
    val shortlistAuditError: Boolean = false,
    val finalPlan: com.dailynews.model.Part1Plan? = null,
    val finalPlanError: Boolean = false,
    val contractViolations: List<Pair<String, String>> = emptyList(),
)

private sealed interface ArtifactTexts {
    data object Loading : ArtifactTexts
    data class Loaded(
        val validation: String?,
        val budget: String?,
        /** name → content. One is written every time the LLM is sent back; previously completely invisible inside the app. */
        val violations: List<Pair<String, String>>,
        val shortlist: String?,
        val plan: String?,
        val comparisons: List<ComparisonStatus>,
        val recoveryCosts: com.dailynews.pipeline.observability.RecoveryAccounting,
    ) : ArtifactTexts
}

internal data class SideState(
    val probes: List<NetworkProbe>,
    val probing: Boolean,
    val events: List<DiagnosticsEvent>,
)

/** Keeps an explicit deep-link target even after retention deleted the run row. */
internal fun initialSelection(initialRunId: String?, rows: List<RunSummary>): String? =
    initialRunId ?: rows.firstOrNull()?.runId

internal fun llmTotalsFor(calls: List<LlmCallEntity>): LlmTotals = LlmTotals(
    calls = calls.size,
    inputTokens = calls.sumOf { it.inputTokens ?: 0L },
    outputTokens = calls.sumOf { it.outputTokens ?: 0L },
    failed = calls.count { !it.outcome.startsWith("success") && !it.outcome.startsWith("repair_success") },
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel(
    initialRunId: String?,
    private val savedState: SavedStateHandle,
    private val runs: RunRepository,
    private val runLogs: RunLogRepository,
    private val llmCalls: LlmCallRepository,
    private val artifacts: ArtifactStore,
    private val feeds: FeedRepository,
    private val networkDiagnostics: NetworkDiagnostics,
    private val networkContext: () -> Map<String, String>,
    private val providerTargets: () -> List<NetworkProbeTarget> = { emptyList() },
) : ViewModel() {
    private val selected = savedState.getStateFlow<String?>(SELECTED_RUN_ID, initialRunId)
    private val probes = MutableStateFlow<List<NetworkProbe>>(emptyList())
    private val probing = MutableStateFlow(false)
    private val events = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())
    private val eventIds = AtomicLong(0)
    private val recentRuns = runs.observeRecent(50)

    /** Full RunEntity row so the verdict card can track RUNNING -> finished transitions. */
    private val detail: Flow<RunEntity?> = selected.flatMapLatest { runId ->
        runId?.let { runs.observeDetail(it) } ?: flowOf(null)
    }

    /**
     * Artifacts live in their own flow keyed by the detail row: when a RUNNING run
     * finishes, Room re-emits the entity and this branch re-reads validation.json.
     * Loading is emitted first so the verdict card (Room-only data) is never blocked
     * on disk IO.
     */
    private val details: Flow<DiagnosticDetails> = detail.flatMapLatest { entity ->
        if (entity == null) flowOf(DiagnosticDetails())
        else {
            val texts: Flow<ArtifactTexts> = artifacts.observeComparisonRevision(entity.runId).flatMapLatest { flow {
                emit(ArtifactTexts.Loading)
                val loaded = withContext(Dispatchers.IO) {
                    // The contract-violation artifacts are the only record of what exactly went wrong
                    // when the model was sent back for the Nth time. Sorting by name sorts by
                    // operation + attempt, which is exactly the order they happened in.
                    val violations = artifacts.names(entity.runId)
                        .filter { it.startsWith("contract_violations/") }
                        .sorted()
                        .mapNotNull { name -> artifacts.readText(entity.runId, name)?.let { name to it } }
                    val chain = try { runs.recoveryAccounting(entity.runId, artifacts) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { com.dailynews.pipeline.observability.RecoveryAccounting(emptyList(), "0", 0, 0, false, listOf("read_error")) }
                    ArtifactTexts.Loaded(
                        artifacts.readText(entity.runId, "validation.json"),
                        artifacts.readText(entity.runId, "context_budget.json"),
                        violations,
                        artifacts.readText(entity.runId, "part1_shortlist.json"),
                        artifacts.readText(entity.runId, "part1_plan.json"),
                        artifacts.names(entity.runId).filter { it.startsWith("comparisons/") && it.endsWith("/manifest.json") }.map { name ->
                            runCatching {
                                val manifest = com.dailynews.model.ArtifactJson.codec.decodeFromString<kotlinx.serialization.json.JsonObject>(requireNotNull(artifacts.readText(entity.runId, name)))
                                ComparisonStatus((manifest["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "unknown",
                                    (manifest["preference"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
                            }.getOrElse { ComparisonStatus("unreadable", "") }
                        },
                        chain,
                    )
                }
                emit(loaded)
            } }
            combine(runLogs.observe(entity.runId), llmCalls.observe(entity.runId), texts) { logs, calls, artifactTexts ->
                when (artifactTexts) {
                    ArtifactTexts.Loading -> DiagnosticDetails(logs, calls, artifactsLoading = true)
                    is ArtifactTexts.Loaded -> DiagnosticDetails(
                        logs = logs,
                        calls = calls,
                        artifactsLoading = false,
                        resolved = resolveDiagnosticsArtifacts(artifactTexts.validation, artifactTexts.budget, entity, logs),
                        comparisons = artifactTexts.comparisons,
                        recoveryCosts = artifactTexts.recoveryCosts,
                        finalPlan = artifactTexts.plan?.let { runCatching { com.dailynews.model.ArtifactJson.codec.decodeFromString<com.dailynews.model.Part1Plan>(it) }.getOrNull() },
                        finalPlanError = artifactTexts.plan?.let { runCatching { com.dailynews.model.ArtifactJson.codec.decodeFromString<com.dailynews.model.Part1Plan>(it) }.isFailure } ?: false,
                        shortlistAudit = artifactTexts.shortlist?.let { runCatching { com.dailynews.model.ArtifactJson.codec.decodeFromString<com.dailynews.model.Part1ShortlistPayload>(it) }.getOrNull() },
                        shortlistAuditError = artifactTexts.shortlist?.let { runCatching { com.dailynews.model.ArtifactJson.codec.decodeFromString<com.dailynews.model.Part1ShortlistPayload>(it) }.isFailure } ?: false,
                        contractViolations = artifactTexts.violations,
                    )
                }
            }
        }
    }

    private val sideState = combine(probes, probing, events, ::SideState)

    val state: StateFlow<DiagnosticsUiState> = combine(recentRuns, selected, detail, details, sideState) {
            runRows, selectedId, entity, detailBundle, side ->
        buildState(runRows, selectedId, entity, detailBundle, side)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    init {
        // Only fill a missing selection. An explicit deep-link runId is never rewritten
        // to "latest", even when retention already cleaned the row.
        viewModelScope.launch {
            recentRuns.collect { rows ->
                if (selected.value == null) {
                    savedState[SELECTED_RUN_ID] = initialSelection(initialRunId, rows)
                }
            }
        }
    }

    fun select(runId: String) { savedState[SELECTED_RUN_ID] = runId }

    fun consumeEvent(id: Long) { events.update { list -> list.filterNot { it.id == id } } }

    fun runNetworkDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            probing.value = true
            runCatching {
                networkDiagnostics.run(
                    feeds.enabledFeeds(),
                    androidContext = networkContext(),
                    providerTargets = providerTargets(),
                )
            }
                .onSuccess { probes.value = it }
                .onFailure { postEvent("诊断失败：${it.message ?: it::class.simpleName}", error = true, retryTag = "probe") }
            probing.value = false
        }
    }

    fun export(write: suspend (suspend (OutputStream) -> Unit) -> Boolean) {
        val runId = selected.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val accounting = runs.recoveryAccounting(runId, artifacts)
                artifacts.write(runId, "recovery_accounting.json", com.dailynews.model.ArtifactJson.codec.encodeToString(
                    com.dailynews.pipeline.observability.RecoveryAccounting.serializer(), accounting).toByteArray())
                check(write { output -> artifacts.exportZip(runId, output) }) { "无法写入所选文件" }
            }
                .onSuccess { postEvent("产物已导出", error = false) }
                .onFailure { postEvent("导出失败：${it.message ?: it::class.simpleName}", error = true, retryTag = "export") }
        }
    }

    private fun postEvent(message: String, error: Boolean, retryTag: String? = null) {
        events.update { it + DiagnosticsEvent(eventIds.getAndIncrement(), message, error, retryTag) }
    }

    private companion object {
        const val SELECTED_RUN_ID = "selectedRunId"
    }
}

/** Pure projection kept out of the combine lambda so it is directly unit-testable. */
internal fun buildState(
    runRows: List<RunSummary>,
    selectedId: String?,
    entity: RunEntity?,
    detailBundle: DiagnosticDetails,
    side: SideState,
): DiagnosticsUiState {
    val resolved = detailBundle.resolved.validation
    val stage = stageFrom(resolved.blockingReasons)
    val evidence = evidenceFor(resolved.blockingReasons, resolved.warnings, detailBundle.logs)
    val detail = entity?.let {
        RunDetail(
            runId = it.runId,
            reportDate = it.reportDate,
            status = it.status,
            classification = it.classification,
            validatorExitCode = it.validatorExitCode,
            attempt = it.attempt,
            trigger = it.trigger,
            startedAtUtc = it.startedAtUtc,
            finishedAtUtc = it.finishedAtUtc,
        )
    }
    val advice = advise(
        DiagnosticsAdviceInput(
            hasRuns = runRows.isNotEmpty(),
            status = entity?.status.orEmpty(),
            classification = entity?.classification.orEmpty(),
            stage = stage,
            validatorExitCode = entity?.validatorExitCode ?: 40,
            warningCount = resolved.warnings.size,
            errorFeedCount = resolved.feedResults.count { feed -> feed.status == "error" },
            evidence = evidence,
        ),
    )
    return DiagnosticsUiState(
        runs = runRows,
        selectedRunId = selectedId,
        detail = detail,
        advice = if (entity?.status == "RUNNING") advice.copy(headline = com.dailynews.app.ui.common.activeGenerationLabel(detailBundle.logs)) else advice,
        stage = stage,
        blockingReasons = resolved.blockingReasons,
        warnings = resolved.warnings,
        counts = resolved.counts,
        feedResults = resolved.feedResults,
        budget = detailBundle.resolved.budget,
        comparisons = detailBundle.comparisons,
        recoveryCosts = detailBundle.recoveryCosts,
        shortlistAudit = detailBundle.shortlistAudit,
        shortlistAuditError = detailBundle.shortlistAuditError,
        finalPlan = detailBundle.finalPlan,
        finalPlanError = detailBundle.finalPlanError,
        logs = detailBundle.logs,
        llmCalls = detailBundle.calls,
        llmTotals = llmTotalsFor(detailBundle.calls),
        probes = side.probes,
        probing = side.probing,
        probeSuggested = probeSuggested(side.probes, evidence),
        contractViolations = detailBundle.contractViolations,
        validationArtifact = detailBundle.resolved.validationArtifact,
        budgetArtifact = detailBundle.resolved.budgetArtifact,
        loading = false,
        artifactsLoading = detailBundle.artifactsLoading,
        events = side.events,
    )
}
