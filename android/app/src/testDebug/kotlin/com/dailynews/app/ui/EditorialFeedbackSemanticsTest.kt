package com.dailynews.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.foundation.lazy.LazyColumn
import com.dailynews.app.ui.report.ReportUiState
import com.dailynews.app.ui.report.reportContent
import com.dailynews.data.db.ReportEntity
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.app.ui.report.EditorialFeedbackDialog
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.model.ArticleFeedback
import com.dailynews.model.FeedbackKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorialFeedbackSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reportCardMenuSendsTheCorrectArticleAndClosesBeforeTheDialog() {
        val item = ReportItemEntity("2026-09-07", 1, 1, "https://example.org/a", "Compiler release", "Example", "2026-09-07 10:00 UTC", "2026-09-07T10:00:00Z", summaryZh = "测试摘要")
        var result: Pair<String, FeedbackKind?>? = null
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                LazyColumn {
                    reportContent(
                        state = ReportUiState(loaded = true, report = ReportEntity("2026-09-07", "SUCCESS", "report", "report", createdAtUtc = "2026-09-07T10:00:00Z"), items = listOf(item)),
                        onToggleRaw = {}, onToggleGroup = {}, onMarkRead = {}, onToggleFavorite = {}, onOpen = {}, onShare = {},
                        onFeedback = { article, kind, _ -> result = article.link to kind },
                    )
                }
            }
        }
        compose.onNodeWithText("Compiler release").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("选题反馈").performClick()
        compose.onNodeWithText("分享文章").assertDoesNotExist()
        compose.onNodeWithText("减少这个来源").performClick()
        compose.onNodeWithText("保存反馈").performClick()
        assertEquals(item.link to FeedbackKind.LESS_SOURCE, result)
    }

    @Test
    fun reducingTopicRequiresAnExplicitTopic() {
        var result: Pair<FeedbackKind?, String>? = null
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                EditorialFeedbackDialog(null, {}) { kind, topic -> result = kind to topic }
            }
        }
        compose.onNodeWithText("减少某个主题").performClick()
        compose.onNodeWithText("保存反馈").assertIsNotEnabled()
        compose.onNodeWithText("希望减少的主题").performTextInput("一般融资")
        compose.onNodeWithText("保存反馈").performClick()
        assertEquals(FeedbackKind.LESS_TOPIC to "一般融资", result)
    }

    @Test
    fun existingFeedbackCanBeRemoved() {
        var called = false
        var result: FeedbackKind? = FeedbackKind.VALUABLE
        compose.setContent {
            DailyNewsTheme(dynamicColor = false) {
                EditorialFeedbackDialog(
                    ArticleFeedback("https://example.org/a", "Headline", "Example", kind = FeedbackKind.VALUABLE), {},
                ) { kind, _ -> called = true; result = kind }
            }
        }
        compose.onNodeWithText("撤销反馈").performClick()
        assertEquals(true, called)
        assertEquals(null, result)
    }
}
