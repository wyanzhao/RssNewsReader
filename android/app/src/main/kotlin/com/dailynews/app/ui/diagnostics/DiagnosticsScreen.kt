package com.dailynews.app.ui.diagnostics

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, startupFailure: String?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            viewModel.export { writeZip ->
                context.contentResolver.openOutputStream(it)?.use { output -> writeZip(output) } != null
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.diagnostics_title)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            startupFailure?.let { failure -> item { Text("启动初始化失败：$failure", color = MaterialTheme.colorScheme.error) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.probing, onClick = viewModel::runNetworkDiagnostics) {
                        Text(stringResource(if (state.probing) R.string.diagnosing else R.string.run_network_diagnostics))
                    }
                    OutlinedButton(
                        enabled = state.selectedRunId != null,
                        onClick = { state.selectedRunId?.let { exportLauncher.launch("dailynews-$it.zip") } },
                    ) { Text(stringResource(R.string.export_artifacts)) }
                }
            }
            state.message?.let { item { Text(it) } }
            items(state.probes, key = { "${it.target}-${it.stage}" }) { probe ->
                Text("${if (probe.passed) "✓" else "✗"} ${probe.target}/${probe.stage}: ${probe.detail}", Modifier.padding(vertical = 2.dp))
            }
            items(state.runs, key = { it.runId }) { run ->
                Card(onClick = { viewModel.select(run.runId) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(run.reportDate, fontWeight = FontWeight.Bold)
                        Text("${run.classification} · exit ${run.validatorExitCode} · attempt ${run.attempt}")
                    }
                }
            }
            state.selectedRunId?.let { runId -> item { Text(stringResource(R.string.step_timeline, runId), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) } }
            items(state.logs, key = { it.id }) { log -> Text("${log.level} ${log.step}: ${log.message}", Modifier.padding(vertical = 4.dp)) }
            if (state.llmCalls.isNotEmpty()) item { Text(stringResource(R.string.llm_calls), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
            items(state.llmCalls, key = { it.id }) { call ->
                Text("${call.role} · ${call.provider}/${call.model} · tokens ${call.inputTokens ?: "?"}+${call.outputTokens ?: "?"} · ${call.outcome}")
            }
            state.validation?.let { text -> item { Text("validation.json", style = MaterialTheme.typography.titleLarge); Text(text) } }
            state.budget?.let { text -> item { Text("context_budget.json", style = MaterialTheme.typography.titleLarge); Text(text) } }
        }
    }
}
