package com.dailynews.app

import android.content.Intent
import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.dailynews.app.notify.NotificationHelper
import com.dailynews.model.AssembledReport
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteAndShareContractTest {
    @Test
    fun staticShortcutRouteMatrixIsComplete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parser = context.resources.getXml(R.xml.shortcuts)
        val routes = linkedSetOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "extra" &&
                parser.getAttributeValue(ANDROID_NS, "name") == "route"
            ) {
                routes += parser.getAttributeValue(ANDROID_NS, "value")
            }
            parser.next()
        }
        assertEquals(setOf("brief", "history", "favorites"), routes)
    }

    @Test
    fun canonicalRouteMapsLegacyTodayAliasAndIgnoresUnknownRoutes() {
        assertEquals("brief", com.dailynews.app.ui.canonicalRoute("today"))
        assertEquals("brief", com.dailynews.app.ui.canonicalRoute("brief"))
        assertEquals("reader", com.dailynews.app.ui.canonicalRoute("reader"))
        assertEquals("report/2026-08-04", com.dailynews.app.ui.canonicalRoute("report/2026-08-04"))
        assertEquals("runDiagnostics/run-1", com.dailynews.app.ui.canonicalRoute("runDiagnostics/run-1"))
        // New routes must be added to the guard in sync, otherwise deep links and route restoration
        // are silently dropped without any error being reported.
        assertEquals("story/openai-funding", com.dailynews.app.ui.canonicalRoute("story/openai-funding"))
        assertEquals("periodic/2026-W32", com.dailynews.app.ui.canonicalRoute("periodic/2026-W32"))
        assertEquals(
            "article/https%3A%2F%2Fexample.com%2Fa",
            com.dailynews.app.ui.canonicalRoute("article/https%3A%2F%2Fexample.com%2Fa"),
        )
        assertEquals(null, com.dailynews.app.ui.canonicalRoute(null))
        assertEquals(null, com.dailynews.app.ui.canonicalRoute("  "))
        assertEquals(null, com.dailynews.app.ui.canonicalRoute("not-a-route"))
    }

    @Test
    fun actualSuccessNotificationShareActionKeepsTopNMarkdownByteForByte() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val exact = "# Top 2\n\n1. A\n2. B\n"
        val result = RunExecutionResult.Success(
            LocalDate.parse("2026-08-04"),
            "run",
            AssembledReport("2026-08-04", "full", exact, emptyList()),
            emptyList(),
        )

        val notification = NotificationHelper.resultNotification(context, result)
        val chooser = shadowOf(notification.actions.single().actionIntent).savedIntent
        @Suppress("DEPRECATION")
        val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(exact, share.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun exactAlarmPermissionChangeIsRegisteredForRescheduling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val matches = context.packageManager.queryBroadcastReceivers(
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).setPackage(context.packageName),
            0,
        )
        assertTrue(matches.any { it.activityInfo.name.endsWith("BootReceiver") })
    }

    @Test
    fun watchedProgressNotificationDisclosesAiAssessmentAndOpensTheStory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val item = com.dailynews.model.ReportItem(1, 1, "https://example.test/new", "Chip specifications", "Source", "", "", "摘要",
            eventKey = "chip-launch", development = com.dailynews.model.EventDevelopment("2026-09-06", "已发布规格", "https://example.test/new", "published specifications"))
        val result = RunExecutionResult.Success(LocalDate.parse("2026-09-07"), "run",
            AssembledReport("2026-09-07", "full", "exact markdown", listOf(item)), emptyList())
        val watches = com.dailynews.model.WatchPreferences(events = listOf(com.dailynews.model.EventWatch("chip-launch", "Chip", "2026-09-05")))
        val notification = NotificationHelper.resultNotification(context, result, watches)
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains("AI 判断"))
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("已发布规格"))
        assertEquals("story/chip-launch", shadowOf(notification.contentIntent).savedIntent.getStringExtra("route"))
        val unfollowed = NotificationHelper.resultNotification(context, result, com.dailynews.model.WatchPreferences())
        assertEquals("report/2026-09-07", shadowOf(unfollowed.contentIntent).savedIntent.getStringExtra("route"))
        assertTrue(unfollowed.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains("已生成"))
        val multiple = NotificationHelper.resultNotification(context, result.copy(report = result.report.copy(items = listOf(item, item.copy(eventKey = "other-event")))),
            watches.copy(events = watches.events + com.dailynews.model.EventWatch("other-event", "Other", "2026-09-05")))
        assertEquals("report/2026-09-07", shadowOf(multiple.contentIntent).savedIntent.getStringExtra("route"))
    }

    @Test
    fun failureNotificationDeepLinksStraightToTheFailedRun() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = RunExecutionResult.Failed(LocalDate.parse("2026-08-04"), "run-42", "fetch", "boom")

        val notification = NotificationHelper.resultNotification(context, result)
        assertEquals("runDiagnostics/run-42", shadowOf(notification.contentIntent).savedIntent.getStringExtra("route"))
        // The trailing action is the "diagnostics" deep link; retry sits before it.
        assertEquals("runDiagnostics/run-42", shadowOf(notification.actions.last().actionIntent).savedIntent.getStringExtra("route"))
    }

    companion object { private const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
