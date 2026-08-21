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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailynews.app.R
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.app.ui.theme.LocalDailyNewsColors
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
    /**
     * 这条线索被报道过的天数，>= 2 时才有意义。
     *
     * 跨天线索是这个 app 最有差异化的功能，此前唯一入口是长按菜单——读者没有任何
     * 方式知道第 7 条是某个事件的第四天，要发现它得把 30 张卡片挨个长按一遍。
     */
    val storyDays: Int? = null,
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
    blankSummaryText: String = "暂无中文摘要",
    extraMenuItem: @Composable (() -> Unit)? = null,
    onOpenStory: (() -> Unit)? = null,
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
            //
            // customActions 是必须的：分享、复制链接、线索历史全部只挂在 onLongClick 的
            // 菜单上，而 TalkBack 用户没有"长按"这个手势可以发现它们——合并语义让他们
            // 听得到内容，却没有任何被播报的路径去用这四个动作。
            .semantics(mergeDescendants = true) {
                customActions = buildList {
                    add(CustomAccessibilityAction("分享文章") { onShare(); true })
                    add(
                        CustomAccessibilityAction(if (saved) "取消收藏" else "收藏") {
                            onToggleFavorite()
                            true
                        },
                    )
                    onOpenStory?.let { open -> add(CustomAccessibilityAction("查看线索历史") { open(); true }) }
                }
            },
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
                        // 形状 + 颜色双通道区分收藏态。只切 tint 时两种状态都是同一个
                        // 描边心形，扫一眼列表分不出收没收；实心轮廓在缩略尺寸下也立得住。
                        Icon(
                            painterResource(if (saved) R.drawable.ic_favorite_filled else R.drawable.ic_favorite),
                            contentDescription = stringResource(if (saved) R.string.remove_favorite else R.string.favorite),
                            tint = if (saved) LocalDailyNewsColors.current.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    article.summaryZh.ifBlank {
                        if (generatingSummary) "正在生成中文摘要…" else blankSummaryText
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                val storyDays = article.storyDays?.takeIf { it >= 2 }
                if (article.relatedLinks.isNotEmpty() || storyDays != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
                        if (storyDays != null && onOpenStory != null) {
                            AssistChip(
                                onClick = onOpenStory,
                                label = { Text("线索 · $storyDays 天") },
                            )
                        }
                        article.relatedLinks.take(3).forEach { link ->
                            AssistChip(onClick = { onOpenRelated(link) }, label = { Text(linkDomain(link)) })
                        }
                        // 超出 3 条时给出总数，否则 6 个来源的聚类看起来只有 3 个。
                        if (article.relatedLinks.size > 3) {
                            AssistChip(onClick = {}, enabled = false, label = { Text("+${article.relatedLinks.size - 3}") })
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                extraMenuItem?.invoke()
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
