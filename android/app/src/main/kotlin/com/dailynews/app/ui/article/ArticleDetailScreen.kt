package com.dailynews.app.ui.article

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.relativeArticleTime
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.app.ui.theme.LocalDailyNewsColors

/**
 * In-app article reading.
 *
 * It exists for one reason: `articleText` has already been fetched, paid for, and
 * persisted, yet no UI consumed it. With this screen, offline you hold 30 Chinese
 * summaries **plus** each article's body excerpt, instead of 30 links that open a
 * browser offline-error page.
 *
 * Deliberately not done: no HTML rendering, no image loading, no network. It reads
 * only data already on device, so it must open in airplane mode — that is the entire
 * point. For the original page, use "Open in browser".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    viewModel: ArticleDetailViewModel,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.article?.source.orEmpty()) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = {
                    state.article?.let { article ->
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                painterResource(if (article.favoritedAtUtc != null) R.drawable.ic_favorite_filled else R.drawable.ic_favorite),
                                contentDescription = stringResource(
                                    if (article.favoritedAtUtc != null) R.string.remove_favorite else R.string.favorite,
                                ),
                                tint = if (article.favoritedAtUtc != null) {
                                    LocalDailyNewsColors.current.favorite
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val article = state.article
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            article == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("这篇文章已不在本地（可能已超出保留期）", style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(DailyNewsSpacing.roomy),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
            ) {
                item("header") {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
                    ) {
                        Text(article.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOf(article.source, relativeArticleTime(article.pubDateIso, article.pubDateUtc))
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (article.summaryZh.isNotBlank()) {
                    item("summary") {
                        Text(
                            article.summaryZh,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        )
                    }
                }
                item("divider") { HorizontalDivider(Modifier.widthIn(max = DailyNewsSpacing.readingMaxWidth)) }
                item("body") {
                    Text(
                        // The body is an **excerpt**, not the full article (truncated by word
                        // count at fetch time), so say so here rather than letting readers think
                        // the article is this short.
                        article.articleText.ifBlank { "本地没有正文摘录。点下方在浏览器打开原文。" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                    )
                }
                if (article.articleText.isNotBlank()) {
                    item("excerpt-note") {
                        Text(
                            "以上为抓取时保存的正文摘录，非全文。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        )
                    }
                }
                item("actions") {
                    Row(
                        Modifier.fillMaxWidth().widthIn(max = DailyNewsSpacing.readingMaxWidth),
                        horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
                    ) {
                        Button(onClick = { onOpenInBrowser(article.link) }) { Text("在浏览器打开") }
                        OutlinedButton(
                            onClick = { onShare("${article.title}\n${article.link}\n${article.summaryZh}") },
                        ) { Text("分享") }
                    }
                }
            }
        }
    }
}
