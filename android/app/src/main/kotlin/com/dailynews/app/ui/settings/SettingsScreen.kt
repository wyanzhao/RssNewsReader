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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.BuildConfig
import com.dailynews.app.ui.common.InfoCard
import com.dailynews.app.ui.common.ProviderTypePicker
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderSort
import com.dailynews.llm.ProviderType
import com.dailynews.llm.ReasoningEffort
import com.dailynews.llm.StructuredMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showConnectionOptions by remember { mutableStateOf(false) }
    var showLegacyModels by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.providerMessage) {
        state.providerMessage?.let { snackbars.showSnackbar(it.take(240)) }
    }
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
        uri?.let { viewModel.importDeviceState { readBoundedBytes(context, it) } }
    }
    val stateExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            viewModel.exportDeviceState { writeZip -> context.contentResolver.openOutputStream(it)?.use { output -> writeZip(output) } != null }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
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
                    item { SettingsEntry("模型服务", providerSummary(state), { viewModel.selectSection(SettingsSection.PROVIDERS) }) }
                    item { SettingsEntry("计划与后台", "每日 ${state.form.schedule} · ${state.form.sweepInterval} 分钟增量抓取", { viewModel.selectSection(SettingsSection.SCHEDULE) }) }
                    item { SettingsEntry("生成流程", "Top ${state.form.topN} · 仅产出 Part 1 精选", { viewModel.selectSection(SettingsSection.PIPELINE) }) }
                    item { SettingsEntry("数据与迁移", "导入、导出与设备状态恢复", { viewModel.selectSection(SettingsSection.DATA) }) }
                    item { SettingsEntry("运行诊断", "最近运行、步骤日志、LLM 调用与网络探测", onOpenDiagnostics) }
                }
                SettingsSection.PROVIDERS -> providerItems(state, viewModel, showConnectionOptions, { showConnectionOptions = !showConnectionOptions }, showLegacyModels, { showLegacyModels = !showLegacyModels }, { pendingDeleteId = it })
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
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除服务 $id？") },
            text = { Text("已保存的 API key 会一并清除，且无法恢复。若此服务正被新闻精选或 Part 2 模型使用，需先在模型设置中更换服务后再删除。") },
            confirmButton = {
                TextButton(onClick = { pendingDeleteId = null; viewModel.deleteProvider(id) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
        )
    }
}

private fun sectionTitle(section: SettingsSection): String = when (section) {
    SettingsSection.OVERVIEW -> "设置"
    SettingsSection.PROVIDERS -> "模型服务"
    SettingsSection.SCHEDULE -> "计划与后台"
    SettingsSection.PIPELINE -> "生成流程"
    SettingsSection.DATA -> "数据与迁移"
}

private fun providerSummary(state: SettingsUiState): String = state.savedProviders?.providers?.takeIf(List<*>::isNotEmpty)
    ?.joinToString { "${it.id} (${it.type.displayLabel})" } ?: "尚未配置，添加服务后选择生成模型"

@Composable
private fun SettingsEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DailyNewsSpacing.roomy), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One saved provider: tapping the row edits it, the trailing button asks for
 * delete confirmation. The subtitle reports what management otherwise hides —
 * which role mapping points here and whether the encrypted vault still holds
 * the key (it can come back empty after a device restore).
 */
