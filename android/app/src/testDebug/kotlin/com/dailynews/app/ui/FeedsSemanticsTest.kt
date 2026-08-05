package com.dailynews.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.dailynews.app.ui.feeds.FeedsScreen
import com.dailynews.app.ui.feeds.FeedsViewModel
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.data.repo.FeedEditorRepository
import com.dailynews.data.repo.FeedRecord
import com.dailynews.model.FeedDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class FeedsSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun deleteRequiresConfirmationAndSnackbarCanRestoreTheFeed() {
        val repository = FakeFeedRepository()
        val viewModel = FeedsViewModel(repository, SavedStateHandle())
        compose.setContent { DailyNewsTheme(dynamicColor = false) { FeedsScreen(viewModel, expanded = false) } }

        compose.onNodeWithText("Example Feed").assertIsDisplayed()
        compose.onAllNodesWithText("删除")[0].performClick()
        compose.onNodeWithText("删除 Example Feed？").assertIsDisplayed()
        compose.onAllNodesWithText("删除")[1].performClick()

        compose.waitUntil(5_000) { repository.deleteCount == 1 }
        compose.onNodeWithText("已删除 Example Feed").assertIsDisplayed()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5_000) { repository.restoreCount == 1 }
        compose.onNodeWithText("Example Feed").assertIsDisplayed()
    }
}

private class FakeFeedRepository : FeedEditorRepository {
    private val initial = FeedRecord(1, "Example Feed", "https://example.com/rss", "block", true, 0)
    private val feeds = MutableStateFlow(listOf(initial))
    @Volatile var deleteCount = 0
    @Volatile var restoreCount = 0

    override fun observeAll(): Flow<List<FeedRecord>> = feeds
    override suspend fun insert(feed: FeedDefinition): Long = error("not used")
    override suspend fun update(id: Long, feed: FeedDefinition) = error("not used")
    override suspend fun delete(id: Long) {
        deleteCount++
        feeds.value = feeds.value.filterNot { it.id == id }
    }
    override suspend fun restore(feed: FeedRecord) {
        restoreCount++
        feeds.value = listOf(feed)
    }
    override suspend fun reorder(orderedIds: List<Long>) = Unit
    override suspend fun importOpml(content: String): Int = error("not used")
    override suspend fun exportOpml(): String = error("not used")
}
