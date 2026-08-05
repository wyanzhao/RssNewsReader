package com.dailynews.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dailynews.app.AppContainer
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.R
import com.dailynews.app.ui.diagnostics.DiagnosticsScreen
import com.dailynews.app.ui.diagnostics.DiagnosticsViewModel
import com.dailynews.app.ui.favorites.FavoritesScreen
import com.dailynews.app.ui.favorites.FavoritesViewModel
import com.dailynews.app.ui.feeds.FeedsScreen
import com.dailynews.app.ui.feeds.FeedsViewModel
import com.dailynews.app.ui.history.HistoryScreen
import com.dailynews.app.ui.history.HistoryViewModel
import com.dailynews.app.ui.reader.ReaderScreen
import com.dailynews.app.ui.reader.ReaderViewModel
import com.dailynews.app.ui.report.ReportPane
import com.dailynews.app.ui.report.ReportScreen
import com.dailynews.app.ui.report.ReportViewModel
import com.dailynews.app.ui.settings.SettingsScreen
import com.dailynews.app.ui.settings.SettingsViewModel
import com.dailynews.app.ui.brief.TodayScreen
import com.dailynews.app.ui.brief.TodayViewModel
import com.dailynews.app.work.DailyReportWorker
import com.dailynews.app.work.ReportScheduler
import com.dailynews.app.work.SweepWorker
import kotlinx.coroutines.flow.Flow

private data class Destination(val route: String, val label: Int, val icon: Int)
private val destinations = listOf(
    Destination("brief", R.string.nav_brief, R.drawable.ic_today),
    Destination("reader", R.string.nav_reader, R.drawable.ic_reader),
    Destination("feeds", R.string.nav_feeds, R.drawable.ic_rss_feed),
    Destination("favorites", R.string.nav_favorites, R.drawable.ic_favorite),
    Destination("settings", R.string.nav_settings, R.drawable.ic_settings),
)

/**
 * 路由归一化守卫：用户 pin 过的快捷方式会保留旧 extras（today→brief），
 * nav.navigate(未注册路由) 会抛 IllegalArgumentException，脏 route 在此静默归零。
 */
internal fun canonicalRoute(raw: String?): String? {
    val route = raw?.trim().orEmpty()
    if (route.isEmpty()) return null
    if (route == "today") return "brief"
    if (route in topLevelRoutes || route.startsWith("report/") || route.startsWith("runDiagnostics/")) return route
    return null
}

private val topLevelRoutes = setOf("brief", "reader", "history", "feeds", "favorites", "settings", "diagnostics")

