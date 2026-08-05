package com.dailynews.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import com.dailynews.app.ui.diagnostics.DiagnosticsUiState
import com.dailynews.app.ui.diagnostics.diagnosticsContent
import com.dailynews.app.ui.diagnostics.diagnosticsEmptyState
import com.dailynews.app.ui.diagnostics.diagnosticsFixtureState
import com.dailynews.app.ui.theme.DailyNewsSpacing
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
 * Renders diagnosticsContent with a hand-built fixture, bypassing Robolectric's empty
 * Room. The fixture keeps the advanced section expanded (truncated raw JSON included);
 * the 840dp variant locks the 640dp reading width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class DiagnosticsScreenshotTest {
    private data class Variant(
        val name: String,
        val width: Int,
        val dark: Boolean,
        val scale: Float,
        val state: () -> DiagnosticsUiState,
    )

    @Test
    fun recordsDataAndEmptyVariants() {
        val variants = listOf(
            Variant("data-compact-light-100", 360, false, 1f, ::diagnosticsFixtureState),
            Variant("data-compact-dark-100", 360, true, 1f, ::diagnosticsFixtureState),
            Variant("data-compact-light-200", 360, false, 2f, ::diagnosticsFixtureState),
            Variant("data-expanded-light-100", 840, false, 1f, ::diagnosticsFixtureState),
            Variant("empty-compact-light-100", 360, false, 1f, ::diagnosticsEmptyState),
        )
        variants.forEach { variant ->
            captureRoboImage(
                filePath = "src/test/screenshots/diagnostics-${variant.name}.png",
                roborazziComposeOptions = RoborazziComposeOptions {
                    size(widthDp = variant.width, heightDp = 800)
                    fontScale(variant.scale)
                    uiMode(if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                },
            ) {
                DailyNewsTheme(darkTheme = variant.dark, dynamicColor = false) {
                    LazyColumn(
                        contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                        verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        diagnosticsContent(state = variant.state(), advancedExpanded = true)
                    }
                }
            }
        }
    }
}
