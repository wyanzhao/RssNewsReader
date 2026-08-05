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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.flowOf

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class V3ScreenMatrixScreenshotTest {
    @Test
    fun recordsCompactExpandedLightDarkAndTwoHundredPercent() {
        val application = ApplicationProvider.getApplicationContext<DailyNewsApplication>()
        val routes = linkedMapOf(
            "today" to "today",
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
