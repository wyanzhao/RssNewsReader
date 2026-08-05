package com.dailynews.app.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.report.ReportUiState
import com.dailynews.app.ui.report.part2Section
import com.dailynews.app.ui.report.reportContent
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.data.db.ReportEntity
import com.dailynews.model.ReportGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun articleTitleFavoriteAndLongPressMenuAreAccessible() {
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                ArticleCard(
                    article = ArticleCardModel("https://example.com/a", "English headline", "Example", "2026-08-04 12:00 UTC", "2026-08-04T12:00:00Z", "中文摘要", rank = 1, relatedLinks = listOf("https://related.example/story")),
                    saved = false,
                    read = false,
                    onOpen = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onOpenRelated = {},
                )
            }
        }

        compose.onNode(hasText("English headline") and hasText("中文摘要")).assertIsDisplayed()
        compose.onNodeWithText("related.example").assertIsDisplayed()
        compose.onNodeWithContentDescription("收藏").assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("English headline") and hasText("中文摘要")).performTouchInput { longClick() }
        compose.onNodeWithText("分享文章").assertIsDisplayed()
    }

    @Test
    fun part2SectionKeepsSourceFoldSemanticsWhenInvokedDirectly() {
        var toggled = ""
        val state = ReportUiState(
            report = ReportEntity("2026-08-04", "SUCCESS", "full", "# report", createdAtUtc = "2026-08-04T00:00:00Z"),
            groups = listOf(ReportGroup("Example", "ok", 1)),
        )
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn {
                    part2Section(
                        state = state,
                        onToggleGroup = { toggled = it },
                        onMarkRead = {},
                        onToggleFavorite = {},
                        onOpen = {},
                        onShare = {},
                    )
                }
            }
        }

        compose.onNodeWithText("▶ Example · 1 篇").performClick()
        assertEquals("Example", toggled)
    }

    @Test
    fun reportContentHidesPart2GroupHeadersWhileKeepingTopNSharePayloadExact() {
        var shared = ""
        val exact = "# Top 1\n\n1. Exact\n"
        val state = ReportUiState(
            report = ReportEntity("2026-08-04", "SUCCESS", "full", exact, createdAtUtc = "2026-08-04T00:00:00Z"),
            groups = listOf(ReportGroup("Example", "ok", 1)),
        )
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn {
                    reportContent(
                        state = state,
                        onToggleRaw = {},
                        onToggleGroup = {},
                        onMarkRead = {},
                        onToggleFavorite = {},
                        onOpen = {},
                        onShare = { shared = it },
                    )
                }
            }
        }

        // Epic U：PART2_SECTION_ENABLED = false 后组头不再出现，且 Top N 分享 payload 逐字节不变。
        compose.onNodeWithText("▶ Example · 1 篇").assertDoesNotExist()
        compose.onNodeWithText("分享 Top N").performClick()
        assertEquals(exact, shared)
    }
}
