package com.dailynews.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.dailynews.app.ui.DailyNewsApp
import com.dailynews.app.ui.onboarding.OnboardingScreen
import com.dailynews.app.ui.onboarding.OnboardingViewModel
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.dailynews.app.ui.savedStateViewModelFactory
import com.dailynews.app.work.DailyReportWorker
import com.dailynews.app.work.ReportScheduler
import com.dailynews.app.work.SweepWorker
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val appViewModel: DailyNewsViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        if (savedInstanceState == null) appViewModel.acceptRoute(intent.getStringExtra("route"))
        setContent {
            val layoutFlow = remember {
                WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).map<WindowLayoutInfo, WindowLayoutInfo?> { it }
            }
            val layoutInfo by layoutFlow.collectAsStateWithLifecycle(null)
            val hasSeparatingHinge = layoutInfo?.displayFeatures.orEmpty().filterIsInstance<FoldingFeature>().any { it.isSeparating }
            val expanded = calculateWindowSizeClass(this).widthSizeClass != WindowWidthSizeClass.Compact || hasSeparatingHinge
            val container = (application as DailyNewsApplication).container
            val sweepWorkInfos = remember(appContext) {
                WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(SweepWorker.UNIQUE_REFRESH)
            }
            val onboardingComplete by appViewModel.onboardingComplete.collectAsStateWithLifecycle()
            val routeRequest by appViewModel.route.collectAsStateWithLifecycle()
            DailyNewsTheme {
                if (onboardingComplete) {
                    DailyNewsApp(
                        container,
                        expanded,
                        routeRequest.route,
                        routeRequest.version,
                        sweepWorkInfos,
                        onRouteConsumed = appViewModel::consumeRoute,
                    )
                } else {
                    val onboarding: OnboardingViewModel = viewModel(
                        factory = savedStateViewModelFactory { savedState ->
                            OnboardingViewModel(
                                container.providerSettings,
                                container.apiKeyVault,
                                container.configRepository,
                                scheduleReports = { schedule -> ReportScheduler(appContext).ensureScheduled(schedule) },
                                runNow = { DailyReportWorker.enqueue(appContext, scheduled = false) },
                                savedState = savedState,
                            )
                        },
                    )
                    OnboardingScreen(onboarding)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.acceptRoute(intent.getStringExtra("route"))
    }
}
