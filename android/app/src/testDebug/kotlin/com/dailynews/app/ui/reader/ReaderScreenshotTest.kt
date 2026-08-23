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
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Component-level reader screenshots: named without the v3- prefix, so they are re-recorded separately from the 64-image matrix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class ReaderScreenshotTest {
    private companion object {
        /**
         * Card subtitles are relative times like "N hours ago / N days ago". The fixture's pubDate is fixed,
         * so without pinning now these three baselines would drift by one step per day with the calendar.
         * Kept on the same day as ReaderFixture's newest item (2026-08-05T06:30Z).
         */
        val FIXED_NOW: Instant = Instant.parse("2026-08-05T12:00:00Z")
    }

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
                        ReaderContent(state = variant.state(), now = FIXED_NOW)
                    }
                }
            }
        }
    }
}
