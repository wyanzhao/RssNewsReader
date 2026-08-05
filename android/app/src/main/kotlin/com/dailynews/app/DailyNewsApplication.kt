package com.dailynews.app

import android.app.Application
import com.dailynews.app.notify.NotificationHelper
import com.dailynews.app.work.ReportScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class DailyNewsApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Non-null when start-up bookkeeping failed; surfaced in the diagnostics screen. */
    @Volatile var startupFailure: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // First touch of the database and the alarm APIs. A failure here must
            // not take down every launch — the UI can still run and report it.
            runCatching {
                container.runRepository.recoverInterruptedRuns()
                container.feedRepository.seedIfEmpty()
                val config = container.configRepository.config.first()
                ReportScheduler(this@DailyNewsApplication).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
            }.onFailure { startupFailure = it.message ?: it::class.simpleName }
        }
    }
}
