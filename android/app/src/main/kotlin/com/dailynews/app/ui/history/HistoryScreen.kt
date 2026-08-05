package com.dailynews.app.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.theme.DailyNewsSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    expanded: Boolean,
    onOpenReport: (String) -> Unit,
    detail: @Composable (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listPane: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(DailyNewsSpacing.regular)) {
            item {
                OutlinedTextField(state.query, viewModel::setQuery, label = { Text(stringResource(R.string.search_reports)) }, modifier = Modifier.fillMaxWidth())
            }
            items(state.reports, key = { it.reportDate }) { report ->
                Card(
                    onClick = {
                        if (expanded) {
                            viewModel.select(report.reportDate)
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, report.reportDate) }
                        } else onOpenReport(report.reportDate)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        ) {
                            Text(report.reportDate, fontWeight = FontWeight.Bold)
                            StatusBadge(report.status)
                        }
                        Text("${report.articleCount} 篇", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                        listOfNotNull(report.previewTitle1, report.previewTitle2, report.previewTitle3).forEach { title ->
                            Text("• $title", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        report.failureReason?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.history_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        if (expanded) {
            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                value = navigator.scaffoldValue,
                modifier = Modifier.fillMaxSize().padding(padding),
                listPane = { AnimatedPane { listPane(Modifier.fillMaxSize()) } },
                detailPane = {
                    AnimatedPane {
                        Box(Modifier.fillMaxSize()) {
                            val date = state.selectedDate
                            if (date != null) detail(date)
                        }
                    }
                },
            )
        } else listPane(Modifier.fillMaxSize().padding(padding))
    }
}
