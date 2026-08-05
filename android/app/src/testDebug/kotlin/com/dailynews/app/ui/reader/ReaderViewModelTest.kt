package com.dailynews.app.ui.reader

import android.content.Context
import androidx.room.Room
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.FeedEntity
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.FavoriteRepository
import com.dailynews.data.repo.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderViewModelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: DailyNewsDatabase

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReaderViewModel(
        ArticleRepository(database),
        FeedRepository(database, context),
        FavoriteRepository(database),
    )

    private suspend fun insertArticle(linkKey: String, feed: String, iso: String = "2026-08-04T10:00+00:00") {
        database.articles().insert(
            ArticleEntity(
                linkKey = linkKey,
                link = linkKey,
                feedName = feed,
                title = "title $linkKey",
                summaryEn = "summary",
                articleText = "",
                pubDateUtc = "2026-08-04 10:00 UTC",
                pubDateIso = iso,
                fetchedAtUtc = "2026-08-04T10:01:00Z",
            ),
        )
    }

    @Test
    fun emptyDatabaseMovesFromExplicitLoadingToExplicitEmpty() = runTest {
        val vm = viewModel()

        // stateIn 初值：articles 为 null，与空态显式可区分。
        assertEquals(ReaderPhase.LOADING, vm.state.value.phase)
        assertNull(vm.state.value.articles)

        val state = vm.state.first { it.articles != null }
        assertEquals(ReaderPhase.EMPTY, state.phase)
        assertEquals("还没有订阅源，先去「订阅」页添加来源。", state.emptyReason)
    }

    @Test
    fun poolWithArticlesReachesContentPhase() = runTest {
        database.feeds().insert(FeedEntity(name = "Alpha", url = "https://a"))
        insertArticle("https://example/one", "Alpha")

        val state = viewModel().state.first { it.articles != null }

        assertEquals(ReaderPhase.CONTENT, state.phase)
        assertEquals(listOf("https://example/one"), state.articles?.map { it.linkKey })
        assertEquals(1, state.totalUnread)
    }

    @Test
    fun scrollingOnlyGrowsTheWindowAndNeverWritesReadState() = runTest {
        database.feeds().insert(FeedEntity(name = "Alpha", url = "https://a"))
        insertArticle("https://example/one", "Alpha")
        val vm = viewModel()
        vm.state.first { it.phase == ReaderPhase.CONTENT }

        repeat(50) { index -> vm.onVisibleItem(index, 100) }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(database.articles().get("https://example/one")?.readAtUtc)
    }

    @Test
    fun markAllReadBatchUndoRestoresExactlyTheBatch() = runTest {
        database.feeds().insert(FeedEntity(name = "Alpha", url = "https://a"))
        insertArticle("https://example/one", "Alpha")
        insertArticle("https://example/two", "Alpha")
        val vm = viewModel()
        vm.state.first { it.phase == ReaderPhase.CONTENT }

        vm.markAllRead()
        vm.state.first { it.totalUnread == 0 }
        assertTrue(vm.state.value.canUndoMarkAllRead)

        vm.undoMarkAllRead()
        val restored = vm.state.first { it.totalUnread == 2 }
        assertEquals(2, restored.articles?.size)
    }

    @Test
    fun deletedSelectedFeedFallsBackToAllFeeds() = runTest {
        val feedId = database.feeds().insert(FeedEntity(name = "Alpha", url = "https://a"))
        insertArticle("https://example/one", "Alpha")
        val vm = viewModel()
        vm.state.first { it.phase == ReaderPhase.CONTENT }
        vm.selectFeed("Alpha")
        vm.state.first { it.filter.feedName == "Alpha" }

        database.feeds().delete(feedId)
        val fallen = vm.state.first { it.filter.feedName == null }
        assertNull(fallen.filter.feedName)
    }
}
