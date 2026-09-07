package com.dailynews.pipeline.text

import java.text.Normalizer
import java.util.Locale

fun readingSearchTerms(query: String): List<String> = Normalizer.normalize(query.take(200), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotBlank).distinct()

fun readingSearchMatches(query: String, fields: List<String>): Boolean {
    if (query.isBlank()) return true
    val normalized = fields.map { Normalizer.normalize(it, Normalizer.Form.NFKC).lowercase(Locale.ROOT) }
    return readingSearchTerms(query).all { term -> normalized.any { term in it } }
}
