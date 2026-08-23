package com.dailynews.app.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.ReadingColumn
import com.dailynews.app.ui.theme.DailyNewsSpacing
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(
    viewModel: StoryViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.story_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        StoryContent(state, Modifier.fillMaxSize().padding(padding), onOpen)
    }
}

/** Stateless content body, invoked directly by component-level screenshot and semantics tests. */
@Composable
fun StoryContent(
    state: StoryUiState,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit = {},
    now: Instant = Instant.now(),
) {
    val days = state.days
    when {
        days == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        days.isEmpty() -> Box(modifier.padding(DailyNewsSpacing.roomy), contentAlignment = Alignment.TopCenter) {
            EmptyState(
                title = stringResource(R.string.story_empty_title),
                message = stringResource(R.string.story_empty_message),
            )
        }
        else -> LazyColumn(
            modifier,
            contentPadding = PaddingValues(horizontal = DailyNewsSpacing.roomy, vertical = DailyNewsSpacing.compact),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "story-header") {
                ReadingColumn(Modifier.padding(bottom = DailyNewsSpacing.regular)) {
                    Text(state.headline, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.story_span, days.size, state.totalReports),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.reportDate}") {
                    Box(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            day.reportDate,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = DailyNewsSpacing.readingMaxWidth)
                                .padding(vertical = DailyNewsSpacing.compact),
                        )
                    }
                }
                items(day.items, key = { "${day.reportDate}-${it.position}" }) { item ->
                    ReadingColumn(Modifier.padding(bottom = DailyNewsSpacing.regular)) {
                        ArticleCard(
                            article = ArticleCardModel(
                                link = item.link,
                                title = item.title,
                                source = item.source,
                                pubDateUtc = item.pubDateUtc,
                                pubDateIso = item.pubDateIso,
                                summaryZh = item.summaryZh,
                            ),
                            saved = false,
                            read = false,
                            onOpen = { onOpen(item.link) },
                            onToggleFavorite = {},
                            onShare = {},
                            onOpenRelated = onOpen,
                            now = now,
                        )
                    }
                }
            }
        }
    }
}
