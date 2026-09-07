package com.dailynews.app.ui.story

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.model.EventDevelopment
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class StoryDevelopmentScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @Test fun light() = capture(false)
    @Test fun dark() = capture(true)
    private fun capture(dark: Boolean) {
        val row = ReportItemEntity("2026-08-05", 1, 1, "https://source.example/release", "Compiler reaches general availability", "Source", "2026-08-05 09:00 UTC", "2026-08-05T09:00:00+00:00", summaryZh = "编译器发布正式版本，支持原有测试阶段的主要功能。", eventKey = "compiler", development = EventDevelopment("2026-08-03", "编译器从测试阶段进入正式可用阶段。", "https://source.example/release", "The compiler is generally available."))
        compose.setContent {
            DailyNewsTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    StoryContent(StoryUiState("compiler", listOf(StoryDay(row.reportDate, listOf(row))), "Compiler release"), now = Instant.parse("2026-08-05T12:00:00Z"))
                }
            }
        }
        compose.onNodeWithText("本次进展 · AI 判断").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("src/test/screenshots/story-development-compact-${if (dark) "dark" else "light"}-100.png")
    }
}
