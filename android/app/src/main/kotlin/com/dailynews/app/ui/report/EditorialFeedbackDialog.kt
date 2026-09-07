package com.dailynews.app.ui.report

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.dailynews.model.ArticleFeedback
import com.dailynews.model.FeedbackKind

@Composable
internal fun EditorialFeedbackDialog(
    current: ArticleFeedback?,
    onDismiss: () -> Unit,
    onSave: (FeedbackKind?, String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current?.kind ?: FeedbackKind.VALUABLE) }
    var topic by rememberSaveable { mutableStateOf(current?.topic.orEmpty()) }
    val valid = selected != FeedbackKind.LESS_TOPIC || topic.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整下一次选题") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text("反馈用于后续简报；当前报告保持原样。")
                FeedbackKind.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected == kind, role = Role.RadioButton) { selected = kind },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == kind, onClick = null)
                        Text(when (kind) {
                            FeedbackKind.VALUABLE -> "有价值，希望多看此类"
                            FeedbackKind.LESS_TOPIC -> "减少某个主题"
                            FeedbackKind.LESS_SOURCE -> "减少这个来源"
                            FeedbackKind.REPETITIVE -> "重复报道，没有新进展"
                            FeedbackKind.FOLLOW_UP -> "希望看到这个事件的后续"
                        })
                    }
                }
                if (selected == FeedbackKind.LESS_TOPIC) {
                    OutlinedTextField(
                        topic, { if (it.length <= 80) topic = it },
                        label = { Text("希望减少的主题") },
                        supportingText = { Text("例如：一般融资消息；最多 80 字") },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected, topic.trim()) }, enabled = valid) { Text("保存反馈") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null, "") }) { Text("撤销反馈") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
