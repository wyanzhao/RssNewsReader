package com.dailynews.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 收藏红是应用里唯一一个绕开 Material 配色方案的硬编码颜色，没有任何上游保证它可读。
 * WCAG 对非文本 UI 元素（图标）的下限是 3:1。
 *
 * 只对静态 light/dark scheme 断言：Roborazzi 基线也跑在静态 scheme 上，而 Material You
 * 的 surface 恒为低彩度中性色，动态取色下的偏移有界。
 */
class FavoriteColorContrastTest {
    @Test
    fun favoriteIconMeetsNonTextContrastOnBothSurfaces() {
        val cases = listOf(
            Triple("light", LightExtendedColors.favorite, lightColorScheme().surface),
            Triple("dark", DarkExtendedColors.favorite, darkColorScheme().surface),
            // 卡片是 surfaceContainer 系，一并覆盖，避免只在纯背景上达标。
            Triple("light/surfaceVariant", LightExtendedColors.favorite, lightColorScheme().surfaceVariant),
            Triple("dark/surfaceVariant", DarkExtendedColors.favorite, darkColorScheme().surfaceVariant),
        )
        cases.forEach { (name, foreground, background) ->
            val ratio = contrastRatio(foreground, background)
            assertTrue(ratio >= MIN_NON_TEXT_CONTRAST, "$name 收藏红对比度 ${"%.2f".format(ratio)} 低于 $MIN_NON_TEXT_CONTRAST:1")
        }
    }

    @Test
    fun favoriteReadsAsRedNotAsThemePrimary() {
        // 回归守卫：这个 token 存在的全部意义就是"不是 primary、不是 error"。
        listOf(LightExtendedColors.favorite, DarkExtendedColors.favorite).forEach { color ->
            assertTrue(color.red > color.green && color.red > color.blue, "收藏色不再是红色系：$color")
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val luminanceA = relativeLuminance(a)
        val luminanceB = relativeLuminance(b)
        return (max(luminanceA, luminanceB) + 0.05) / (min(luminanceA, luminanceB) + 0.05)
    }

    /** WCAG 2.x relative luminance. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val MIN_NON_TEXT_CONTRAST = 3.0
    }
}
