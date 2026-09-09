package com.dailynews.data.config

import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderType
import com.dailynews.llm.ReasoningEffort
import com.dailynews.llm.RoleModel
import com.dailynews.llm.RoleModelMapping
import com.dailynews.model.ArtifactJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromString

class ProviderSettingsValidatorTest {
    @Test
    fun providerIdIsTrimmedAndRestrictedToKeySafeCharacters() {
        assertEquals("team.open_ai-1", ProviderSettingsValidator.normalizeId(" team.open_ai-1 "))
        assertFailsWith<IllegalArgumentException> { ProviderSettingsValidator.normalizeId("unsafe/id") }
        assertFailsWith<IllegalArgumentException> { ProviderSettingsValidator.normalizeId(" ") }
    }

    @Test
    fun roleMappingRequiresKnownProvidersAndBothModels() {
        val settings = ProviderSettings(
            providers = listOf(ProviderConfig("known", ProviderType.OPENAI_COMPAT, "https://example.com/v1/chat/completions", "provider-known")),
            mapping = RoleModelMapping(RoleModel("known", "editor", 8_192), RoleModel("known", "drafter", 4_096)),
        )
        ProviderSettingsValidator.requireMapping(settings, "known", "known", "editor", "drafter")
        assertFailsWith<IllegalArgumentException> {
            ProviderSettingsValidator.requireMapping(settings, "missing", "known", "editor", "drafter")
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderSettingsValidator.requireMapping(settings, "known", "known", "", "drafter")
        }
    }

    @Test
    fun missingReasoningEffortDecodesAsLow() {
        val json = """
            {
              "providers": [],
              "mapping": {
                "editor": {"providerId": "default", "model": "editor-model", "maxTokens": 8192},
                "drafter": {"providerId": "default", "model": "drafter-model", "maxTokens": 4096}
              }
            }
        """.trimIndent()

        val decoded = ArtifactJson.codec.decodeFromString<ProviderSettings>(json)

        assertEquals(ReasoningEffort.LOW, decoded.mapping.editor.reasoningEffort)
        assertEquals(ReasoningEffort.LOW, decoded.mapping.drafter.reasoningEffort)
        assertEquals(ReasoningEffort.LOW, RoleModel("default", "model", 8_192).reasoningEffort)
    }

    @Test
    fun removableProviderIsReturnedWithIdNormalized() {
        val settings = settingsWith("spare")
        assertEquals("spare", ProviderSettingsValidator.requireRemovable(settings, " spare ").id)
    }

    @Test
    fun removingUnknownProviderFails() {
        val settings = settingsWith("spare")
        val error = assertFailsWith<IllegalStateException> {
            ProviderSettingsValidator.requireRemovable(settings, "missing")
        }
        assertEquals("服务 missing 不存在", error.message)
    }

    @Test
    fun providerReferencedByEditorRoleCannotBeRemoved() {
        val settings = settingsWith("active", editorRole = "active", drafterRole = "elsewhere")
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderSettingsValidator.requireRemovable(settings, "active")
        }
        assertEquals("服务 active 正被这些角色使用：新闻精选。请先在模型设置中更换服务再删除", error.message)
    }

    @Test
    fun providerReferencedByDrafterRoleCannotBeRemoved() {
        val settings = settingsWith("legacy", editorRole = "elsewhere", drafterRole = "legacy")
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderSettingsValidator.requireRemovable(settings, "legacy")
        }
        assertEquals("服务 legacy 正被这些角色使用：Part 2。请先在模型设置中更换服务再删除", error.message)
    }

    @Test
    fun providerReferencedByBothRolesNamesThemBoth() {
        val settings = settingsWith("only", editorRole = "only", drafterRole = "only")
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderSettingsValidator.requireRemovable(settings, "only")
        }
        assertEquals("服务 only 正被这些角色使用：新闻精选、Part 2。请先在模型设置中更换服务再删除", error.message)
    }

    private fun settingsWith(
        vararg providerIds: String,
        editorRole: String = "editor-provider",
        drafterRole: String = "drafter-provider",
    ): ProviderSettings = ProviderSettings(
        providers = providerIds.map { id ->
            ProviderConfig(id, ProviderType.OPENAI_COMPAT, "https://example.com/v1/chat/completions", "provider-$id")
        },
        mapping = RoleModelMapping(RoleModel(editorRole, "editor", 8_192), RoleModel(drafterRole, "drafter", 4_096)),
    )
}
