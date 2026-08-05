package com.dailynews.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.app.ui.today.SweepProgressCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayProgressSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sweepProgressAnnouncesItsStateAndShowsAnAnimatedIndicator() {
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                SweepProgressCard("正在抓取 RSS 并更新文章池…")
            }
        }

        compose.onNodeWithText("正在抓取 RSS 并更新文章池…").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
    }
}
