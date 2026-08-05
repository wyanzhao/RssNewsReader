package com.dailynews.app.ui.common

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailynews.app.R
import com.dailynews.app.ui.theme.DailyNewsSpacing
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

data class ArticleCardModel(
    val link: String,
    val title: String,
    val source: String,
    val pubDateUtc: String,
    val pubDateIso: String,
    val summaryZh: String,
    val rank: Int? = null,
    val relatedLinks: List<String> = emptyList(),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCard(
    article: ArticleCardModel,
    saved: Boolean,
    read: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onOpenRelated: (String) -> Unit,
    generatingSummary: Boolean = false,
    now: Instant = Instant.now(),
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Card(
        Modifier
            .fillMaxWidth()
            .alpha(if (read) 0.76f else 1f)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
            )
            // Merge the visible title, source/time, summary and related-source text. A custom
            // contentDescription here would replace all of that TalkBack information.
            .semantics(mergeDescendants = true) {},
    ) {
        Box {
            Column(
                Modifier.padding(DailyNewsSpacing.roomy),
                verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                    article.rank?.let { rank ->
                        AssistChip(onClick = {}, enabled = false, label = { Text("#$rank") })
                    }
                    Column(Modifier.weight(1f)) {
                        Text(article.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(article.source, relativeArticleTime(article.pubDateIso, article.pubDateUtc, now)).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleFavorite()
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_favorite),
                            contentDescription = if (saved) "取消收藏" else "收藏",
                            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    article.summaryZh.ifBlank {
                        if (generatingSummary) "正在生成中文摘要…" else "尚未生成中文摘要；展开来源组可重试。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (article.relatedLinks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                        article.relatedLinks.take(3).forEach { link ->
                            AssistChip(onClick = { onOpenRelated(link) }, label = { Text(linkDomain(link)) })
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("分享文章") },
                    onClick = { menuExpanded = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("复制链接") },
                    onClick = {
                        menuExpanded = false
                        scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText(article.title, article.link))) }
                    },
                )
            }
        }
    }
}

internal fun relativeArticleTime(pubDateIso: String, fallback: String, now: Instant = Instant.now()): String {
    val published = parseArticleInstant(pubDateIso) ?: return fallback
    val duration = Duration.between(published, now)
    if (duration.isNegative) return fallback
    return when {
        duration.toMinutes() < 60 -> "${duration.toMinutes().coerceAtLeast(1)} 分钟前"
        duration.toHours() < 24 -> "${duration.toHours()} 小时前"
        duration.toDays() < 7 -> "${duration.toDays()} 天前"
        else -> fallback
    }
}

private fun parseArticleInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrElse {
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

private fun linkDomain(link: String): String = runCatching {
    URI(link).host?.removePrefix("www.").orEmpty()
}.getOrDefault("").ifBlank { "相关来源" }