@Composable
private fun ProviderRow(provider: ProviderConfig, roleBadge: String, hasKey: Boolean?, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = DailyNewsSpacing.roomy, top = DailyNewsSpacing.roomy, bottom = DailyNewsSpacing.roomy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(provider.id, style = MaterialTheme.typography.titleLarge)
                val details = buildList {
                    add(provider.type.displayLabel)
                    if (roleBadge.isNotEmpty()) add(roleBadge)
                    when (hasKey) {
                        true -> add("已存 API key")
                        false -> add("缺 API key")
                        null -> {}
                    }
                }
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasKey == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun roleBadgeFor(state: SettingsUiState, providerId: String): String {
    val mapping = state.savedProviders?.mapping ?: return ""
    val roles = buildList {
        if (mapping.editor.providerId == providerId) add("新闻精选使用中")
        if (mapping.drafter.providerId == providerId) add("Part 2 使用中")
    }
    return roles.joinToString(" · ")
}

private fun androidx.compose.foundation.lazy.LazyListScope.providerItems(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    showConnectionOptions: Boolean,
    toggleConnectionOptions: () -> Unit,
    showLegacyModels: Boolean,
    toggleLegacyModels: () -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val form = state.form
    val savedProviders = state.savedProviders?.providers.orEmpty()
    item { Text("已保存的服务", style = MaterialTheme.typography.titleLarge) }
    if (savedProviders.isEmpty()) {
        item {
            Text(
                "尚未添加服务。添加服务并保存 API key 后，再到下方选择新闻精选模型即可开始生成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    savedProviders.forEach { provider ->
        item(key = "provider-${provider.id}") {
            ProviderRow(
                provider = provider,
                roleBadge = roleBadgeFor(state, provider.id),
                hasKey = state.keyStatus[provider.id],
                onEdit = { viewModel.editProvider(provider.id) },
                onDelete = { onRequestDelete(provider.id) },
            )
        }
    }
    item { OutlinedButton(onClick = viewModel::newProvider, enabled = !state.busy) { Text("添加服务") } }
    item { Text(if (form.editingProvider) "编辑服务：${form.providerId}" else "添加新服务", style = MaterialTheme.typography.titleLarge) }
    item { ProviderTypePicker(form.providerType, viewModel::selectProviderType) }
    item { OutlinedTextField(form.providerId, { value -> viewModel.update { it.copy(providerId = value) } }, label = { Text("服务标识") }, readOnly = form.editingProvider, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    item {
        OutlinedTextField(
            form.baseUrl,
            { value -> viewModel.update { it.copy(baseUrl = value) } },
            label = { Text(baseUrlLabel(form.providerType)) },
            supportingText = { Text(baseUrlSupporting(form.providerType)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        OutlinedTextField(
            form.apiKey,
            { value -> viewModel.update { it.copy(apiKey = value) } },
            label = { Text(if (form.editingProvider) "API key（留空保留，输入则更换）" else "API key（必填）") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item { TextButton(onClick = toggleConnectionOptions) { Text(if (showConnectionOptions) "收起高级连接设置" else "高级连接设置") } }
    if (showConnectionOptions) {
        if (form.providerType.usesOpenAiCompatApi) item {
            Row { Checkbox(form.supportsJsonMode, { value -> viewModel.update { it.copy(supportsJsonMode = value) } }); Text("支持 response_format=json_object", Modifier.padding(top = 12.dp)) }
        }
        item {
            EnumDropdown("结构化输出", form.structuredMode.name, StructuredMode.entries.map(StructuredMode::name)) { value ->
                viewModel.update { it.copy(structuredMode = StructuredMode.valueOf(value)) }
            }
        }
        if (form.providerType == ProviderType.OPENROUTER) {
            item { Text("OpenRouter 路由", style = MaterialTheme.typography.titleMedium) }
            item {
                EnumDropdown("提供商排序", form.routingSort.name, ProviderSort.entries.map(ProviderSort::name)) { value ->
                    viewModel.update { it.copy(routingSort = ProviderSort.valueOf(value)) }
                }
            }
            item {
                OutlinedTextField(
                    form.routingFallbacks,
                    { value -> viewModel.update { it.copy(routingFallbacks = value) } },
                    label = { Text("备选模型（逗号分隔；主模型超时或不可用时依次尝试）") },
                    supportingText = { Text("同样需要厂商前缀。主模型不可用时按这个列表依次换模型。") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row {
                    Checkbox(form.routingRequireParameters, { value -> viewModel.update { it.copy(routingRequireParameters = value) } })
                    Text("只路由到支持 response_format 的提供商", Modifier.padding(top = 12.dp))
                }
            }

        }
    }
    item {
        val canSaveUrl = form.baseUrl.isNotBlank() || form.providerType == ProviderType.OPENROUTER
        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
            Button(onClick = viewModel::saveProvider, enabled = !state.busy && form.providerId.isNotBlank() && canSaveUrl) { Text("保存服务") }
            OutlinedButton(onClick = viewModel::testProvider, enabled = !state.busy && form.editingProvider) { Text("测试 JSON 兼容性") }
        }
    }
    item { Text("测试使用已保存的服务地址与 KEY，以及下方选择的模型和力度；会发起一次小型 API 请求。", style = MaterialTheme.typography.bodySmall) }
    val providerIds = state.savedProviders?.providers?.map { it.id }.orEmpty().ifEmpty { listOf(form.providerId.ifBlank { "default" }) }
    item { Text("新闻精选模型", style = MaterialTheme.typography.titleLarge) }
    item { EnumDropdown("使用服务", form.editorProviderId, providerIds) { value -> viewModel.update { it.copy(editorProviderId = value) } } }
    item {
        OutlinedTextField(
            form.editorModel,
            { value -> viewModel.update { it.copy(editorModel = value) } },
            label = { Text("模型名称") },
            supportingText = { if (state.savedProviders?.providers?.firstOrNull { it.id == form.editorProviderId }?.type == ProviderType.OPENROUTER) Text("OpenRouter 须带前缀，例如 anthropic/claude-sonnet-4") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.savedProviders?.providers?.firstOrNull { it.id == form.editorProviderId }?.type?.defaultModel
        ?.takeIf { it.isNotEmpty() }?.let { presetModel ->
            item { TextButton(onClick = { viewModel.update { it.copy(editorModel = presetModel, editorReasoningEffort = ReasoningEffort.LOW) } }) { Text("使用预设模型：$presetModel") } }
        }
    item { NumberField("单次输出 token 上限", form.editorMaxTokens, state.validationErrors["editorMaxTokens"]) { value -> viewModel.update { it.copy(editorMaxTokens = value) } } }
    item {
        ReasoningEffortPicker("推理力度", form.editorReasoningEffort, state.savedProviders?.providers?.firstOrNull { it.id == form.editorProviderId }?.let { com.dailynews.llm.modelPolicyFor(it, form.editorModel).efforts } ?: ReasoningEffort.entries) { value ->
            viewModel.update { it.copy(editorReasoningEffort = value) }
        }
    }
    item { TextButton(onClick = toggleLegacyModels) { Text(if (showLegacyModels) "收起兼容配置" else "兼容配置（Part 2 已停用）") } }
    if (showLegacyModels) {
        item { EnumDropdown("Part 2 Provider", form.drafterProviderId, providerIds) { value -> viewModel.update { it.copy(drafterProviderId = value) } } }
        item {
            OutlinedTextField(
                form.drafterModel,
                { value -> viewModel.update { it.copy(drafterModel = value) } },
                label = { Text("Part 2 经济模型") },
                supportingText = { if (form.providerType == ProviderType.OPENROUTER) Text("OpenRouter 须带前缀，例如 openai/gpt-4o-mini") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { NumberField("DRAFTER maxTokens", form.drafterMaxTokens, state.validationErrors["drafterMaxTokens"]) { value -> viewModel.update { it.copy(drafterMaxTokens = value) } } }
        item {
            ReasoningEffortPicker("DRAFTER reasoning", form.drafterReasoningEffort, state.savedProviders?.providers?.firstOrNull { it.id == form.drafterProviderId }?.let { com.dailynews.llm.modelPolicyFor(it, form.drafterModel).efforts } ?: ReasoningEffort.entries) { value ->
                viewModel.update { it.copy(drafterReasoningEffort = value) }
            }
        }
    }
    item {
        Text(
            "推理力度默认低。DeepSeek 预设的关闭会禁用思考；GLM-5.3-Flash 不支持关闭。通用兼容服务的关闭仅省略参数，实际行为由服务决定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item { Button(onClick = viewModel::saveRoleMapping, enabled = !state.busy && state.validationErrors.keys.none { it.endsWith("MaxTokens") }) { Text("保存模型设置") } }
    state.providerMessage?.let { item { Text(it) } }
}

private fun baseUrlLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENROUTER -> "Base URL（可留空，默认 OpenRouter 官方）"
    ProviderType.OPENAI_COMPAT -> "Base URL"
    ProviderType.ANTHROPIC, ProviderType.DEEPSEEK, ProviderType.ZAI -> "Base URL"
}

private fun baseUrlSupporting(type: ProviderType): String = when (type) {
    ProviderType.OPENROUTER -> "默认 https://openrouter.ai/api/v1，只有走代理时才需要改。"
    ProviderType.OPENAI_COMPAT -> "OpenAI 官方为 https://api.openai.com/v1；兼容端点填它们自己的地址。"
    ProviderType.ANTHROPIC -> "Anthropic 官方为 https://api.anthropic.com。"
    ProviderType.DEEPSEEK -> "官方 https://api.deepseek.com/v1；选择此预设启用 DeepSeek 参数适配。"
    ProviderType.ZAI -> "官方通用 API：https://api.z.ai/api/paas/v4。"
}

private fun androidx.compose.foundation.lazy.LazyListScope.scheduleItems(state: SettingsUiState, viewModel: SettingsViewModel, context: android.content.Context) {
    val form = state.form
    item {
        OutlinedButton(onClick = {
            val parts = form.schedule.split(":")
            android.app.TimePickerDialog(
                context,
                { _, hour, minute -> viewModel.update { it.copy(schedule = String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)) } },
                parts.getOrNull(0)?.toIntOrNull() ?: 10,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                true,
            ).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("每日运行时间：${form.schedule} · 选择时间") }
    }
    item { NumberField("后台增量抓取间隔（分钟）", form.sweepInterval, state.validationErrors["sweepInterval"]) { value -> viewModel.update { it.copy(sweepInterval = value) } } }
    item { Row { Checkbox(form.wifiOnly, { value -> viewModel.update { it.copy(wifiOnly = value) } }); Text("仅在 Wi-Fi 下抓取文章页正文", Modifier.padding(top = 12.dp)) } }
    if (BuildConfig.DEBUG) item { Row { Checkbox(form.useLegacySingleShotFetch, { value -> viewModel.update { it.copy(useLegacySingleShotFetch = value) } }); Text("调试：旧单次抓取路径", Modifier.padding(top = 12.dp)) } }
    item { OutlinedTextField(form.topicsText, { value -> viewModel.update { it.copy(topicsText = value) } }, label = { Text("长期关注主题（每行一条）") }, supportingText = { Text("例如：AI 编译器、存储系统。最多 20 条，每条 80 字；用于后续选题。") }, modifier = Modifier.fillMaxWidth()) }
    if (state.config.watches.events.isNotEmpty()) {
        item { Text("关注的事件", style = MaterialTheme.typography.titleLarge) }
        state.config.watches.events.forEach { watch ->
            item(key = "event-watch-${watch.eventKey}") {
                Column {
                    Text(watch.title)
                    TextButton(onClick = { viewModel.removeEventWatch(watch.eventKey) }, enabled = !state.busy) { Text("取消关注") }
                }
            }
        }
    }
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
            InfoCard("未加入电池优化白名单：Doze 会在灭屏后掐断本应用的后台网络，定时报告会顺延。", "允许后台运行") {
                // The targeted intent raises the system allow/deny dialog on the spot.
                // ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS only opens the global list,
                // where the user still has to switch the filter to "all apps" and hunt for
                // DailyNews — so it is the fallback, for OEMs that strip the dialog activity.
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${context.packageName}".toUri(),
                )
                runCatching { context.startActivity(direct) }.onFailure {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
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
            if (used == null) "本月 token：读取中…" else "本月 token：$used / ${if (state.config.monthlyTokenBudget == 0L) "不限" else state.config.monthlyTokenBudget.toString()}${if (ratio >= 0.8) " · 已达 80% 告警线" else ""}",
            color = if (ratio >= 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    item { NumberField("Top N", form.topN, state.validationErrors["topN"]) { value -> viewModel.update { it.copy(topN = value) } } }
    item { NumberField("调试产物保留天数", form.retention, state.validationErrors["retention"]) { value -> viewModel.update { it.copy(retention = value) } } }
    item { NumberField("文章池保留天数", form.articleRetention, state.validationErrors["articleRetention"]) { value -> viewModel.update { it.copy(articleRetention = value) } } }
    item { NumberField("Part 2 报告条目保留天数", form.reportRetention, state.validationErrors["reportRetention"]) { value -> viewModel.update { it.copy(reportRetention = value) } } }
    item {
        Row {
            Checkbox(form.tokenBudget == "0", { unlimited -> viewModel.update { it.copy(tokenBudget = if (unlimited) "0" else "1000000") } })
            Text("月度 token 预算不限", Modifier.padding(top = 12.dp))
        }
    }
    if (form.tokenBudget != "0") item { NumberField("月度 token 预算（0 表示不限）", form.tokenBudget, state.validationErrors["tokenBudget"]) { value -> viewModel.update { it.copy(tokenBudget = value) } } }
    item { NumberField("每次运行 LLM 调用上限", form.maxLlmCalls, state.validationErrors["maxLlmCalls"]) { value -> viewModel.update { it.copy(maxLlmCalls = value) } } }
    item { Text("LLM 正式生成", style = MaterialTheme.typography.titleLarge) }
    item { NumberField("连接超时（秒）", form.llmConnectTimeoutSeconds, state.validationErrors["llmConnectTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmConnectTimeoutSeconds = value) } } }
    item { NumberField("读取超时（秒）", form.llmReadTimeoutSeconds, state.validationErrors["llmReadTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmReadTimeoutSeconds = value) } } }
    item { NumberField("单次调用总超时（秒）", form.llmCallTimeoutSeconds, state.validationErrors["llmCallTimeoutSeconds"]) { value -> viewModel.update { it.copy(llmCallTimeoutSeconds = value) } } }
    item { Text("报告仅生成 Top N 新闻精选；全部文章可在阅读器查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { OutlinedTextField(form.feedbackText, { value -> viewModel.update { it.copy(feedbackText = value) } }, label = { Text("编辑反馈（每行一条，最多 20 条）") }, modifier = Modifier.fillMaxWidth()) }
    item { Button(onClick = viewModel::savePipeline, enabled = !state.busy && state.validationErrors.isEmpty()) { Text("保存生成流程") } }
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
private fun ReasoningEffortPicker(label: String, selected: ReasoningEffort, options: List<ReasoningEffort> = ReasoningEffort.entries, onSelect: (ReasoningEffort) -> Unit) {
    EnumDropdown(label, selected.menuLabel + if (selected !in options) " · 此模型不支持，请重新选择" else "", options.map(ReasoningEffort::menuLabel)) { value ->
        onSelect(ReasoningEffort.entries.first { it.menuLabel == value })
    }
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

/**
 * Measure before reading.
 *
 * Previously this was a bare `readBytes()` with the size check inside `importZip` — meaning the
 * array had already been allocated before anything asked whether it was too large. Picking the
 * wrong file (or something hundreds of MB big) pushed the process into memory pressure before
 * validation ever happened. SAF can report the size without reading the content, so ask it first.
 */
private fun readBoundedBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? {
    val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
    require(size == null || size <= MAX_IMPORT_BYTES) {
        "所选文件 ${size?.div(1_048_576)} MB，超过 ${MAX_IMPORT_BYTES / 1_048_576} MB 上限"
    }
    return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

/** A cap the phone can comfortably hold, well below the nominal 64 MiB of StateBackupRepository. */
private const val MAX_IMPORT_BYTES = 48L * 1_048_576
