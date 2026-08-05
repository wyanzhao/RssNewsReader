package com.dailynews.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dailynews.model.isValidScheduleTime
import java.time.LocalTime
import java.time.ZonedDateTime

class ReportScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)

    fun ensureScheduled(scheduleTime: String, sweepIntervalMinutes: Int = 120) {
        val target = nextTrigger(scheduleTime)
        val operation = alarmIntent(context)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.toInstant().toEpochMilli(), operation)
        } else {
            val center = target.toInstant().toEpochMilli()
            alarms.setWindow(AlarmManager.RTC_WAKEUP, center - 15 * 60_000L, 30 * 60_000L, operation)
        }
        SweepWorker.ensureScheduled(context, sweepIntervalMinutes)
    }

    internal fun nextTrigger(value: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val time = value.takeIf(::isValidScheduleTime)?.let(LocalTime::parse) ?: LocalTime.of(10, 0)
        var target = now.toLocalDate().atTime(time).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target
    }

    companion object {
        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            9001,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
