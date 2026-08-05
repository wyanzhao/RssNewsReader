package com.dailynews.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.dailynews.app.ui.diagnostics.diagnosticsContent
import com.dailynews.app.ui.diagnostics.diagnosticsFixtureState
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.app.ui.theme.DailyNewsTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for [diagnosticsItemWidth]: on wide windows every diagnostics item
 * must be capped at the reading width and centered. LazyColumn.horizontalAlignment is
 * unreliable under Robolectric, so the shared item modifier does the centering itself
 * and this test pins the exact left offset it produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h800dp-xxhdpi")
class DiagnosticsWidthProbeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun verdictCardIsCenteredAtReadingWidth() {
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn(
                    contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                    verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    diagnosticsContent(state = diagnosticsFixtureState())
                }
            }
        }
        // 840dp window, roomy content padding each side -> 808dp slot; the item modifier
        // caps content at 640dp and centers it: (808 - 640)/2 = 84, + 16 padding = 100.
        // The stage text sits behind the card's inner roomy Column padding.
        compose.onNodeWithText("失败阶段：fetch").assertLeftPositionInRootIsEqualTo(100.dp + DailyNewsSpacing.roomy)
    }
}
