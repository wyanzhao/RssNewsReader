package com.dailynews.app.ui.article

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dailynews.model.ReadingPreferences
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun AnnotationDialog(note: String, tags: List<String>, save: suspend (String, List<String>) -> Boolean, close: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(note) }
    var tagText by rememberSaveable { mutableStateOf(tags.joinToString(", ")) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 560.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = { if (!saving) close() }, title = { Text("标签与笔记") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(tagText, { tagText = it }, label = { Text("标签，用逗号分隔") }, singleLine = true, supportingText = { Text("最多 10 个，每个 40 字") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft, { draft = it }, label = { Text("笔记") }, maxLines = 7, modifier = Modifier.fillMaxWidth().height(180.dp))
            Text("保存标签或笔记时会同时收藏文章。清空后保存可删除标签与笔记。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = { scope.launch { saving = true; try {
            val validated = runCatching { com.dailynews.model.ArticleAnnotations(draft, tagText.split(',', '，')).validated() }
            if (validated.isFailure) error = validated.exceptionOrNull()?.message
            else if (save(draft, validated.getOrThrow().tags)) close() else error = "保存失败，请稍后重试。"
        } finally { saving = false } } }) { Text("保存") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("取消") } })
}

@Composable
internal fun ReadingDialog(value: ReadingPreferences, save: suspend (ReadingPreferences) -> Boolean, close: () -> Unit) {
    var size by rememberSaveable { mutableIntStateOf(value.fontSizeSp) }
    var spacing by rememberSaveable { mutableIntStateOf(value.lineHeightPercent) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text("阅读排版") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("字号：$size")
            Slider(size.toFloat(), { size = it.roundToInt() }, valueRange = 14f..30f, steps = 15)
            Text("行距：$spacing%")
            Slider(spacing.toFloat(), { spacing = it.roundToInt() }, valueRange = 120f..200f, steps = 7)
            Text("应用于所有文章，并跟随系统字体缩放。更改排版后从文章开头显示。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = { scope.launch { saving = true; try { if (save(ReadingPreferences(size, spacing))) close() else error = "保存失败，请稍后重试。" } finally { saving = false } } }) { Text("保存") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("取消") } })
}
