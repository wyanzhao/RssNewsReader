package com.dailynews.app.ui.settings

import com.dailynews.llm.OpenRouterDefaults
import com.dailynews.llm.ProviderSort
import com.dailynews.llm.ProviderType
import com.dailynews.llm.RoleModelDefaults
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
    fun formalGenerationDefaultsUseTwentyMinutesAndRoleTokenCaps() {
        val form = SettingsFormState()
        val execution = LlmExecutionConfig()

        assertEquals("1200", form.llmConnectTimeoutSeconds)
        assertEquals("1200", form.llmReadTimeoutSeconds)
        assertEquals("1200", form.llmCallTimeoutSeconds)
        // 表单默认必须能通过表单自己的校验：把某个角色的默认上限调到允许区间之外，
        // 用户一打开设置页就会看到一条无法保存的报错，而不是任何有用的提示。
        assertEquals(RoleModelDefaults.EDITOR_MAX_TOKENS, form.editorMaxTokens.toInt())
        assertEquals(RoleModelDefaults.DRAFTER_MAX_TOKENS, form.drafterMaxTokens.toInt())
        assertTrue(settingsValidationErrors(form).isEmpty(), settingsValidationErrors(form).toString())
        // EDITOR 一次要写满 Top N 条中文摘要，DRAFTER 只写一批短摘要。
        assertTrue(RoleModelDefaults.EDITOR_MAX_TOKENS > RoleModelDefaults.DRAFTER_MAX_TOKENS)
        assertEquals(listOf(1_200, 1_200, 1_200), listOf(
            execution.connectTimeoutSeconds,
            execution.readTimeoutSeconds,
            execution.callTimeoutSeconds,
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
            llmExecution = LlmExecutionConfig(20, 240, 480),
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
    fun part2ModeIsForcedLazyByNormalizationRegardlessOfFormSelection() {
        // Epic U：normalized() 强制 LAZY，无论表单选什么、落盘值是什么。
        assertEquals(Part2Mode.LAZY, SettingsFormState().applyTo(PipelineConfig()).part2Mode)
        assertEquals(
            Part2Mode.LAZY,
            SettingsFormState(part2Mode = Part2Mode.FULL).applyTo(PipelineConfig(part2Mode = Part2Mode.FULL)).part2Mode,
        )
        assertEquals(Part2Mode.LAZY, PipelineConfig(part2Mode = Part2Mode.FULL).normalized().part2Mode)
    }

    @Test
    fun llmExecutionSettingsPersistIndependentTimeouts() {
        val updated = SettingsFormState(
            llmConnectTimeoutSeconds = "25",
            llmReadTimeoutSeconds = "240",
            llmCallTimeoutSeconds = "480",
        ).applyTo(PipelineConfig())

        assertEquals(LlmExecutionConfig(25, 240, 480), updated.llmExecution)
    }

    @Test
    fun switchingProviderTypePrefillsOfficialUrlAndOpenRouterRouting() {
        val openRouter = SettingsFormState().withProviderType(ProviderType.OPENROUTER)
        assertEquals(ProviderType.OPENROUTER, openRouter.providerType)
        assertEquals(OpenRouterDefaults.BASE_URL, openRouter.baseUrl)
        assertEquals(ProviderSort.THROUGHPUT, openRouter.routingSort)
        assertTrue(openRouter.routingRequireParameters)

        val openai = openRouter.withProviderType(ProviderType.OPENAI_COMPAT)
        assertEquals(ProviderType.OPENAI_COMPAT, openai.providerType)
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals(ProviderSort.DEFAULT, openai.routingSort)
        assertFalse(openai.routingRequireParameters)

        val anthropic = openai.withProviderType(ProviderType.ANTHROPIC)
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        assertFalse(anthropic.supportsJsonMode)

        val proxy = SettingsFormState(baseUrl = "https://proxy.example/v1").withProviderType(ProviderType.ANTHROPIC)
        assertEquals("https://proxy.example/v1", proxy.baseUrl)
    }

    @Test
    fun providerApiKeyIsScrubbedFromSavedStateCopy() {
        val form = SettingsFormState(apiKey = "plaintext-secret", baseUrl = "https://api.example")

        val saved = form.forSavedState()

        assertEquals("", saved.apiKey)
        assertEquals(form.baseUrl, saved.baseUrl)
    }
}
