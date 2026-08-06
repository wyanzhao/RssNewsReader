package com.dailynews.app.ui.periodic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.ReadingColumn
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.data.db.PeriodicReportEntity
import com.dailynews.data.repo.PeriodicReportRepository
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PeriodicDigestUiState(
    /** null = 首次发射之前，与其它屏一致地区分「加载中」与「没有」。 */
    val report: PeriodicReportEntity? = null,
    val loaded: Boolean = false,
)

class PeriodicDigestViewModel(
    repository: PeriodicReportRepository,
    periodKey: String,
) : ViewModel() {
    val state: StateFlow<PeriodicDigestUiState> = repository.observe(periodKey)
        .map { PeriodicDigestUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodicDigestUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodicDigestScreen(
    viewModel: PeriodicDigestViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val report = state.report
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(report?.periodKey.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // 分享 payload 逐字节等于 periodic_reports.markdown，
                    // 与 Top N 分享路径完全隔离，两条不得互相复用。
                    if (report?.status == "SUCCESS") {
                        TextButton(onClick = { shareText(context, report.markdown) }) { Text(stringResource(R.string.share)) }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when {
            !state.loaded -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            report == null -> Box(modifier.padding(DailyNewsSpacing.roomy), contentAlignment = Alignment.TopCenter) {
                EmptyState(title = stringResource(R.string.story_empty_title), message = "")
            }
            // 失败必须显式展示，绝不用任何拼凑内容顶替。
            report.status != "SUCCESS" -> Box(modifier.padding(DailyNewsSpacing.roomy), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    EmptyState(
                        title = stringResource(R.string.periodic_failed_title),
                        message = report.failureReason.orEmpty(),
                        actionLabel = stringResource(R.string.open_diagnostics),
                        onAction = onOpenDiagnostics,
                    )
                }
            }
            else -> LazyColumn(
                modifier,
                contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "markdown") {
                    ReadingColumn {
                        Text(report.markdown, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
