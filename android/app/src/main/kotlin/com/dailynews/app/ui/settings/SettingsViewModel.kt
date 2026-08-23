package com.dailynews.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dailynews.data.config.ApiKeyVault
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.config.ProviderSettings
import com.dailynews.data.config.ProviderSettingsRepository
import com.dailynews.data.repo.LlmCallRepository
import com.dailynews.data.repo.StateImporter
import com.dailynews.data.repo.StateBackupRepository
import java.io.OutputStream
import com.dailynews.llm.OpenRouterDefaults
import com.dailynews.llm.ProviderRouting
import com.dailynews.llm.ProviderSort
import com.dailynews.llm.ProviderType
import com.dailynews.llm.RoleModelDefaults
import com.dailynews.llm.StructuredMode
import com.dailynews.model.PipelineConfig
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.isValidScheduleTime
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsFormState(
    val providerType: ProviderType = ProviderType.OPENROUTER,
    val providerId: String = "default",
    val baseUrl: String = OpenRouterDefaults.BASE_URL,
    val apiKey: String = "",
    val supportsJsonMode: Boolean = true,
    val structuredMode: StructuredMode = StructuredMode.AUTO,
    val routingSort: ProviderSort = OpenRouterDefaults.ROUTING.sort,
    val routingFallbacks: String = "",
    val routingRequireParameters: Boolean = OpenRouterDefaults.ROUTING.requireParameters,
    val editorProviderId: String = "default",
    val editorModel: String = "",
    val drafterProviderId: String = "default",
    val drafterModel: String = "",
    val editorMaxTokens: String = DEFAULT_EDITOR_MAX_TOKENS,
    val drafterMaxTokens: String = DEFAULT_DRAFTER_MAX_TOKENS,
    val topN: String = "30",
    val schedule: String = "10:00",
    val wifiOnly: Boolean = false,
    val retention: String = "14",
    val articleRetention: String = "30",
    val reportRetention: String = "45",
    val sweepInterval: String = "120",
    val useLegacySingleShotFetch: Boolean = false,
    val part2Mode: Part2Mode = Part2Mode.FULL,
    val tokenBudget: String = "1000000",
    val maxLlmCalls: String = "20",
    val llmConnectTimeoutSeconds: String = "1200",
    val llmReadTimeoutSeconds: String = "1200",
    val llmCallTimeoutSeconds: String = "1200",
    val feedbackText: String = "",
) : java.io.Serializable

private val DEFAULT_EDITOR_MAX_TOKENS = RoleModelDefaults.EDITOR_MAX_TOKENS.toString()
private val DEFAULT_DRAFTER_MAX_TOKENS = RoleModelDefaults.DRAFTER_MAX_TOKENS.toString()

enum class SettingsSection { OVERVIEW, PROVIDERS, SCHEDULE, PIPELINE, DATA }

data class SettingsUiState(
    val form: SettingsFormState = SettingsFormState(),
    val savedProviders: ProviderSettings? = null,
    val config: PipelineConfig = PipelineConfig(),
    val monthTokens: Long? = null,
    val providerMessage: String? = null,
    val importMessage: String? = null,
    val busy: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val section: SettingsSection = SettingsSection.OVERVIEW,
)

private data class SettingsExtras(
    val monthTokens: Long?,
    val providerMessage: String?,
    val importMessage: String?,
    val busy: Boolean,
)

