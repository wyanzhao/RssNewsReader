package com.dailynews.app.ui.common

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.model.EventDevelopment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventDevelopmentSemanticsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun readerCanInspectQuoteAndOpenTheExactEvidenceSource() {
        val progress = EventDevelopment("2026-01-02", "编译器发布正式版本。", "https://source.example/a?version=1", "The compiler is generally available.")
        var opened: String? = null
        compose.setContent { DailyNewsTheme(dynamicColor = false) { EventDevelopmentContent(progress) { opened = it } } }
        compose.onNodeWithText("本次进展 · AI 判断").assertExists()
        compose.onNodeWithText(progress.evidenceQuote).assertDoesNotExist()
        compose.onNodeWithText("查看来源证据").performClick()
        compose.onNodeWithText(progress.evidenceQuote).assertExists()
        compose.onNodeWithText("打开证据原文").performClick()
        assertEquals(progress.evidenceLink, opened)
        compose.onNodeWithText("收起来源证据").performClick()
        compose.onNodeWithText(progress.evidenceQuote).assertDoesNotExist()
    }
}
