package com.dailynews.data.config

import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderType
import com.dailynews.llm.RoleModel
import com.dailynews.llm.RoleModelMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
