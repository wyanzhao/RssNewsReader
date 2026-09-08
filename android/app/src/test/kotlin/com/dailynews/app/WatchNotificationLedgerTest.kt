package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.repo.WatchNotificationLedger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchNotificationLedgerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("watch_notifications", Context.MODE_PRIVATE)

    @Test fun recordedKeysSurviveReopenAndAreBoundedAndPruned() {
        prefs.edit().clear().commit()
        try {
            val ledger = WatchNotificationLedger(context)
            assertTrue(ledger.notifiedKeys().isEmpty())
            ledger.record(listOf("chip-launch|https://a.test/1", "", "chip-launch|https://a.test/1"), "2026-09-07")
            assertEquals(setOf("chip-launch|https://a.test/1"), WatchNotificationLedger(context).notifiedKeys())
            // Re-recording the same key on a later date keeps one entry and refreshes its date.
            ledger.record(listOf("chip-launch|https://a.test/1"), "2026-09-08")
            assertEquals(1, ledger.notifiedKeys().size)
            // Entries older than the retention window drop out when something new is recorded.
            ledger.record(listOf("old-event|https://a.test/old"), "2026-01-01")
            ledger.record(listOf("fresh|https://a.test/fresh"), "2026-09-09")
            assertEquals(setOf("chip-launch|https://a.test/1", "fresh|https://a.test/fresh"), ledger.notifiedKeys())
            // Bounded: only the newest MAX_ENTRIES survive.
            ledger.record((1..(WatchNotificationLedger.MAX_ENTRIES + 5)).map { "e|https://a.test/$it" }, "2026-09-10")
            val keys = ledger.notifiedKeys()
            assertEquals(WatchNotificationLedger.MAX_ENTRIES, keys.size)
            assertTrue("e|https://a.test/${WatchNotificationLedger.MAX_ENTRIES + 5}" in keys)
            assertFalse("chip-launch|https://a.test/1" in keys)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun corruptLedgerReadsAsEmptyAndRecoversOnNextRecord() {
        prefs.edit().putString("entries", "{broken").commit()
        try {
            val ledger = WatchNotificationLedger(context)
            assertTrue(ledger.notifiedKeys().isEmpty())
            ledger.record(listOf("chip-launch|https://a.test/1"), "2026-09-07")
            assertEquals(setOf("chip-launch|https://a.test/1"), ledger.notifiedKeys())
            assertFailsWith<Exception> { ledger.record(listOf("x|y"), "not-a-date") }
        } finally { prefs.edit().clear().commit() }
    }
}
