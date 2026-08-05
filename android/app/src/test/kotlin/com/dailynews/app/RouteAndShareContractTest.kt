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
        assertEquals(setOf("today", "history", "favorites"), routes)
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

    companion object { private const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
