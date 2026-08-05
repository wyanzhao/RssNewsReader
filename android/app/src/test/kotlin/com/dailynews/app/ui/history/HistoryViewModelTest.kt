package com.dailynews.app.ui.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @Test
    fun blankQueryIsImmediateButTextInputIsDebounced() = runTest {
        assertEquals("", flowOf("").debounceSearchInput().first())
        assertEquals(0, currentTime)

        assertEquals("100%", flowOf("100%").debounceSearchInput().first())
        assertEquals(300, currentTime)
    }
}
