package com.dailynews.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.dailynews.app.R
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.ProviderTypePicker
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.llm.ProviderType
import com.dailynews.model.isValidScheduleTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.notificationsDone()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("设置 DailyNews · ${state.step.ordinal + 1}/5") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(DailyNewsSpacing.section),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
        ) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when (state.step) {
                OnboardingStep.WELCOME -> {
                    item { Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall) }
                    item { Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge) }
                    item { Button(onClick = viewModel::start) { Text("开始设置") } }
                }
                OnboardingStep.NOTIFICATIONS -> {
                    item { Text("生成结果通知", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("DailyNews 只在报告完成或失败时通知你。允许后可以直接打开报告、分享 Top N 或重试；拒绝不会影响手动使用。") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.notificationsDone()
                                } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text("允许通知") }
                            OutlinedButton(onClick = viewModel::notificationsDone) { Text("暂不允许") }
                        }
                    }
                }
                OnboardingStep.PROVIDER -> {
                    item { Text("配置 LLM Provider", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("密钥只保存在 Android 加密存储中。跳过后确定性抓取仍可运行，但编辑分支会明确 fail closed。") }
                    item { ProviderTypePicker(state.type, viewModel::selectProviderType) }
                    item {
                        OutlinedTextField(
                            state.baseUrl,
                            { value -> viewModel.update { it.copy(baseUrl = value) } },
                            label = { Text(stringResource(R.string.base_url)) },
                            supportingText = {
                                Text(
                                    when (state.type) {
                                        ProviderType.OPENROUTER -> "可留空，默认 OpenRouter 官方地址。"
                                        ProviderType.OPENAI_COMPAT -> "OpenAI 官方或 DeepSeek / Kimi 等兼容端点。"
                                        ProviderType.ANTHROPIC -> "Anthropic 官方 Messages API。"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            state.model,
                            { value -> viewModel.update { it.copy(model = value) } },
                            label = { Text(stringResource(R.string.model)) },
                            supportingText = {
                                if (state.type == ProviderType.OPENROUTER) {
                                    Text("须带厂商前缀，例如 anthropic/claude-sonnet-4")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            state.apiKey,
                            { value -> viewModel.update { it.copy(apiKey = value) } },
                            label = { Text(stringResource(R.string.api_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                            Button(
                                enabled = !state.busy &&
                                    (state.baseUrl.isNotBlank() || state.type == ProviderType.OPENROUTER) &&
                                    state.model.isNotBlank() &&
                                    state.apiKey.isNotBlank(),
                                onClick = viewModel::saveProvider,
                            ) { Text("保存 Provider") }
                            OutlinedButton(onClick = viewModel::skipProvider, enabled = !state.busy) { Text("跳过并保持 fail closed") }
                        }
                    }
                }
                OnboardingStep.SCHEDULE -> {
                    item { Text("安排每日生成", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("设置每天开始完整报告生成的时间；文章池还会按后台间隔增量更新。") }
                    item {
                        val valid = isValidScheduleTime(state.schedule)
                        OutlinedTextField(
                            state.schedule,
                            { value -> viewModel.update { it.copy(schedule = value) } },
                            label = { Text(stringResource(R.string.schedule_time)) },
                            isError = !valid,
                            supportingText = { if (!valid) Text("请输入 00:00–23:59 的 HH:mm 时间") },
                        )
                    }
                    if (state.providerSkipped) item { Text("Provider 已跳过：首次完整生成会 fail closed，直到在设置中完成配置。", color = MaterialTheme.colorScheme.error) }
                    item { Button(onClick = viewModel::saveSchedule, enabled = !state.busy && isValidScheduleTime(state.schedule)) { Text("保存计划") } }
                }
                OnboardingStep.COMPLETE -> {
                    item {
                        EmptyState(
                            title = "设置完成",
                            message = "DailyNews 已安排在每天 ${state.schedule} 生成。你可以先进入应用，也可以立即运行一次完整流程。",
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                            Button(onClick = { viewModel.finish(startRun = true) }) { Text("进入并立即生成") }
                            OutlinedButton(onClick = { viewModel.finish(startRun = false) }) { Text("先进入应用") }
                        }
                    }
                }
            }
            state.message?.let { item { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}
