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
// Viewport explicitly made taller: LazyColumn only composes visible items, and the day sections additionally insert
// sticky headers, so at the default height the last card falls outside the viewport and is never composed at all,
// leaving assertions mysteriously short by one.
@Config(sdk = [35], qualifiers = "w360dp-h1600dp")
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

        // Both the chip selected state and the unread badge are readable by TalkBack.
        compose.onNodeWithText("全部 412").assertIsSelected()
        compose.onNodeWithText("TechCrunch 5").assertIsDisplayed()
        compose.onNodeWithText("Broken Feed 0").assertIsDisplayed()
        // The error-source chip carries a health badge.
        compose.onNodeWithContentDescription("异常，connect timeout").assertIsDisplayed()
        // Favorite button ≥48dp (one node taken from each of several cards).
        compose.onAllNodesWithContentDescription("收藏").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("收藏")[0].assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("取消收藏").assertHeightIsAtLeast(48.dp)
    }
}
