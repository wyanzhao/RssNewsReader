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
 * Favorite-red is the only hardcoded color in the app that bypasses the Material
 * color scheme; nothing upstream guarantees it is readable. WCAG's floor for
 * non-text UI (icons) is 3:1.
 *
 * Asserted only against the static light/dark schemes: Roborazzi baselines also
 * run on static schemes, and Material You surfaces stay low-chroma neutrals, so
 * dynamic-color drift is bounded.
 */
class FavoriteColorContrastTest {
    @Test
    fun favoriteIconMeetsNonTextContrastOnBothSurfaces() {
        val cases = listOf(
            Triple("light", LightExtendedColors.favorite, lightColorScheme().surface),
            Triple("dark", DarkExtendedColors.favorite, darkColorScheme().surface),
            // Cards are in the surfaceContainer family; cover them too so we do not only pass on a plain background.
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
        // Regression guard: this token exists solely to mean "not primary, not error".
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