class SettingsViewModel(
    private val providerSettings: ProviderSettingsRepository,
    private val vault: ApiKeyVault,
    private val configRepository: PipelineConfigRepository,
    llmCalls: LlmCallRepository,
    private val importer: StateImporter,
    private val stateBackups: StateBackupRepository,
    private val testConnection: suspend (String, String) -> Unit,
    private val scheduleReports: (PipelineConfig) -> Unit,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val form = MutableStateFlow(
        (savedState[FORM_KEY] ?: SettingsFormState()).forSavedState(),
    )
    private val section = MutableStateFlow(savedState[SECTION_KEY] ?: SettingsSection.OVERVIEW)
    private val monthTokens = MutableStateFlow<Long?>(null)
    private val providerMessage = MutableStateFlow<String?>(null)
    private val importMessage = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private var initialized = savedState.contains(FORM_KEY)

    val state: StateFlow<SettingsUiState> = combine(
        form,
        providerSettings.settings,
        configRepository.config,
        combine(monthTokens, providerMessage, importMessage, busy, ::SettingsExtras),
        section,
    ) { formState, providers, config, extras, selectedSection ->
        SettingsUiState(
            formState,
            providers,
            config,
            extras.monthTokens,
            extras.providerMessage,
            extras.importMessage,
            extras.busy,
            settingsValidationErrors(formState),
            selectedSection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            combine(providerSettings.settings, configRepository.config, ::Pair).collect { (providers, config) ->
                if (!initialized) {
                    setForm(SettingsFormState(
                        editorProviderId = providers.mapping.editor.providerId,
                        editorModel = providers.mapping.editor.model,
                        drafterProviderId = providers.mapping.drafter.providerId,
                        drafterModel = providers.mapping.drafter.model,
                        editorMaxTokens = providers.mapping.editor.maxTokens.toString(),
                        drafterMaxTokens = providers.mapping.drafter.maxTokens.toString(),
                        topN = config.part1MaxItems.toString(),
                        schedule = config.scheduleTime,
                        wifiOnly = config.wifiOnlyPageEnrichment,
                        retention = config.artifactRetentionDays.toString(),
                        articleRetention = config.articleRetentionDays.toString(),
                        reportRetention = config.reportRetentionDays.toString(),
                        sweepInterval = config.sweepIntervalMinutes.toString(),
                        useLegacySingleShotFetch = config.useLegacySingleShotFetch,
                        part2Mode = config.part2Mode,
                        tokenBudget = config.monthlyTokenBudget.toString(),
                        maxLlmCalls = config.maxLlmCallsPerRun.toString(),
                        llmConnectTimeoutSeconds = config.llmExecution.connectTimeoutSeconds.toString(),
                        llmReadTimeoutSeconds = config.llmExecution.readTimeoutSeconds.toString(),
                        llmCallTimeoutSeconds = config.llmExecution.callTimeoutSeconds.toString(),
                        feedbackText = config.editorFeedback.joinToString("\n"),
                    ))
                    initialized = true
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { monthTokens.value = llmCalls.tokensThisMonth() }
    }

    fun update(transform: (SettingsFormState) -> SettingsFormState) { setForm(transform(form.value)) }

    fun selectProviderType(type: ProviderType) = update { it.withProviderType(type) }

    fun selectSection(value: SettingsSection) {
        section.value = value
        savedState[SECTION_KEY] = value
    }

    fun saveProvider() = launchOperation {
        val value = form.value
        providerSettings.upsertProvider(
            value.providerId,
            value.providerType,
            value.baseUrl,
            value.apiKey,
            value.supportsJsonMode,
            vault,
            value.structuredMode,
            ProviderRouting(
                modelFallbacks = value.routingFallbacks.split(',', '\n'),
                sort = value.routingSort,
                requireParameters = value.routingRequireParameters,
            ),
        )
        setForm(value.copy(apiKey = ""))
        providerMessage.value = "Provider ${value.providerId.trim()} 已保存；API key 不会进入日志或导出。"
    }

    fun testProvider() = launchOperation {
        val value = form.value
        testConnection(value.providerId, value.editorModel.ifBlank { value.drafterModel })
        providerMessage.value = "Provider ${value.providerId.trim()} 连接成功"
    }

    fun saveRoleMapping() = launchOperation {
        val value = form.value
        require(value.editorMaxTokens.toIntOrNull() in MAX_TOKENS_RANGE) { "EDITOR maxTokens 必须在 $MAX_TOKENS_HINT 之间" }
        require(value.drafterMaxTokens.toIntOrNull() in MAX_TOKENS_RANGE) { "DRAFTER maxTokens 必须在 $MAX_TOKENS_HINT 之间" }
        providerSettings.updateRoleMapping(
            value.editorProviderId,
            value.editorModel,
            value.drafterProviderId,
            value.drafterModel,
            value.editorMaxTokens.toIntOrNull() ?: RoleModelDefaults.EDITOR_MAX_TOKENS,
            value.drafterMaxTokens.toIntOrNull() ?: RoleModelDefaults.DRAFTER_MAX_TOKENS,
        )
        providerMessage.value = "EDITOR / DRAFTER 映射已保存"
    }

    fun savePipeline() = launchOperation {
        val value = form.value
        require(settingsValidationErrors(value).isEmpty()) { settingsValidationErrors(value).values.joinToString("；") }
        val config = value.applyTo(configRepository.config.first())
        configRepository.save(config)
        scheduleReports(config)
        providerMessage.value = "生成流程配置已保存"
    }

    fun importSeenLinks(readBytes: suspend () -> ByteArray?) = launchImport {
        readBytes()?.let { bytes -> ByteArrayInputStream(bytes).use { importer.importSeenLinks(it) } }
            ?: error("无法读取所选文件")
    }

    fun importCache(readBytes: suspend () -> ByteArray?) = launchImport {
        readBytes()?.let { bytes -> ByteArrayInputStream(bytes).use { importer.importEditorialCache(it) } }
            ?: error("无法读取所选文件")
    }

    fun exportDeviceState(write: suspend (suspend (OutputStream) -> Unit) -> Boolean) = launchStateTransfer {
        lateinit var detail: String
        check(write { output ->
            val summary = stateBackups.exportZip(output)
            detail = "已导出 ${summary.articles} 篇文章、${summary.reports} 份报告、${summary.favorites} 条收藏"
        }) { "无法写入所选文件" }
        detail
    }

    fun importDeviceState(readBytes: suspend () -> ByteArray?) = launchStateTransfer {
        val payload = readBytes() ?: error("无法读取所选文件")
        val summary = stateBackups.importZip(payload)
        "已恢复 ${summary.articles} 篇文章、${summary.reports} 份报告、${summary.favorites} 条收藏"
    }

    private fun launchImport(block: suspend () -> Int) = launchOperation {
        importMessage.value = "已导入 ${block()} 条记录"
    }

    private fun launchStateTransfer(block: suspend () -> String) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            runCatching { block() }
                .onSuccess { importMessage.value = it }
                .onFailure { importMessage.value = it.message ?: it::class.simpleName }
            busy.value = false
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            runCatching { block() }.onFailure { providerMessage.value = it.message ?: it::class.simpleName }
            busy.value = false
        }
    }

    private fun setForm(value: SettingsFormState) {
        form.value = value
        // Never copy provider credentials into Activity saved-state bundles.
        savedState[FORM_KEY] = value.forSavedState()
    }

    companion object {
        private const val FORM_KEY = "settings-form"
        private const val SECTION_KEY = "settings-section"
    }
}

internal fun SettingsFormState.forSavedState(): SettingsFormState = copy(apiKey = "")

internal fun SettingsFormState.withProviderType(type: ProviderType): SettingsFormState {
    if (type == providerType) return this
    val routing = type.defaultRouting()
    return copy(
        providerType = type,
        baseUrl = type.adjustedBaseUrl(providerType, baseUrl),
        supportsJsonMode = type.defaultSupportsJsonMode(),
        routingSort = routing.sort,
        routingFallbacks = if (type == ProviderType.OPENROUTER) routingFallbacks else "",
        routingRequireParameters = routing.requireParameters,
    )
}

internal fun settingsValidationErrors(value: SettingsFormState): Map<String, String> = buildMap {
    if (!isValidScheduleTime(value.schedule)) put("schedule", "请输入 00:00–23:59 的 HH:mm 时间")
    if (value.topN.toIntOrNull() !in 10..50) put("topN", "请输入 10–50")
    if (value.retention.toIntOrNull() !in 1..365) put("retention", "请输入 1–365 天")
    if (value.articleRetention.toIntOrNull() !in 1..365) put("articleRetention", "请输入 1–365 天")
    if (value.reportRetention.toIntOrNull() !in 7..365) put("reportRetention", "请输入 7–365 天")
    if (value.sweepInterval.toIntOrNull() !in 15..360) put("sweepInterval", "请输入 15–360 分钟")
    if (value.tokenBudget.toLongOrNull()?.let { it >= 0 } != true) put("tokenBudget", "请输入 0 或更大的整数")
    if (value.maxLlmCalls.toIntOrNull() !in 4..100) put("maxLlmCalls", "请输入 4–100")
    if (value.llmConnectTimeoutSeconds.toIntOrNull() !in 5..1_200) put("llmConnectTimeoutSeconds", "请输入 5–1200 秒")
    if (value.llmReadTimeoutSeconds.toIntOrNull() !in 30..1_200) put("llmReadTimeoutSeconds", "请输入 30–1200 秒")
    val readTimeout = value.llmReadTimeoutSeconds.toIntOrNull()
    val callTimeout = value.llmCallTimeoutSeconds.toIntOrNull()
    if (callTimeout !in 60..1_200 || (readTimeout != null && callTimeout != null && callTimeout < readTimeout)) {
        put("llmCallTimeoutSeconds", "请输入 60–1200 秒，且不得小于 read timeout")
    }
    if (value.editorMaxTokens.toIntOrNull() !in MAX_TOKENS_RANGE) put("editorMaxTokens", "请输入 $MAX_TOKENS_HINT")
    if (value.drafterMaxTokens.toIntOrNull() !in MAX_TOKENS_RANGE) put("drafterMaxTokens", "请输入 $MAX_TOKENS_HINT")
}

private val MAX_TOKENS_RANGE = RoleModelDefaults.MIN_MAX_TOKENS..RoleModelDefaults.MAX_MAX_TOKENS
private const val MAX_TOKENS_HINT = "512–65536"

internal fun SettingsFormState.applyTo(base: PipelineConfig): PipelineConfig {
    require(isValidScheduleTime(schedule)) { "计划时间必须使用 HH:mm 格式（00:00–23:59）" }
    return base.copy(
        part1MaxItems = topN.toIntOrNull() ?: 30,
        scheduleTime = schedule,
        wifiOnlyPageEnrichment = wifiOnly,
        artifactRetentionDays = retention.toIntOrNull() ?: 14,
        articleRetentionDays = articleRetention.toIntOrNull() ?: 30,
        reportRetentionDays = reportRetention.toIntOrNull() ?: 45,
        sweepIntervalMinutes = sweepInterval.toIntOrNull() ?: 120,
        useLegacySingleShotFetch = useLegacySingleShotFetch,
        part2Mode = part2Mode,
        monthlyTokenBudget = tokenBudget.toLongOrNull() ?: 1_000_000,
        maxLlmCallsPerRun = maxLlmCalls.toIntOrNull() ?: 20,
        llmExecution = LlmExecutionConfig(
            connectTimeoutSeconds = llmConnectTimeoutSeconds.toIntOrNull() ?: 1_200,
            readTimeoutSeconds = llmReadTimeoutSeconds.toIntOrNull() ?: 1_200,
            callTimeoutSeconds = llmCallTimeoutSeconds.toIntOrNull() ?: 1_200,
        ),
        editorFeedback = feedbackText.lines(),
    ).normalized()
}
