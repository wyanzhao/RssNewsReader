package com.dailynews.app

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.dailynews.app.work.DailyReportWorker
import com.dailynews.app.work.ReportScheduler
import com.dailynews.app.work.SweepWorker
import com.dailynews.app.ui.common.relativeArticleTime
import com.dailynews.app.ui.common.feedDisplayStatus
import com.dailynews.data.repo.FeedRecord
import androidx.work.NetworkType
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dailynews.pipeline.parse.FeedParser
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstRunOnboardingTransitionsToRestorableTopLevelUi() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        if (Build.VERSION.SDK_INT >= 33) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${application.packageName} android.permission.POST_NOTIFICATIONS",
            )
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        application.container.providerSettings.setOnboardingComplete(false)
        compose.waitForIdle()
        compose.onNodeWithText("欢迎使用 DailyNews").assertIsDisplayed()
        compose.runOnUiThread { application.container.providerSettings.completeOnboarding() }
        compose.waitForIdle()
        compose.onNodeWithText("DailyNews").assertIsDisplayed()
    }

    @Test
    fun feedEditorSurvivesActivityRecreation() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        openRoute(application, "feeds")
        compose.onNode(hasText("名称") and hasSetTextAction()).performTextInput("Process-safe feed")
        compose.onNode(hasText("RSS URL") and hasSetTextAction()).performTextInput("https://example.com/rss")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNode(hasText("Process-safe feed") and hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasText("https://example.com/rss") and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun settingsSectionAndFormSurviveActivityRecreation() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        openRoute(application, "settings")
        compose.onNodeWithText("Pipeline").performClick()
        compose.onNode(hasText("Top N") and hasSetTextAction()).performTextClearance()
        compose.onNode(hasText("Top N") and hasSetTextAction()).performTextInput("47")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNode(hasText("47") and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun consumedLaunchRouteDoesNotReplayAfterActivityRecreation() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        openRoute(application, "feeds")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("Providers").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText("Providers").assertIsDisplayed()
    }

    @Test
    fun desugaredTimeParsingHandlesPlusZeroOffsetOnDevice() {
        val now = Instant.parse("2026-08-04T12:00:00Z")
        assertEquals(
            "2 小时前",
            relativeArticleTime("2026-08-04T10:00:00+00:00", "fallback", now),
        )
        assertEquals(
            "STALE",
            feedDisplayStatus(
                FeedRecord(1, "Source", "https://feed", "block", true, 0, "ok", newestItemDateIso = "2026-06-01T00:00:00+00:00"),
                now,
            ),
        )
    }

    @Test
    fun schedulerUsesConfiguredTimeAndWorkerCarriesScheduledFlag() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = ZonedDateTime.of(2026, 8, 4, 9, 0, 0, 0, ZoneId.of("America/Los_Angeles"))

        val target = ReportScheduler(context).nextTrigger("09:45", now)
        val request = DailyReportWorker.request(scheduled = true)
        val sweep = SweepWorker.request(intervalMinutes = 75)

        assertEquals(9, target.hour)
        assertEquals(45, target.minute)
        assertEquals(true, request.workSpec.input.getBoolean("scheduled", false))
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertEquals(75 * 60_000L, sweep.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, sweep.workSpec.constraints.requiredNetworkType)
        assertEquals(1_200_000L, DailyReportWorker.FOREGROUND_WATCHDOG_MILLIS)
        assertEquals(1_200_000L, DailyReportWorker.DEGRADED_WATCHDOG_MILLIS)
        assertEquals(1_200_000L, SweepWorker.WATCHDOG_MILLIS)
    }

    @Test
    fun schedulerRegistersOneUniquePeriodicSweepAndCanReregisterAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = ReportScheduler(context)

        scheduler.ensureScheduled("09:45", sweepIntervalMinutes = 75)
        scheduler.ensureScheduled("09:45", sweepIntervalMinutes = 90)

        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SweepWorker.UNIQUE_WORK)
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertTrue(work.single().tags.contains(SweepWorker::class.java.name))
    }

    @Test
    fun periodicSweepCanBeAdvancedByWorkManagerTestDriver() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val direct = SynchronousExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(direct).setTaskExecutor(direct).build(),
        )
        val request = SweepWorker.request(intervalMinutes = 75)
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)

        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setPeriodDelayMet(request.id)

        val info = requireNotNull(manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test
    fun promptTemplatesPrecomputeTopNArithmeticAndCarrySafetyContinuityRules() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prompts = AssetPromptSource(context)

        val shortlist = prompts.part1Shortlist(20)
        val plan = prompts.part1Plan(20)

        assertTrue(shortlist.contains("30–35"))
        assertTrue(!shortlist.contains("{N}"))
        assertTrue(shortlist.contains("在野利用 0day"))
        assertTrue(plan.contains("recent_top30"))
        assertTrue(plan.contains("同一 source 最多 3 条"))
        // The plan pass owns the ranking, so it needs the whole ladder — not just the security gates.
        assertTrue(!plan.contains("{N}"))
        assertTrue(plan.contains("排序阶梯"))
        assertTrue(plan.contains("重大业务/资本事件"))
        assertTrue(plan.contains("任何不满足上述任一条的安全新闻一律归入第 5 层"))
        assertTrue(plan.contains("shortfall` 必须等于 `20 - items 数量"))
    }

    @Test
    fun feedParserRunsOnAndroidWhenSecureProcessingFeatureIsUnsupported() {
        val xml = """<!DOCTYPE rss SYSTEM "https://invalid.example/external.dtd">
            <rss version="2.0"><channel><item>
              <title>Android provider compatible</title>
              <link>https://example.com/android</link>
              <pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate>
              <description>Parsed without loading the external DTD.</description>
            </item></channel></rss>
        """.trimIndent()

        val article = FeedParser.parse(xml).single()

        assertEquals("Android provider compatible", article.title)
        assertEquals("https://example.com/android", article.link)
    }

    private fun openRoute(application: DailyNewsApplication, route: String) {
        compose.runOnUiThread {
            application.container.providerSettings.completeOnboarding()
            application.startActivity(
                Intent(application, MainActivity::class.java)
                    .putExtra("route", route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        compose.waitForIdle()
    }
}
