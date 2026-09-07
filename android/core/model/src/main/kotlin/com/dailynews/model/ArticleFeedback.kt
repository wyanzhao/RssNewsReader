package com.dailynews.model

import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackKind { VALUABLE, LESS_TOPIC, LESS_SOURCE, REPETITIVE, FOLLOW_UP }

/** Explicit user input. The title/source are quoted article data, never instructions. */
@Serializable
data class ArticleFeedback(
    val link: String,
    val title: String,
    val source: String,
    val eventKey: String = "",
    val kind: FeedbackKind,
    val topic: String = "",
)
