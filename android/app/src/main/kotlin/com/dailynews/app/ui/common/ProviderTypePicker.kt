package com.dailynews.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dailynews.app.ui.theme.DailyNewsSpacing
import com.dailynews.llm.ProviderType

@Composable
fun ProviderTypePicker(
    selected: ProviderType,
    onSelect: (ProviderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DailyNewsSpacing.compact),
        ) {
            ProviderType.entries.forEach { type ->
                val buttonModifier = Modifier.weight(1f)
                val contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                if (type == selected) {
                    Button(onClick = { onSelect(type) }, modifier = buttonModifier, contentPadding = contentPadding) {
                        Text(type.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(type) }, modifier = buttonModifier, contentPadding = contentPadding) {
                        Text(type.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(
            providerTypeHint(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun providerTypeHint(type: ProviderType): String = when (type) {
    ProviderType.OPENROUTER ->
        "走 OpenRouter 网关。模型名必须带厂商前缀，例如 anthropic/claude-sonnet-4 或 openai/gpt-4o-mini。默认按吞吐路由，并只落到支持 response_format 的提供商。"
    ProviderType.OPENAI_COMPAT ->
        "OpenAI 官方 API，或任何 OpenAI 兼容端点（DeepSeek、Kimi 等）。请填该服务的 Base URL，不要把 OpenRouter 的路由字段发过去。"
    ProviderType.ANTHROPIC ->
        "Anthropic 官方 Messages API。默认地址一般不用改。"
}
