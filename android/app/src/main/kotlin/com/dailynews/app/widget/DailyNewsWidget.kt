package com.dailynews.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.MainActivity
import com.dailynews.data.repo.WidgetReportSnapshot

class DailyNewsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = (context.applicationContext as? DailyNewsApplication)?.container?.reportRepository?.widgetSnapshot()
        provideContent { GlanceTheme { WidgetContent(snapshot) } }
    }
}

class DailyNewsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyNewsWidget()
}

private val routeKey = ActionParameters.Key<String>("route")

internal fun widgetRoute(snapshot: WidgetReportSnapshot?): String = snapshot?.reportDate?.let { "report/$it" } ?: "brief"

@Composable
internal fun WidgetContent(snapshot: WidgetReportSnapshot?) {
    val route = widgetRoute(snapshot)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(routeKey to route))),
    ) {
        Text("DailyNews", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(8.dp))
        when {
            snapshot == null -> Text("还没有报告 · 点按打开今日页")
            snapshot.status != "SUCCESS" -> {
                Text("${snapshot.reportDate} · 生成失败", style = TextStyle(color = ColorProvider(Color(0xFFB3261E))))
                Text("点按查看失败报告")
            }
            else -> {
                Text(snapshot.reportDate, style = TextStyle(fontWeight = FontWeight.Medium))
                snapshot.titles.take(3).forEachIndexed { index, title ->
                    Text("${index + 1}. $title", maxLines = 1)
                }
            }
        }
    }
}
