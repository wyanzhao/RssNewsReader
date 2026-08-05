package com.dailynews.app.widget

import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import com.dailynews.data.repo.WidgetReportSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyNewsWidgetTest {
    @Test
    fun widgetRoutesToLatestReportOrBrief() {
        assertEquals("brief", widgetRoute(null))
        assertEquals("report/2026-08-04", widgetRoute(WidgetReportSnapshot("2026-08-04", "SUCCESS", emptyList())))
    }

    @Test
    fun successShowsExactlyThreeTopTitles() = runGlanceAppWidgetUnitTest {
        provideComposable {
            GlanceTheme {
                WidgetContent(WidgetReportSnapshot("2026-08-04", "SUCCESS", listOf("One", "Two", "Three", "Four")))
            }
        }

        onNode(hasText("1. One")).assertExists()
        onNode(hasText("2. Two")).assertExists()
        onNode(hasText("3. Three")).assertExists()
        onNode(hasText("4. Four")).assertDoesNotExist()
    }
}
