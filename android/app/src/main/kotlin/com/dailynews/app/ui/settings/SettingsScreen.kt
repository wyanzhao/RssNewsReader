package com.dailynews.app.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.BuildConfig
import com.dailynews.app.R
import com.dailynews.app.ui.common.InfoCard
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.llm.ProviderType
import com.dailynews.llm.StructuredMode
import com.dailynews.model.Part2Mode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = state.section != SettingsSection.OVERVIEW) {
        viewModel.selectSection(SettingsSection.OVERVIEW)
    }
    val seenImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importSeenLinks { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } } }
    }
    val cacheImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importCache { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } } }
    }
    val stateImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDeviceState { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } } }
    }
    val stateExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            viewModel.exportDeviceState { writeZip -> context.contentResolver.openOutputStream(it)?.use { output -> writeZip(output) } != null }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle(state.section)) },
                navigationIcon = {
                    if (state.section != SettingsSection.OVERVIEW) TextButton(onClick = { viewModel.selectSection(SettingsSection.OVERVIEW) }) { Text("返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(DailyNewsSpacing.roomy),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
        ) {
            when (state.section) {
                SettingsSection.OVERVIEW -> {
                    item { SettingsEntry("Providers", providerSummary(state), { viewModel.selectSection(SettingsSection.PROVIDERS) }) }
                    item { SettingsEntry("计划与后台", "每日 ${state.form.schedule} · ${state.form.sweepInterval} 分钟增量抓取", { viewModel.selectSection(SettingsSection.SCHEDULE) }) }
                    item { SettingsEntry("Pipeline", "Top ${state.form.topN} · Part 2 ${state.form.part2Mode}", { viewModel.selectSection(SettingsSection.PIPELINE) }) }
                    item { SettingsEntry("数据与迁移", "导入、导出与设备状态恢复", { viewModel.selectSection(SettingsSection.DATA) }) }
                    item { SettingsEntry("运行诊断", "最近运行、步骤日志、LLM 调用与网络探测", onOpenDiagnostics) }
                }
                SettingsSection.PROVIDERS -> providerItems(state, viewModel)
                SettingsSection.SCHEDULE -> scheduleItems(state, viewModel, context)
                SettingsSection.PIPELINE -> pipelineItems(state, viewModel)
                SettingsSection.DATA -> dataItems(
                    state,
                    onExportState = { stateExporter.launch("dailynews-device-state.zip") },
                    onImportState = { stateImporter.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onImportSeen = { seenImporter.launch(arrayOf("application/json", "*/*")) },
                    onImportCache = { cacheImporter.launch(arrayOf("application/json", "*/*")) },
                )
            }
        }
    }
}

private fun sectionTitle(section: SettingsSection): String = when (section) {
    SettingsSection.OVERVIEW -> "设置"
    SettingsSection.PROVIDERS -> "Providers"
    SettingsSection.SCHEDULE -> "计划与后台"
    SettingsSection.PIPELINE -> "Pipeline"
    SettingsSection.DATA -> "数据与迁移"
}

private fun providerSummary(state: SettingsUiState): String = state.savedProviders?.providers?.takeIf(List<*>::isNotEmpty)
    ?.joinToString { "${it.id} (${it.type})" } ?: "尚未配置；编辑分支会 fail closed"

@Composable
private fun SettingsEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.providerItems(state: SettingsUiState, viewModel: SettingsViewModel) {
    val form = state.form
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            ProviderType.entries.forEach { type -> OutlinedButton(onClick = { viewModel.update { it.copy(providerType = type) } }) { Text(type.name) } }
        }
    }
    item { OutlinedTextField(form.providerId, { value -> viewModel.update { it.copy(providerId = value) } }, label = { Text("Provider ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    item { OutlinedTextField(form.baseUrl, { value -> viewModel.update { it.copy(baseUrl = value) } }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    item {
        OutlinedTextField(
            form.apiKey,
            { value -> viewModel.update { it.copy(apiKey = value) } },
            label = { Text("API key（留空则保留已有密钥）") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (form.providerType == ProviderType.OPENAI_COMPAT) item {
        Row { Checkbox(form.supportsJsonMode, { value -> viewModel.update { it.copy(supportsJsonMode = value) } }); Text("支持 response_format=json_object", Modifier.padding(top = 12.dp)) }
    }
    item {
        EnumDropdown("结构化输出", form.structuredMode.name, StructuredMode.entries.map(StructuredMode::name)) { value ->
            viewModel.update { it.copy(structuredMode = StructuredMode.valueOf(value)) }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Button(onClick = viewModel::saveProvider, enabled = !state.busy && form.providerId.isNotBlank() && form.baseUrl.isNotBlank()) { Text("保存 Provider") }
            OutlinedButton(onClick = viewModel::testProvider, enabled = !state.busy && form.providerId.isNotBlank()) { Text("测试连接") }
        }
    }
    val providerIds = state.savedProviders?.providers?.map { it.id }.orEmpty().ifEmpty { listOf(form.providerId.ifBlank { "default" }) }
    item { Text("角色映射", style = MaterialTheme.typography.titleLarge) }
    item { EnumDropdown("Part 1 Provider", form.editorProviderId, providerIds) { value -> viewModel.update { it.copy(editorProviderId = value) } } }
    item { OutlinedTextField(form.editorModel, { value -> viewModel.update { it.copy(editorModel = value) } }, label = { Text("Part 1 强模型") }, modifier = Modifier.fillMaxWidth()) }
    item { NumberField("Part 1 maxTokens", form.editorMaxTokens, state.validationErrors["editorMaxTokens"]) { value -> viewModel.update { it.copy(editorMaxTokens = value) } } }
    item { EnumDropdown("Part 2 Provider", form.drafterProviderId, providerIds) { value -> viewModel.update { it.copy(drafterProviderId = value) } } }
    item { OutlinedTextField(form.drafterModel, { value -> viewModel.update { it.copy(drafterModel = value) } }, label = { Text("Part 2 经济模型") }, modifier = Modifier.fillMaxWidth()) }
    item { NumberField("Part 2 maxTokens", form.drafterMaxTokens, state.validationErrors["drafterMaxTokens"]) { value -> viewModel.update { it.copy(drafterMaxTokens = value) } } }
    item { Button(onClick = viewModel::saveRoleMapping, enabled = !state.busy && state.validationErrors.keys.none { it.endsWith("MaxTokens") }) { Text("保存角色映射") } }
    state.providerMessage?.let { item { Text(it) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.scheduleItems(state: SettingsUiState, viewModel: SettingsViewModel, context: android.content.Context) {
    val form = state.form
    item {
        OutlinedTextField(
            form.schedule,
            { value -> viewModel.update { it.copy(schedule = value) } },
            label = { Text("每日时间 HH:mm") },
            isError = state.validationErrors["schedule"] != null,
            supportingText = { state.validationErrors["schedule"]?.let { Text(it) } },
        )
    }
    item { NumberField("后台增量抓取间隔（分钟）", form.sweepInterval, state.validationErrors["sweepInterval"]) { value -> viewModel.update { it.copy(sweepInterval = value) } } }
    item { Row { Checkbox(form.wifiOnly, { value -> viewModel.update { it.copy(wifiOnly = value) } }); Text("仅在 Wi-Fi 下抓取文章页正文", Modifier.padding(top = 12.dp)) } }
    if (BuildConfig.DEBUG) item { Row { Checkbox(form.useLegacySingleShotFetch, { value -> viewModel.update { it.copy(useLegacySingleShotFetch = value) } }); Text("调试：旧单次抓取路径", Modifier.padding(top = 12.dp)) } }
    item { Button(onClick = viewModel::savePipeline, enabled = !state.busy && state.validationErrors.isEmpty()) { Text("保存计划与后台设置") } }
    item {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            InfoCard("精确闹钟未授权，当前自动回退 ±15 分钟窗口。", "授权") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
            }
        }
    }
    item {
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
            InfoCard("后台长任务可能被省电策略中断；请允许 DailyNews 后台运行。", "打开电池优化设置") {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pipelineItems(state: SettingsUiState, viewModel: SettingsViewModel) {
    val form = state.form
    item {
        val used = state.monthTokens
        val ratio = if (used != null && state.config.monthlyTokenBudget > 0) used.toDouble() / state.config.monthlyTokenBudget else 0.0
        Text(
            if (used == null) "本月 token：读取中…" else "本月 token：$used / ${state.config.monthlyTokenBudget}${if (ratio >= 0.8) " · 已达 80% 告警线" else ""}",
            color = if (ratio >= 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    item { NumberField("Top N", form.topN, state.validationErrors["topN"]) { value -> viewModel.update { it.copy(topN = value) } } }
    item { NumberField("调试产物保留天数", form.retention, state.validationErrors["retention"]) { value -> viewModel.update { it.copy(retention = value) } } }
    item { NumberField("文章池保留天数", form.articleRetention, state.validationErrors["articleRetention"]) { value -> viewModel.update { it.copy(articleRetention = value) } } }
    item { NumberField("月度 token 预算", form.tokenBudget, state.validationErrors["tokenBudget"]) { value -> viewModel.update { it.copy(tokenBudget = value) } } }
    item { NumberField("每次运行 LLM 调用上限", form.maxLlmCalls, state.validationErrors["maxLlmCalls"]) { value -> viewModel.update { it.copy(maxLlmCalls = value) } } }
    item { Text("LLM 正式生成", style = MaterialTheme.typography.titleLarge) }
    item { NumberField("连接超时（秒）", form.llmConnectTimeoutSeconds, state.validationErrors["llmConnectTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmConnectTimeoutSeconds = value) } } }
    item { NumberField("读取超时（秒）", form.llmReadTimeoutSeconds, state.validationErrors["llmReadTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmReadTimeoutSeconds = value) } } }
    item { NumberField("单次调用总超时（秒）", form.llmCallTimeoutSeconds, state.validationErrors["llmCallTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmCallTimeoutSeconds = value) } } }
    item { NumberField("Part 1 shortlist maxTokens", form.part1ShortlistMaxTokens, state.validationErrors["part1ShortlistMaxTokens"]) { value -> viewModel.update { it.copy(part1ShortlistMaxTokens = value) } } }
    item { NumberField("Part 1 plan maxTokens", form.part1PlanMaxTokens, state.validationErrors["part1PlanMaxTokens"]) { value -> viewModel.update { it.copy(part1PlanMaxTokens = value) } } }
    item { NumberField("Part 2 batch maxTokens", form.part2BatchMaxTokens, state.validationErrors["part2BatchMaxTokens"]) { value -> viewModel.update { it.copy(part2BatchMaxTokens = value) } } }
    item { Row { Checkbox(form.part2Mode == Part2Mode.LAZY, { lazy -> viewModel.update { it.copy(part2Mode = if (lazy) Part2Mode.LAZY else Part2Mode.FULL) } }); Text("Part 2 展开来源时生成（LAZY）", Modifier.padding(top = 12.dp)) } }
    item { OutlinedTextField(form.feedbackText, { value -> viewModel.update { it.copy(feedbackText = value) } }, label = { Text("编辑反馈（每行一条，最多 20 条）") }, modifier = Modifier.fillMaxWidth()) }
    item { Button(onClick = viewModel::savePipeline, enabled = !state.busy && state.validationErrors.isEmpty()) { Text("保存 Pipeline") } }
    state.providerMessage?.let { item { Text(it) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dataItems(
    state: SettingsUiState,
    onExportState: () -> Unit,
    onImportState: () -> Unit,
    onImportSeen: () -> Unit,
    onImportCache: () -> Unit,
) {
    item { Text("完整设备状态", style = MaterialTheme.typography.titleLarge) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            OutlinedButton(onClick = onExportState, enabled = !state.busy) { Text("导出全部状态") }
            OutlinedButton(onClick = onImportState, enabled = !state.busy) { Text("恢复全部状态") }
        }
    }
    item { Text("Python 历史迁移", style = MaterialTheme.typography.titleLarge) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            OutlinedButton(onClick = onImportSeen, enabled = !state.busy) { Text("导入 seen-links") }
            OutlinedButton(onClick = onImportCache, enabled = !state.busy) { Text("导入摘要缓存") }
        }
    }
    state.importMessage?.let { item { Text(it) } }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, onValue: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onValue(it.filter(Char::isDigit)) },
        label = { Text(label) },
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EnumDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}
