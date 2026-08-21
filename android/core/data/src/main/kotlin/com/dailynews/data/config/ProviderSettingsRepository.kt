package com.dailynews.data.config

import android.content.Context
import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderEndpoints
import com.dailynews.llm.ProviderType
import com.dailynews.llm.ProviderRouting
import com.dailynews.llm.RoleModel
import com.dailynews.llm.RoleModelDefaults
import com.dailynews.llm.RoleModelMapping
import com.dailynews.llm.StructuredMode
import com.dailynews.model.ArtifactJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class ProviderSettings(
    val providers: List<ProviderConfig>,
    val mapping: RoleModelMapping,
)

fun clampMaxTokens(value: Int): Int =
    value.coerceIn(RoleModelDefaults.MIN_MAX_TOKENS, RoleModelDefaults.MAX_MAX_TOKENS)

object ProviderSettingsValidator {
    private val providerIdPattern = Regex("[A-Za-z0-9._-]+")

    fun normalizeId(id: String): String = id.trim().also {
        require(it.matches(providerIdPattern)) { "provider id must use letters, digits, dot, underscore, or dash" }
    }

    fun requireMapping(settings: ProviderSettings, editorProviderId: String, drafterProviderId: String, editorModel: String, drafterModel: String) {
        require(settings.providers.any { it.id == editorProviderId }) { "unknown editor provider" }
        require(settings.providers.any { it.id == drafterProviderId }) { "unknown drafter provider" }
        require(editorModel.isNotBlank() && drafterModel.isNotBlank()) { "both role models are required" }
    }
}

class ProviderSettingsRepository(context: Context) {
    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.applicationContext.getSharedPreferences("provider_settings", Context.MODE_PRIVATE)
    }
    private val settingsState by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MutableStateFlow(readFromDisk()) }
    private val onboardingState by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MutableStateFlow(preferences.getBoolean("onboarding_complete", false))
    }
    val settings: StateFlow<ProviderSettings> get() = settingsState.asStateFlow()
    val onboardingComplete: StateFlow<Boolean> get() = onboardingState.asStateFlow()

    fun load(): ProviderSettings = settingsState.value

    private fun readFromDisk(): ProviderSettings {
        val saved = preferences.getString("settings", null)
        return saved?.let { runCatching { ArtifactJson.codec.decodeFromString<ProviderSettings>(it) }.getOrNull() }
            ?: defaultSettings()
    }

    private fun defaultSettings() = ProviderSettings(
        providers = emptyList(),
        mapping = RoleModelMapping(
            editor = RoleModel("default", "", RoleModelDefaults.EDITOR_MAX_TOKENS),
            drafter = RoleModel("default", "", RoleModelDefaults.DRAFTER_MAX_TOKENS),
        ),
    )

    fun save(settings: ProviderSettings) {
        preferences.edit().putString("settings", ArtifactJson.compact.encodeToString(settings)).apply()
        settingsState.value = settings
    }

    fun completeOnboarding() = setOnboardingComplete(true)

    fun setOnboardingComplete(complete: Boolean) {
        preferences.edit().putBoolean("onboarding_complete", complete).apply()
        onboardingState.value = complete
    }

    fun configureSingleProvider(type: ProviderType, baseUrl: String, model: String, key: String, vault: ApiKeyVault) {
        val id = "default"
        val alias = "provider-default"
        require(model.isNotBlank()) { "model is required" }
        require(key.isNotBlank()) { "API key is required" }
        vault.write(alias, key)
        save(
            ProviderSettings(
                listOf(
                    ProviderConfig(
                        id,
                        type,
                        if (type == ProviderType.OPENAI_COMPAT) ProviderEndpoints.openAi(baseUrl) else ProviderEndpoints.anthropic(baseUrl),
                        alias,
                        supportsJsonMode = type == ProviderType.OPENAI_COMPAT,
                    ),
                ),
                RoleModelMapping(
                    RoleModel(id, model.trim(), RoleModelDefaults.EDITOR_MAX_TOKENS),
                    RoleModel(id, model.trim(), RoleModelDefaults.DRAFTER_MAX_TOKENS),
                ),
            ),
        )
    }

    fun upsertProvider(
        id: String,
        type: ProviderType,
        baseUrl: String,
        key: String,
        supportsJsonMode: Boolean,
        vault: ApiKeyVault,
        structuredMode: StructuredMode = StructuredMode.AUTO,
        routing: ProviderRouting = ProviderRouting(),
    ): ProviderSettings {
        val cleanId = ProviderSettingsValidator.normalizeId(id)
        val alias = "provider-$cleanId"
        if (key.isNotBlank()) vault.write(alias, key)
        val current = load()
        val normalizedUrl = when (type) {
            ProviderType.OPENAI_COMPAT -> ProviderEndpoints.openAi(baseUrl)
            ProviderType.ANTHROPIC -> ProviderEndpoints.anthropic(baseUrl)
        }
        val provider = ProviderConfig(cleanId, type, normalizedUrl, alias, supportsJsonMode, structuredMode, routing.normalized())
        val updated = current.copy(providers = (current.providers.filterNot { it.id == cleanId } + provider).sortedBy { it.id })
        save(updated)
        return updated
    }

    fun updateRoleMapping(
        editorProviderId: String,
        editorModel: String,
        drafterProviderId: String,
        drafterModel: String,
        editorMaxTokens: Int,
        drafterMaxTokens: Int,
    ): ProviderSettings {
        val current = load()
        ProviderSettingsValidator.requireMapping(current, editorProviderId, drafterProviderId, editorModel, drafterModel)
        val updated = current.copy(
            mapping = RoleModelMapping(
                RoleModel(editorProviderId, editorModel.trim(), clampMaxTokens(editorMaxTokens)),
                RoleModel(drafterProviderId, drafterModel.trim(), clampMaxTokens(drafterMaxTokens)),
            ),
        )
        save(updated)
        return updated
    }
}
