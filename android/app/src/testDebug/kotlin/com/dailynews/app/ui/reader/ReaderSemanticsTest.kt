package com.dailynews.app.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.dailynews.app.ui.theme.DailyNewsTheme
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chipsUnreadBadgesAndFavoriteTargetAreAccessible() {
        val state = readerFixtureState()
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                Column {
                    ReaderFilterChips(state = state, onSelectFeed = {}, onToggleUnread = {})
                    ReaderContent(state = state)
                }
            }
        }

        // chip 选中态与未读徽章都能被 TalkBack 读到。
        compose.onNodeWithText("全部 412").assertIsSelected()
        compose.onNodeWithText("TechCrunch 5").assertIsDisplayed()
        compose.onNodeWithText("Broken Feed 0").assertIsDisplayed()
        // 异常源 chip 携带健康徽章。
        compose.onNodeWithContentDescription("异常，connect timeout").assertIsDisplayed()
        // 收藏按钮 ≥48dp（多张卡片各取其一）。
        compose.onAllNodesWithContentDescription("收藏").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("收藏")[0].assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("取消收藏").assertHeightIsAtLeast(48.dp)
    }
}
