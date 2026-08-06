package com.dailynews.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.ui.onboarding.OnboardingScreen
import com.dailynews.app.ui.onboarding.OnboardingViewModel
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class V3ScreenMatrixScreenshotTest {
    private companion object {
        /**
         * 简报页把当前时刻渲染进「下次计划 YYYY-MM-DD HH:mm」和 missed-today 补跑卡。
         * 不钉死时钟，这 8 张 v3-brief 基线每过一天就自己失配一次，
         * 于是整个截图验收门变成噪音，改动的真实影响面无从判断。
         * 取 10:00 计划时刻之前的时刻，稳定落在「等待首次生成」态。
         */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-05T02:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun recordsCompactExpandedLightDarkAndTwoHundredPercent() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        // DailyNewsApplication.onCreate 在 Dispatchers.IO 上异步 seedIfEmpty()，
        // 截图时机决定订阅页看到 0 条还是 N 条——同一份代码两次运行会拍出不同的图。
        // 这是基线不确定的第二个来源（第一个是 wall-clock）。显式等它落库。
        runBlocking { application.container.feedRepository.seedIfEmpty() }
        val routes = linkedMapOf(
            "brief" to "brief",
            "reader" to "reader",
            "history" to "history",
            "feeds" to "feeds",
            "favorites" to "favorites",
            "settings" to "settings",
            "diagnostics" to "diagnostics",
            "report" to "report/2026-08-04",
            "onboarding" to null,
        )
        listOf(false, true).forEach { expanded ->
            listOf(false, true).forEach { dark ->
                listOf(1f, 2f).forEach { scale ->
                    val width = if (expanded) 840 else 360
                    val variant = "${if (expanded) "expanded" else "compact"}-${if (dark) "dark" else "light"}-${if (scale == 2f) "200" else "100"}"
                    routes.forEach { (name, route) ->
                        captureRoboImage(
                            filePath = "src/test/screenshots/v3-$name-$variant.png",
                            roborazziComposeOptions = RoborazziComposeOptions {
                                size(widthDp = width, heightDp = 800)
                                fontScale(scale)
                                uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                            },
                        ) {
                            DailyNewsTheme(darkTheme = dark, dynamicColor = false) {
                                if (route == null) {
                                    val viewModel = remember {
                                        OnboardingViewModel(
                                            application.container.providerSettings,
                                            application.container.apiKeyVault,
                                            application.container.configRepository,
                                            scheduleReports = {},
                                        )
                                    }
                                    OnboardingScreen(viewModel)
                                } else {
                                    DailyNewsApp(
                                        application.container,
                                        expanded,
                                        route,
                                        routeRequestVersion = 1,
                                        sweepWorkInfos = flowOf(emptyList<WorkInfo>()),
                                        clock = FIXED_CLOCK,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
