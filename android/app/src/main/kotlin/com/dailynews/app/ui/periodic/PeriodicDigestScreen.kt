package com.dailynews.app.ui.periodic

import androidx.compose.foundation.clickable
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
    /** null = before the first emission; distinguishes "loading" from "nothing", consistent with the other screens. */
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
    /** Entries in the weekly report also open in in-app reading, consistent with the daily report. */
    onOpenArticle: (String) -> Unit = {},
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
                    // The share payload is byte-for-byte equal to periodic_reports.markdown,
                    // fully isolated from the Top N share path; the two must not reuse each other.
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
            // Failures must be shown explicitly; never replaced with any cobbled-together content.
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
                val parsed = parseDigestMarkdown(report.markdown)
                if (parsed.title.isNotBlank()) {
                    item(key = "digest-title") {
                        ReadingColumn { Text(parsed.title, style = MaterialTheme.typography.headlineSmall) }
                    }
                }
                parsed.sections.forEachIndexed { index, section ->
                    item(key = "digest-section-$index") {
                        ReadingColumn {
                            Column(verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                                Text(section.heading, style = MaterialTheme.typography.titleLarge)
                                if (section.body.isNotBlank()) {
                                    Text(section.body, style = MaterialTheme.typography.bodyLarge)
                                }
                                section.links.forEach { link ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenArticle(link.url) }
                                            .padding(vertical = DailyNewsSpacing.compact),
                                    ) {
                                        Text(link.title, style = MaterialTheme.typography.bodyMedium)
                                        if (link.meta.isNotBlank()) {
                                            Text(
                                                link.meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Lines the parser does not recognize are kept verbatim: the worst case is falling back to today's appearance; content is never lost.
                if (parsed.trailing.isNotBlank()) {
                    item(key = "digest-trailing") {
                        ReadingColumn { Text(parsed.trailing, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
