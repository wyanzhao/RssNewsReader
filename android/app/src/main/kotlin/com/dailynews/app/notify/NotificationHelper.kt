package com.dailynews.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dailynews.app.MainActivity
import com.dailynews.app.work.DailyReportWorker
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import java.time.LocalDate

object NotificationHelper {
    const val PROGRESS_CHANNEL = "run_progress"
    const val READY_CHANNEL = "report_ready"
    const val FAILED_CHANNEL = "run_failed"
    const val PROGRESS_ID = 1001
    private const val GROUP_KEY = "dailynews-results"
    private const val GROUP_SUMMARY_ID = 1099

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(PROGRESS_CHANNEL, "Report progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(READY_CHANNEL, "Report ready", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(FAILED_CHANNEL, "Report failures", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun progress(context: Context, text: String) = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
        .setSmallIcon(com.dailynews.app.R.drawable.ic_notification)
        .setContentTitle("DailyNews 正在生成")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

    fun notifyResult(context: Context, result: RunExecutionResult) {
        val notification = resultNotification(context, result)
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).apply {
                notify(result.reportDate.hashCode(), notification)
                notify(
                    GROUP_SUMMARY_ID,
                    NotificationCompat.Builder(context, READY_CHANNEL)
                        .setSmallIcon(com.dailynews.app.R.drawable.ic_notification)
                        .setContentTitle("DailyNews 报告")
                        .setContentText("生成结果与失败诊断")
                        .setGroup(GROUP_KEY)
                        .setGroupSummary(true)
                        .build(),
                )
            }
        }
    }

    internal fun resultNotification(context: Context, result: RunExecutionResult) = when (result) {
            is RunExecutionResult.Success -> {
                val titles = result.report.items.filter { it.part == 1 }.take(3).joinToString(" · ") { it.title }
                NotificationCompat.Builder(context, READY_CHANNEL)
                    .setSmallIcon(com.dailynews.app.R.drawable.ic_notification)
                    .setContentTitle("DailyNews ${result.reportDate} 已生成")
                    .setContentText(titles.ifBlank { "点击查看报告" })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
                    .setContentIntent(openApp(context, "report/${result.reportDate}"))
                    .addAction(0, "分享 Top N", shareTopN(context, result.reportDate.toString(), result.report.topNMarkdown))
                    .setGroup(GROUP_KEY)
                    .setAutoCancel(true)
                    .build()
            }
            is RunExecutionResult.ExpectedBlock -> failureBuilder(context, "校验阻断：${result.validation.blockingReasons.firstOrNull() ?: "无可发布文章"}", result.runId)
            is RunExecutionResult.Failed -> failureBuilder(context, "${result.stage}：${result.message}", result.runId)
    }

    /**
     * A scheduled trigger that never got a network. Posted on the low-importance progress
     * channel, not FAILED_CHANNEL: nothing broke, so it must not buzz the phone or claim
     * "generation failed" — but staying silent would leave a missing daily report unexplained.
     */
    fun notifyDeferred(context: Context, reportDate: LocalDate, message: String, runId: String?) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(reportDate.hashCode(), deferredNotification(context, message, runId))
    }

    internal fun deferredNotification(context: Context, message: String, runId: String?) = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
        .setSmallIcon(com.dailynews.app.R.drawable.ic_notification)
        .setContentTitle("DailyNews 本次运行已顺延")
        .setContentText("没有可用网络，未开始抓取")
        .setStyle(NotificationCompat.BigTextStyle().bigText("没有可用网络，未开始抓取。$message"))
        .setContentIntent(openApp(context, runId?.let { "runDiagnostics/$it" } ?: "diagnostics"))
        .addAction(0, "立即运行", DailyReportWorker.retryIntent(context))
        .setGroup(GROUP_KEY)
        .setAutoCancel(true)
        .build()

    private fun failureBuilder(context: Context, message: String, runId: String?) = NotificationCompat.Builder(context, FAILED_CHANNEL)
        .setSmallIcon(com.dailynews.app.R.drawable.ic_notification)
        .setContentTitle("DailyNews 生成失败")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setContentIntent(openApp(context, runId?.let { "runDiagnostics/$it" } ?: "diagnostics"))
        .addAction(0, "重试", DailyReportWorker.retryIntent(context))
        .addAction(0, "诊断", openApp(context, runId?.let { "runDiagnostics/$it" } ?: "diagnostics"))
        .setGroup(GROUP_KEY)
        .setAutoCancel(true)
        .build()

    private fun openApp(context: Context, route: String): PendingIntent = PendingIntent.getActivity(
        context,
        route.hashCode(),
        Intent(context, MainActivity::class.java).putExtra("route", route),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun shareTopN(context: Context, date: String, markdown: String): PendingIntent {
        val share = topNShareIntent(date, markdown)
        return PendingIntent.getActivity(
            context,
            "share-$date".hashCode(),
            Intent.createChooser(share, "分享 DailyNews Top N"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun topNShareIntent(date: String, markdown: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "DailyNews $date Top N")
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
}
