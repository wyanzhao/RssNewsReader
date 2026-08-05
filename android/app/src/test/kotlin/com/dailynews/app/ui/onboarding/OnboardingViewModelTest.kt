package com.dailynews.app.ui.onboarding

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
}
