package com.dailynews.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object DailyNewsSpacing {
    val compact = 8.dp
    val regular = 12.dp
    val roomy = 16.dp
    val section = 24.dp
    val readingMaxWidth = 640.dp
}

/**
 * Material 配色方案之外的语义色。
 *
 * 收藏红不能借用 `colorScheme.error`——那个槽位的语义是"出错了"，用它标记收藏
 * 会让 TalkBack 之外的所有视觉线索都在说反话；动态取色下 error 还会跟着壁纸走。
 * 这里固定取值，并由 `FavoriteColorContrastTest` 钉住对比度。
 */
@Immutable
data class DailyNewsExtendedColors(val favorite: Color)

internal val LightExtendedColors = DailyNewsExtendedColors(favorite = Color(0xFFD32F2F))
internal val DarkExtendedColors = DailyNewsExtendedColors(favorite = Color(0xFFE57373))

val LocalDailyNewsColors = staticCompositionLocalOf { LightExtendedColors }

private val DailyNewsTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
)

private val DailyNewsShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun DailyNewsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // 扩展色必须由同一个 `dark` 布尔决定。截图测试显式传 darkTheme，
    // 再调一次 isSystemInDarkTheme() 会让基线在 dark 变体上拍到浅色收藏红。
    CompositionLocalProvider(LocalDailyNewsColors provides if (dark) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = DailyNewsTypography,
            shapes = DailyNewsShapes,
            content = content,
        )
    }
}
