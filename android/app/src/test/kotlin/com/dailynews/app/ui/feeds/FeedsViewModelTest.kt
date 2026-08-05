package com.dailynews.app.ui.feeds

import com.dailynews.data.repo.FeedEditorRepository
import com.dailynews.data.repo.FeedRecord
import com.dailynews.model.FeedDefinition
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class FeedsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun editingCarriesOriginalIdIntoUpdate() = runTest(dispatcher) {
        val repository = FakeFeedRepository()
        val viewModel = FeedsViewModel(repository)
        backgroundScope.launch { viewModel.state.collect() }
        val record = FeedRecord(42, "Old", "https://old", "block", true, 7)

        viewModel.edit(record)
        viewModel.setName("New")
        viewModel.setUrl("https://new")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(42L, repository.updatedId)
        assertEquals("New", repository.updatedFeed?.name)
        assertEquals("https://new", repository.updatedFeed?.url)
        assertEquals(0, repository.insertCount)
    }

    private class FakeFeedRepository : FeedEditorRepository {
        private val rows = MutableStateFlow<List<FeedRecord>>(emptyList())
        var updatedId: Long? = null
        var updatedFeed: FeedDefinition? = null
        var insertCount = 0
        override fun observeAll() = rows
        override suspend fun insert(feed: FeedDefinition): Long { insertCount += 1; return 1 }
        override suspend fun update(id: Long, feed: FeedDefinition) { updatedId = id; updatedFeed = feed }
        override suspend fun delete(id: Long) = Unit
        override suspend fun restore(feed: FeedRecord) = Unit
        override suspend fun reorder(orderedIds: List<Long>) = Unit
        override suspend fun importOpml(content: String) = 0
        override suspend fun exportOpml() = ""
    }
}
