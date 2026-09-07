package com.dailynews.model

import kotlinx.serialization.Serializable

@Serializable
data class ReadingPreferences(val fontSizeSp: Int = 18, val lineHeightPercent: Int = 150) {
    fun normalized() = copy(fontSizeSp = fontSizeSp.coerceIn(14, 30), lineHeightPercent = lineHeightPercent.coerceIn(120, 200))
}
