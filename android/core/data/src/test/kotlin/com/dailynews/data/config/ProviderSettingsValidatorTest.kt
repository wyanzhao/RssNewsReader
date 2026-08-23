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
}
