package com.dailynews.pipeline.text

fun restoreReadingPosition(savedKey: String, currentKey: String, index: Int, offset: Int, itemCount: Int): Pair<Int, Int> {
    require(itemCount > 0)
    if (savedKey.isBlank() || savedKey != currentKey) return 0 to 0
    if (index !in 0 until itemCount) return index.coerceIn(0, itemCount - 1) to 0
    return index to offset.coerceIn(0, 10_000_000)
}
