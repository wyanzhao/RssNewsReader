package com.dailynews.app.ui.favorites

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import com.dailynews.model.ArtifactJson
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailynews.app.R
import com.dailynews.app.ui.common.ArticleCard
import com.dailynews.app.ui.common.ArticleCardModel
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.shareText
import com.dailynews.app.ui.theme.DailyNewsSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(viewModel: FavoritesViewModel, onOpenArticle: ((String) -> Unit)? = null) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.favorites_title)) }) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(DailyNewsSpacing.roomy),
            verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.regular),
        ) {
            item("search") { OutlinedTextField(query, { viewModel.query.value = it.take(200) }, label = { Text("搜索标题、摘要、标签、笔记") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (favorites.isEmpty()) {
                item { EmptyState(if (query.isBlank()) "还没有收藏" else "没有匹配结果", if (query.isBlank()) "在报告文章卡片上点收藏，稍后可以从这里继续阅读。" else "尝试其他中英文关键词，多个词用空格分隔。") }
            }
            items(favorites, key = { it.link }) { item ->
                ArticleCard(
                    article = ArticleCardModel(
                        link = item.link,
                        title = item.title,
                        source = item.source,
                        pubDateUtc = item.pubDateUtc,
                        pubDateIso = item.pubDateIso,
                        summaryZh = item.summaryZh,
                    ),
                    saved = true,
                    read = item.readAtUtc != null,
                    onOpen = {
                        viewModel.markRead(item.link)
                        onOpenArticle?.invoke(item.link)
                            ?: CustomTabsIntent.Builder().build().launchUrl(context, item.link.toUri())
                    },
                    onToggleFavorite = {
                        viewModel.remove(item.link)
                        scope.launch {
                            if (snackbars.showSnackbar("已取消收藏：${item.title}", "撤销") == SnackbarResult.ActionPerformed) {
                                viewModel.restore(item.link)
                            }
                        }
                    },
                    onShare = { shareText(context, "${item.title}\n${item.link}\n${item.summaryZh}") },
                    onOpenRelated = {},
                )
                val tags = ArtifactJson.codec.decodeFromString<List<String>>(item.tagsJson)
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                if (item.note.isNotBlank()) Text("笔记：${item.note.take(160)}", maxLines = 3, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
    }
}
