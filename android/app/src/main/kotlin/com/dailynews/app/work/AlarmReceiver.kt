package com.dailynews.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import com.dailynews.app.DailyNewsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DailyReportWorker.enqueue(
            context,
            scheduled = intent?.getBooleanExtra(DailyReportWorker.KEY_SCHEDULED, true) ?: true,
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as DailyNewsApplication
                val config = app.container.configRepository.config.first()
                ReportScheduler(context).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
            } finally {
                pending.finish()
            }
        }
    }
}