@Composable
fun DailyNewsApp(
    container: AppContainer,
    expanded: Boolean,
    initialRoute: String?,
    routeRequestVersion: Int,
    sweepWorkInfos: Flow<List<WorkInfo>>,
    onRouteConsumed: (Int) -> Unit = {},
) {
    val nav = rememberNavController()
    val currentEntry by nav.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route.orEmpty()
    LaunchedEffect(initialRoute, routeRequestVersion) {
        if (!initialRoute.isNullOrBlank()) {
            canonicalRoute(initialRoute)?.let { route -> nav.navigate(route) { launchSingleTop = true } }
            onRouteConsumed(routeRequestVersion)
        }
    }
    NavigationSuiteScaffold(
        modifier = Modifier.fillMaxSize(),
        layoutType = if (expanded) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            destinations.forEach { destination ->
                item(
                    selected = currentRoute == destination.route,
                    onClick = { nav.navigateTopLevel(destination.route) },
                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        AppNavHost(nav, container, expanded, sweepWorkInfos, Modifier.fillMaxSize())
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    container: AppContainer,
    expanded: Boolean,
    sweepWorkInfos: Flow<List<WorkInfo>>,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    NavHost(
        navController = nav,
        startDestination = "brief",
        modifier = modifier,
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backwardEnter() },
        popExitTransition = { backwardExit() },
    ) {
        composable("brief") {
            val vm: TodayViewModel = viewModel(factory = viewModelFactory {
                TodayViewModel(
                    container.reportRepository,
                    container.configRepository,
                    container.providerSettings,
                    runRepository = container.runRepository,
                    runLogs = container.runLogRepository,
                    articleRepository = container.articleRepository,
                    sweepWorkInfos = sweepWorkInfos,
                )
            })
            TodayScreen(
                vm,
                onRunNow = { DailyReportWorker.enqueue(context, scheduled = false) },
                onSweep = { SweepWorker.enqueueRefresh(context) },
                onOpenDiagnostics = { runId -> nav.navigate(runId?.let { "runDiagnostics/$it" } ?: "diagnostics") },
                onOpenSettings = { nav.navigateTopLevel("settings") },
                onOpenReport = { nav.navigate("report/$it") },
                reportViewModel = { date -> reportViewModel(container, date) },
                onOpenHistory = { nav.navigate("history") },
            )
        }
        composable("history") {
            val vm: HistoryViewModel = viewModel(factory = viewModelFactory { HistoryViewModel(container.reportRepository) })
            HistoryScreen(vm, expanded, onOpenReport = { nav.navigate("report/$it") }) { date ->
                ReportPane(reportViewModel(container, date))
            }
        }
        composable("report/{date}") { entry ->
            val date = entry.arguments?.getString("date").orEmpty()
            ReportScreen(date, reportViewModel(container, date))
        }
        composable("feeds") {
            val vm: FeedsViewModel = viewModel(factory = savedStateViewModelFactory { savedState -> FeedsViewModel(container.feedRepository, savedState) })
            FeedsScreen(vm, expanded)
        }
        composable("favorites") {
            val vm: FavoritesViewModel = viewModel(factory = viewModelFactory { FavoritesViewModel(container.favoriteRepository) })
            FavoritesScreen(vm)
        }
        composable("reader") {
            val vm: ReaderViewModel = viewModel(factory = viewModelFactory {
                ReaderViewModel(container.articleRepository, container.feedRepository, container.favoriteRepository, sweepWorkInfos)
            })
            ReaderScreen(vm, onSweep = { SweepWorker.enqueueRefresh(context) })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = savedStateViewModelFactory { savedState ->
                SettingsViewModel(
                    container.providerSettings,
                    container.apiKeyVault,
                    container.configRepository,
                    container.llmCallRepository,
                    container.stateImporter,
                    container.stateBackupRepository,
                    container::testProviderConnection,
                    scheduleReports = { config -> ReportScheduler(appContext).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes) },
                    savedState = savedState,
                )
            })
            SettingsScreen(vm, onOpenDiagnostics = { nav.navigate("diagnostics") })
        }
        composable("diagnostics") {
            DiagnosticsScreen(
                diagnosticsViewModel(container, null),
                startupFailure(context),
                onRunNow = { DailyReportWorker.enqueue(context, scheduled = false) },
                onOpenFeeds = { nav.navigateTopLevel("feeds") },
                onOpenSettings = { nav.navigateTopLevel("settings") },
            )
        }
        composable("runDiagnostics/{runId}") { entry ->
            val runId = entry.arguments?.getString("runId")
            DiagnosticsScreen(
                diagnosticsViewModel(container, runId),
                startupFailure(context),
                onRunNow = { DailyReportWorker.enqueue(context, scheduled = false) },
                onOpenFeeds = { nav.navigateTopLevel("feeds") },
                onOpenSettings = { nav.navigateTopLevel("settings") },
            )
        }
    }
}

private fun forwardEnter(): EnterTransition = fadeIn() + slideInHorizontally { it / 12 }
private fun forwardExit(): ExitTransition = fadeOut() + slideOutHorizontally { -it / 16 }
private fun backwardEnter(): EnterTransition = fadeIn() + slideInHorizontally { -it / 12 }
private fun backwardExit(): ExitTransition = fadeOut() + slideOutHorizontally { it / 16 }

@Composable
private fun reportViewModel(container: AppContainer, date: String): ReportViewModel = viewModel(
    key = "report-$date",
    factory = savedStateViewModelFactory { savedState ->
        ReportViewModel(
            date,
            container.reportRepository,
            container.favoriteRepository,
            generateGroup = { source -> container.generatePart2Group(date, source) },
            savedState = savedState,
        )
    },
)

@Composable
private fun diagnosticsViewModel(container: AppContainer, runId: String?): DiagnosticsViewModel = viewModel(
    key = "diagnostics-${runId ?: "latest"}",
    factory = savedStateViewModelFactory { savedState ->
        DiagnosticsViewModel(
            runId,
            savedState,
            container.runRepository,
            container.runLogRepository,
            container.llmCallRepository,
            container.artifactStore,
            container.feedRepository,
            container.networkDiagnostics,
            container::currentNetworkContext,
        )
    },
)

private fun startupFailure(context: android.content.Context): String? =
    (context.applicationContext as? DailyNewsApplication)?.startupFailure
