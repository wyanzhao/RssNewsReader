package com.dailynews.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import com.dailynews.model.EventDevelopment

@Composable
fun EventDevelopmentContent(development: EventDevelopment, onOpenSource: (String) -> Unit) {
    var expanded by rememberSaveable(development) { mutableStateOf(false) }
    Column {
        Text("本次进展 · AI 判断", style = MaterialTheme.typography.titleSmall)
        Text("相较 ${development.baselineDate} 的报道", style = MaterialTheme.typography.labelMedium)
        Text(development.changeZh, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起来源证据" else "查看来源证据") }
        if (expanded) {
            Text(development.evidenceQuote, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onOpenSource(development.evidenceLink) }) { Text("打开证据原文") }
        }
    }
}
