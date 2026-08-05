package com.dailynews.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class ArticleCardScreenshotTest {
    @Test
    fun compactLightAtTwoHundredPercentFontScale() {
        captureRoboImage(
            filePath = "src/test/screenshots/article-card-compact-light-200.png",
            roborazziComposeOptions = RoborazziComposeOptions {
                size(widthDp = 360, heightDp = 800)
                fontScale(2f)
            },
        ) {
            DailyNewsTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        ArticleCard(
                            article = ArticleCardModel(
                                link = "https://example.com/story",
                                title = "A long English headline remains readable at large font sizes",
                                source = "Example",
                                pubDateUtc = "2026-08-04 20:00 UTC",
                                pubDateIso = "2026-08-04T20:00:00Z",
                                summaryZh = "这是一段用于验证 200% 字体缩放、中文行高与操作目标不会重叠的摘要。",
                                rank = 1,
                                relatedLinks = listOf("https://second.example.com/story"),
                            ),
                            saved = true,
                            read = false,
                            onOpen = {},
                            onToggleFavorite = {},
                            onShare = {},
                            onOpenRelated = {},
                            now = Instant.parse("2026-08-04T20:59:00Z"),
                        )
                    }
                }
            }
        }
    }
}
