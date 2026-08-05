package com.dailynews.app.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.config.ApiKeyVault
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.config.ProviderSettingsRepository
import com.dailynews.llm.ProviderType
import com.dailynews.model.isValidScheduleTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, NOTIFICATIONS, PROVIDER, SCHEDULE, COMPLETE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val type: ProviderType = ProviderType.OPENAI_COMPAT,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiKey: String = "",
    val schedule: String = "10:00",
    val providerSkipped: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
) : java.io.Serializable

class OnboardingViewModel(
    private val providers: ProviderSettingsRepository,
    private val vault: ApiKeyVault,
    private val config: PipelineConfigRepository,
    private val scheduleReports: (String) -> Unit,
    private val runNow: () -> Unit = {},
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        (savedState[STATE_KEY] ?: OnboardingUiState()).forSavedState(),
    )
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    fun update(transform: (OnboardingUiState) -> OnboardingUiState) = setState(transform(mutableState.value))
    fun start() = setState(mutableState.value.copy(step = OnboardingStep.NOTIFICATIONS))
    fun notificationsDone() = setState(mutableState.value.copy(step = OnboardingStep.PROVIDER))

    fun saveProvider() {
        val form = mutableState.value
        if (form.baseUrl.isBlank() || form.model.isBlank() || form.apiKey.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            setState(form.copy(busy = true, message = null))
            runCatching { providers.configureSingleProvider(form.type, form.baseUrl, form.model, form.apiKey, vault) }
                .onSuccess { setState(form.copy(apiKey = "", busy = false, providerSkipped = false, step = OnboardingStep.SCHEDULE)) }
                .onFailure { setState(form.copy(busy = false, message = "Provider 配置失败：${it.message ?: it::class.simpleName}")) }
        }
    }

    fun skipProvider() = setState(
        mutableState.value.copy(
            providerSkipped = true,
            step = OnboardingStep.SCHEDULE,
            message = "已跳过 Provider；编辑分支将保持 fail closed，稍后可在设置中配置。",
        ),
    )

    fun saveSchedule() {
        val form = mutableState.value
        viewModelScope.launch(Dispatchers.IO) {
            setState(form.copy(busy = true, message = null))
            runCatching {
                require(isValidScheduleTime(form.schedule)) { "计划时间必须使用 HH:mm 格式（00:00–23:59）" }
                val normalized = config.config.first().copy(scheduleTime = form.schedule).normalized()
                config.save(normalized)
                scheduleReports(normalized.scheduleTime)
            }.onSuccess {
                setState(form.copy(busy = false, step = OnboardingStep.COMPLETE))
            }.onFailure {
                setState(form.copy(busy = false, message = "计划保存失败：${it.message ?: it::class.simpleName}"))
            }
        }
    }

    fun finish(startRun: Boolean) {
        providers.completeOnboarding()
        if (startRun) runNow()
    }

    private fun setState(value: OnboardingUiState) {
        mutableState.value = value
        // API keys belong only in the in-memory form and ApiKeyVault. Persisting a
        // keystroke into SavedStateHandle would copy plaintext into the Activity
        // saved-state bundle owned by system_server.
        savedState[STATE_KEY] = value.forSavedState()
    }

    companion object { private const val STATE_KEY = "onboarding-state" }
}

internal fun OnboardingUiState.forSavedState(): OnboardingUiState = copy(apiKey = "")
