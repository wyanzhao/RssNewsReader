package com.dailynews.app.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.dailynews.app.ui.diagnostics.ArtifactStatus
import com.dailynews.app.ui.diagnostics.DiagnosticsAction
import com.dailynews.app.ui.diagnostics.DiagnosticsAdvice
import com.dailynews.app.ui.diagnostics.DiagnosticsUiState
import com.dailynews.app.ui.diagnostics.diagnosticsContent
import com.dailynews.app.ui.diagnostics.diagnosticsEmptyState
import com.dailynews.app.ui.diagnostics.diagnosticsFixtureState
import com.dailynews.app.ui.theme.DailyNewsTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tall qualifier so every lazy item composes; each test calls setContent exactly once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xxhdpi")
class DiagnosticsSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private fun setContent(
        state: DiagnosticsUiState,
        showAllLogs: Boolean = false,
        runsExpanded: Boolean = false,
        advancedExpanded: Boolean = false,
        onRunProbe: () -> Unit = {},
        onRunNow: () -> Unit = {},
        onSelectRun: (String) -> Unit = {},
        onToggleShowAllLogs: () -> Unit = {},
    ) {
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn {
                    diagnosticsContent(
                        state = state,
                        onSelectRun = onSelectRun,
                        onRunNow = onRunNow,
                        onRunProbe = onRunProbe,
                        showAllLogs = showAllLogs,
                        runsExpanded = runsExpanded,
                        advancedExpanded = advancedExpanded,
                        onToggleShowAllLogs = onToggleShowAllLogs,
                    )
                }
            }
        }
    }

    @Test
    fun verdictCardSurfacesBlockingReasonAndPrimaryAction() {
        var probed = false
        setContent(diagnosticsFixtureState(), onRunProbe = { probed = true })

        compose.onNodeWithText("未预期错误，证据指向网络问题").assertIsDisplayed()
        compose.onNodeWithText("• fetch: connection timed out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("失败阶段：fetch").assertIsDisplayed()
        compose.onNodeWithText("运行网络探测", substring = false).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(probed)
    }

    @Test
    fun expectedBlockBadgeReadsAsRuleBlockNotFailure() {
        // Core claim of this rework: EXPECTED_BLOCK must not read like UNEXPECTED_ERROR.
        val blocked = diagnosticsFixtureState().copy(
            detail = diagnosticsFixtureState().detail!!.copy(status = "FAILED", classification = "EXPECTED_BLOCK"),
            advice = DiagnosticsAdvice("系统按规则主动阻断，不是故障", DiagnosticsAction.NONE),
        )
        setContent(blocked)
        compose.onNodeWithContentDescription("按规则阻断，2026-08-04").assertIsDisplayed()
        compose.onNodeWithContentDescription("运行故障，2026-08-04").assertDoesNotExist()
    }

    @Test
    fun unexpectedErrorBadgeReadsAsRunFailure() {
        setContent(diagnosticsFixtureState())
        compose.onNodeWithContentDescription("运行故障，2026-08-04").assertIsDisplayed()
        compose.onNodeWithContentDescription("按规则阻断，2026-08-04").assertDoesNotExist()
    }

    @Test
    fun runsSectionStaysCollapsedUntilToggled() {
        setContent(diagnosticsFixtureState())
        compose.onNodeWithText("▶ 最近运行", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("exit 0 · 重试 1", substring = true)).assertDoesNotExist()
    }

    @Test
    fun runsSectionListsRunsWhenExpanded() {
        var selected = ""
        setContent(diagnosticsFixtureState(), runsExpanded = true, onSelectRun = { selected = it })
        compose.onNode(hasText("exit 0 · 重试 1", substring = true)).performScrollTo().performClick()
        assertTrue(selected == "run-41")
    }

    @Test
    fun showAllLogsRevealsInfoLines() {
        compose.setContent {
            var showAll by remember { mutableStateOf(false) }
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn {
                    diagnosticsContent(
                        state = diagnosticsFixtureState(),
                        showAllLogs = showAll,
                        onToggleShowAllLogs = { showAll = !showAll },
                    )
                }
            }
        }
        compose.onNodeWithText("fetched 42 articles").assertDoesNotExist()
        compose.onNode(hasText("显示全部", substring = true)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("fetched 42 articles").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun advancedSectionExposesRawJsonAndParseNotes() {
        val degraded = diagnosticsFixtureState().copy(
            validationArtifact = diagnosticsFixtureState().validationArtifact.copy(status = ArtifactStatus.UNPARSEABLE),
        )
        setContent(degraded, advancedExpanded = true)
        compose.onNodeWithText("无法解析，已回退到运行记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已截断至 4000 字符").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyDatabaseRendersTheEmptyStateCard() {
        var ran = false
        setContent(diagnosticsEmptyState(), onRunNow = { ran = true })
        compose.onNodeWithText("还没有运行记录").assertIsDisplayed()
        compose.onNodeWithText("立即生成").performClick()
        assertTrue(ran)
    }
}
