package com.dailynews.app.ui.settings

import com.dailynews.model.ContextBudgetConfig
import com.dailynews.model.FetchConfig
import com.dailynews.model.PipelineConfig
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.isValidScheduleTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @Test
    fun formalGenerationDefaultsUseTwentyMinutesAndFullTokenCaps() {
        val form = SettingsFormState()
        val execution = LlmExecutionConfig()

        assertEquals("1200", form.llmConnectTimeoutSeconds)
        assertEquals("1200", form.llmReadTimeoutSeconds)
        assertEquals("1200", form.llmCallTimeoutSeconds)
        assertEquals("65536", form.editorMaxTokens)
        assertEquals("65536", form.drafterMaxTokens)
        assertEquals(listOf(1_200, 1_200, 1_200), listOf(
            execution.connectTimeoutSeconds,
            execution.readTimeoutSeconds,
            execution.callTimeoutSeconds,
        ))
        assertEquals(listOf(65_536, 65_536, 65_536), listOf(
            execution.part1ShortlistMaxTokens,
            execution.part1PlanMaxTokens,
            execution.part2BatchMaxTokens,
        ))
    }

    @Test
    fun formUpdatePreservesConfigurationWithoutUiFields() {
        val stored = PipelineConfig(
            fetch = FetchConfig(hours = 72, maxSummary = 777, staleFeedWarnDays = 45),
            contextBudget = ContextBudgetConfig(
                llmContextMaxBytes = 321_000,
                part1BriefMaxBytes = 123_000,
                part2ContextMaxBytes = 124_000,
                totalContextMaxBytes = 456_000,
                hardBlock = true,
            ),
            llmExecution = LlmExecutionConfig(20, 240, 480, 2_048, 12_288, 6_144),
        )

        val updated = SettingsFormState(
            topN = "25",
            schedule = "09:30",
            sweepInterval = "75",
            useLegacySingleShotFetch = true,
        ).applyTo(stored)

        assertEquals(stored.fetch, updated.fetch)
        assertEquals(stored.contextBudget, updated.contextBudget)
        assertEquals(LlmExecutionConfig(), updated.llmExecution)
        assertEquals(25, updated.part1MaxItems)
        assertEquals("09:30", updated.scheduleTime)
        assertEquals(75, updated.sweepIntervalMinutes)
        assertTrue(updated.useLegacySingleShotFetch)
    }

    @Test
    fun invalidScheduleIsRejectedInsteadOfSilentlyFallingBack() {
        assertTrue(isValidScheduleTime("00:00"))
        assertTrue(isValidScheduleTime("23:59"))
        assertFalse(isValidScheduleTime("24:00"))
        assertFalse(isValidScheduleTime("10:60"))
        assertFailsWith<IllegalArgumentException> {
            SettingsFormState(schedule = "9:00").applyTo(PipelineConfig())
        }
    }

    @Test
    fun sweepIntervalIsClampedToWorkManagerRange() {
        assertEquals(15, SettingsFormState(sweepInterval = "1").applyTo(PipelineConfig()).sweepIntervalMinutes)
        assertEquals(360, SettingsFormState(sweepInterval = "999").applyTo(PipelineConfig()).sweepIntervalMinutes)
    }

    @Test
    fun part2ModeDefaultsToFullAndExplicitLazySelectionIsPersisted() {
        assertEquals(Part2Mode.FULL, SettingsFormState().applyTo(PipelineConfig()).part2Mode)
        assertEquals(
            Part2Mode.LAZY,
            SettingsFormState(part2Mode = Part2Mode.LAZY).applyTo(PipelineConfig()).part2Mode,
        )
    }

    @Test
    fun llmExecutionSettingsPersistIndependentTimeoutsAndOperationCaps() {
        val updated = SettingsFormState(
            llmConnectTimeoutSeconds = "25",
            llmReadTimeoutSeconds = "240",
            llmCallTimeoutSeconds = "480",
            part1ShortlistMaxTokens = "2048",
            part1PlanMaxTokens = "12288",
            part2BatchMaxTokens = "6144",
        ).applyTo(PipelineConfig())

        assertEquals(LlmExecutionConfig(25, 240, 480, 2_048, 12_288, 6_144), updated.llmExecution)
    }

    @Test
    fun providerApiKeyIsScrubbedFromSavedStateCopy() {
        val form = SettingsFormState(apiKey = "plaintext-secret", baseUrl = "https://api.example")

        val saved = form.forSavedState()

        assertEquals("", saved.apiKey)
        assertEquals(form.baseUrl, saved.baseUrl)
    }
}
