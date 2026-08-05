package com.dailynews.app.ui.common

import androidx.work.WorkInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepProgressTest {
    @Test
    fun sweepProgressTracksTheActualWorkerLifetime() {
        val queued = sweepProgressFor(listOf(WorkInfo.State.ENQUEUED))
        val running = sweepProgressFor(listOf(WorkInfo.State.RUNNING))
        val finished = sweepProgressFor(listOf(WorkInfo.State.SUCCEEDED))

        assertTrue(queued.active)
        assertTrue("等待" in queued.label)
        assertTrue(running.active)
        assertTrue("正在抓取" in running.label)
        assertEquals(SweepUiProgress(), finished)
    }
}
