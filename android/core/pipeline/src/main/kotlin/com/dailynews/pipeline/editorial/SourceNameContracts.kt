package com.dailynews.pipeline.editorial

/** A narrow spelling guard for two-word source names, not entity recognition or semantic fact checking. */
object SourceNameContracts {
    // ASCII boundaries allow names embedded directly in Chinese prose. Match overlapping pairs
    // in longer names without crossing source-field/paragraph boundaries.
    private val pair = Regex("(?<![A-Za-z0-9-])(?=([A-Z][A-Za-z0-9-]{2,39})[ \\t]+([A-Z][A-Za-z0-9-]{2,39})(?![A-Za-z0-9-]))")
    private fun pairs(text: String) = pair.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
    private fun key(value: Pair<String, String>) = value.first.lowercase() to value.second.lowercase()
    private fun oneSubstitution(a: String, b: String) = a.length >= 4 && a.length == b.length && a.indices.count { a[it] != b[it] } == 1

    fun errors(summary: String, sourceMaterial: List<String>, label: String): List<String> {
        val names = sourceMaterial.flatMap(::pairs).associateBy(::key)
        return pairs(summary).distinctBy(::key).mapNotNull { written ->
            val word = key(written)
            if (word in names) return@mapNotNull null
            val near = names.filterKeys { source ->
                (word.second == source.second && oneSubstitution(word.first, source.first)) ||
                    (word.first == source.first && oneSubstitution(word.second, source.second))
            }.values.singleOrNull() ?: return@mapNotNull null
            "$label source-name mismatch: '${written.first} ${written.second}' is absent; provided spelling is '${near.first} ${near.second}'. Copy source spelling or use a faithful Chinese description."
        }
    }
}
