package com.dailynews.app.ui.story

import android.content.res.Configuration
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.data.db.ReportItemEntity
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 组件级线索历史截图：不进 V3 的 72 张矩阵，沿用 ReaderScreenshotTest 的做法。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class StoryScreenshotTest {
    private companion object {
        /** 卡片副标题是相对时间，必须钉住 now，否则基线每天自己漂。 */
        val FIXED_NOW: Instant = Instant.parse("2026-08-05T12:00:00Z")
    }

    private fun item(date: String, position: Int, title: String, iso: String) = ReportItemEntity(
        reportDate = date,
        part = 1,
        position = position,
        link = "https://example/$date-$position",
        title = title,
        source = "TechCrunch",
        pubDateUtc = "$date 09:00 UTC",
        pubDateIso = iso,
        summaryZh = "OpenAI 完成新一轮融资，估值与交割条款较上一轮出现实质变化。",
        summaryEn = "",
        articleText = "",
        alsoLinksJson = "[]",
        eventKey = "openai-series-g-funding",
    )

    private fun fixture() = StoryUiState(
        eventKey = "openai-series-g-funding",
        headline = "OpenAI opens new funding round",
        days = listOf(
            StoryDay("2026-08-05", listOf(item("2026-08-05", 1, "OpenAI funding round closes at higher valuation", "2026-08-05T09:00+00:00"))),
            StoryDay("2026-08-03", listOf(item("2026-08-03", 4, "Regulators signal review of OpenAI funding terms", "2026-08-03T09:00+00:00"))),
            StoryDay("2026-08-01", listOf(item("2026-08-01", 2, "OpenAI opens new funding round", "2026-08-01T09:00+00:00"))),
        ),
    )

    @Test
    fun recordsStoryTimelineAndEmptyState() {
        listOf(false, true).forEach { dark ->
            captureRoboImage(
                filePath = "src/test/screenshots/story-content-compact-${if (dark) "dark" else "light"}-100.png",
                roborazziComposeOptions = RoborazziComposeOptions {
                    size(widthDp = 360, heightDp = 800)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                },
            ) {
                DailyNewsTheme(darkTheme = dark, dynamicColor = false) {
                    StoryContent(state = fixture(), now = FIXED_NOW)
                }
            }
        }
        captureRoboImage(
            filePath = "src/test/screenshots/story-empty-compact-light-100.png",
            roborazziComposeOptions = RoborazziComposeOptions { size(widthDp = 360, heightDp = 800) },
        ) {
            DailyNewsTheme(dynamicColor = false) {
                StoryContent(state = StoryUiState(eventKey = "x", days = emptyList()), now = FIXED_NOW)
            }
        }
    }
}
