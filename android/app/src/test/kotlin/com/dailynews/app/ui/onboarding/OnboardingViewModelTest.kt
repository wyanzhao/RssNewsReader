package com.dailynews.app.ui.onboarding

import com.dailynews.llm.OpenRouterDefaults
import com.dailynews.llm.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingViewModelTest {
    @Test
    fun providerApiKeyIsScrubbedFromSavedStateCopy() {
        val form = OnboardingUiState(apiKey = "plaintext-secret", model = "model")

        val saved = form.forSavedState()

        assertEquals("", saved.apiKey)
        assertEquals("model", saved.model)
    }

    @Test
    fun switchingProviderTypePrefillsOfficialBaseUrl() {
        val start = OnboardingUiState()
        assertEquals(ProviderType.OPENROUTER, start.type)
        assertEquals(OpenRouterDefaults.BASE_URL, start.baseUrl)
        assertEquals("https://api.openai.com/v1", start.withProviderType(ProviderType.OPENAI_COMPAT).baseUrl)
        assertEquals("https://api.anthropic.com", start.withProviderType(ProviderType.ANTHROPIC).baseUrl)
    }
}
