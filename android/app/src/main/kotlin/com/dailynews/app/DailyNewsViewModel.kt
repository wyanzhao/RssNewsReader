package com.dailynews.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RouteRequest(val route: String? = null, val version: Int = 0)

class DailyNewsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as DailyNewsApplication).container
    val onboardingComplete = container.providerSettings.onboardingComplete

    private val routeState = MutableStateFlow(RouteRequest())
    val route: StateFlow<RouteRequest> = routeState.asStateFlow()

    fun acceptRoute(route: String?) {
        if (route.isNullOrBlank()) return
        routeState.update { current -> RouteRequest(route, current.version + 1) }
    }

    fun consumeRoute(version: Int) {
        routeState.update { current -> if (current.version == version) current.copy(route = null) else current }
    }
}
