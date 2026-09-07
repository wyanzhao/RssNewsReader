package com.dailynews.app.ui.article

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.model.ReadingPreferences
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
class ReadingControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notesLight() = captureNotes(1f)
    @Test fun notesLargeText() = captureNotes(2f)
    private fun captureNotes(scale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(scale)
        try {
            compose.setContent { DailyNewsTheme(darkTheme = false, dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
                AnnotationDialog("关注编译器优化如何影响端侧推理。\n下次阅读时核对实验条件。", listOf("AI", "芯片"), { _, _ -> true }, {})
            } } }
            compose.onNodeWithText("保存", useUnmergedTree = true).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().captureRoboImage("src/test/screenshots/reading-notes-${(scale * 100).toInt()}.png")
        } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }
    @Test fun settingsLight() = capture(false)
    @Test fun settingsDark() = capture(true)
    private fun capture(dark: Boolean) {
        compose.setContent { DailyNewsTheme(darkTheme = dark, dynamicColor = false) {
            Surface(Modifier.fillMaxSize()) { ReadingDialog(ReadingPreferences(), { true }, {}) }
        } }
        compose.onNodeWithText("阅读排版").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("src/test/screenshots/reading-controls-${if (dark) "dark" else "light"}.png")
    }
    @Test fun invalidTagsStayVisibleAndNeverReachStorage() {
        var called = false
        compose.setContent { DailyNewsTheme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
            AnnotationDialog("", listOf("x".repeat(41)), { _, _ -> called = true; true }, {})
        } } }
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("最多 10 个标签，每个最多 40 字").assertIsDisplayed()
        assertFalse(called)
    }
}
