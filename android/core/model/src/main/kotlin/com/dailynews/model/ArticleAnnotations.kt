package com.dailynews.model

data class ArticleAnnotations(val note: String, val tags: List<String>) {
    fun validated(): ArticleAnnotations {
        require(note.length <= 10_000) { "笔记最多 10000 字" }
        val cleaned = tags.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }
        require(cleaned.size <= 10 && cleaned.all { it.length <= 40 }) { "最多 10 个标签，每个最多 40 字" }
        return copy(tags = cleaned)
    }
}
