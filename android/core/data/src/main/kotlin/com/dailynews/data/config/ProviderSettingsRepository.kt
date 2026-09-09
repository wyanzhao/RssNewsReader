package com.dailynews.data.config

import android.content.Context
import com.dailynews.llm.OpenRouterDefaults
import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderType
import com.dailynews.llm.ProviderRouting
import com.dailynews.llm.ReasoningEffort
import com.dailynews.llm.RoleModel
import com.dailynews.llm.RoleModelDefaults
import com.dailynews.llm.RoleModelMapping
import com.dailynews.llm.StructuredMode
import com.dailynews.llm.canonicalize
import com.dailynews.llm.canonicalizeProviders
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

    /**
     * Returns the provider to delete, or throws when removal must not proceed.
     *
     * Deleting a provider that a role mapping still points at would leave
     * [requireMapping] failing on the next generation run, so the caller is
     * told to switch the role model first. Messages are user-facing Chinese
     * because they surface verbatim in the settings snackbar.
     */
    fun requireRemovable(settings: ProviderSettings, rawId: String): ProviderConfig {
        val id = normalizeId(rawId)
        val provider = settings.providers.firstOrNull { it.id == id }
            ?: error("服务 $id 不存在")
        val roles = buildList {
            if (settings.mapping.editor.providerId == id) add("新闻精选")
            if (settings.mapping.drafter.providerId == id) add("Part 2")
        }
        require(roles.isEmpty()) { "服务 $id 正被这些角色使用：${roles.joinToString("、")}。请先在模型设置中更换服务再删除" }
        return provider
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
        val parsed = saved?.let { runCatching { ArtifactJson.codec.decodeFromString<ProviderSettings>(it) }.getOrNull() }
            ?: return defaultSettings()
        val canonical = parsed.copy(providers = parsed.providers.canonicalizeProviders())
        if (canonical != parsed) {
            preferences.edit().putString("settings", ArtifactJson.compact.encodeToString(canonical)).apply()
        }
        return canonical
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
                        type.chatEndpoint(baseUrl),
                        alias,
                        supportsJsonMode = type.defaultSupportsJsonMode(),
                        routing = type.defaultRouting(),
                    ).canonicalize(),
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
        val current = load()
        val existing = current.providers.firstOrNull { it.id == cleanId }
        val alias = existing?.apiKeyAlias ?: "provider-$cleanId"
        require(existing != null || key.isNotBlank()) { "新增服务需要 API key" }
        val storedRouting = if (type == ProviderType.OPENROUTER) {
            val normalized = routing.normalized()
            if (normalized.isDefault) OpenRouterDefaults.ROUTING else normalized
        } else {
            ProviderRouting()
        }
        val provider = ProviderConfig(
            cleanId,
            type,
            type.chatEndpoint(baseUrl),
            alias,
            supportsJsonMode = type.usesOpenAiCompatApi && supportsJsonMode,
            structuredMode,
            storedRouting,
        ).canonicalize()
        if (key.isNotBlank()) vault.write(alias, key)
        val updated = current.copy(providers = (current.providers.filterNot { it.id == cleanId } + provider).sortedBy { it.id })
        save(updated)
        return updated
    }

    /**
     * Deletes the provider and its stored API key. Removal is refused while the
     * editor / drafter role mapping still references the provider; the key alias
     * is cleared only after the settings write succeeds, so a failure here never
     * leaves a saved provider without its key. Returns the removed provider.
     */
    fun removeProvider(id: String, vault: ApiKeyVault): ProviderConfig {
        val current = load()
        val removed = ProviderSettingsValidator.requireRemovable(current, id)
        save(current.copy(providers = current.providers.filterNot { it.id == removed.id }))
        vault.delete(removed.apiKeyAlias)
        return removed
    }

    fun updateRoleMapping(
        editorProviderId: String,
        editorModel: String,
        drafterProviderId: String,
        drafterModel: String,
        editorMaxTokens: Int,
        drafterMaxTokens: Int,
        editorReasoningEffort: ReasoningEffort = RoleModelDefaults.REASONING_EFFORT,
        drafterReasoningEffort: ReasoningEffort = RoleModelDefaults.REASONING_EFFORT,
    ): ProviderSettings {
        val current = load()
        ProviderSettingsValidator.requireMapping(current, editorProviderId, drafterProviderId, editorModel, drafterModel)
        val updated = current.copy(
            mapping = RoleModelMapping(
                RoleModel(editorProviderId, editorModel.trim(), clampMaxTokens(editorMaxTokens), editorReasoningEffort),
                RoleModel(drafterProviderId, drafterModel.trim(), clampMaxTokens(drafterMaxTokens), drafterReasoningEffort),
            ),
        )
        save(updated)
        return updated
    }
}
