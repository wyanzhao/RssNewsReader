package com.dailynews.app.ui.reader

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件级阅读器截图：命名不带 v3- 前缀，脱离 64 张矩阵单独重录。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class ReaderScreenshotTest {
    private data class Variant(
        val name: String,
        val width: Int,
        val dark: Boolean,
        val scale: Float,
        val state: () -> ReaderUiState,
    )

    @Test
    fun recordsContentAndEmptyVariants() {
        val variants = listOf(
            Variant("content-compact-light-100", 360, false, 1f, ::readerFixtureState),
            Variant("content-compact-dark-100", 360, true, 1f, ::readerFixtureState),
            Variant("content-expanded-light-100", 840, false, 1f, ::readerFixtureState),
            Variant("empty-compact-light-100", 360, false, 1f, ::readerEmptyFixtureState),
        )
        variants.forEach { variant ->
            captureRoboImage(
                filePath = "src/test/screenshots/reader-${variant.name}.png",
                roborazziComposeOptions = RoborazziComposeOptions {
                    size(widthDp = variant.width, heightDp = 800)
                    fontScale(variant.scale)
                    uiMode(if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                },
            ) {
                DailyNewsTheme(darkTheme = variant.dark, dynamicColor = false) {
                    Column {
                        ReaderFilterChips(state = variant.state(), onSelectFeed = {}, onToggleUnread = {})
                        ReaderContent(state = variant.state())
                    }
                }
            }
        }
    }
}
