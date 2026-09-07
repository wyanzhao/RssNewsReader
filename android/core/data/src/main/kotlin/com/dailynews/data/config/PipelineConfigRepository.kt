package com.dailynews.data.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PipelineConfig
import com.dailynews.model.ArticleFeedback
import com.dailynews.model.FeedbackKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.pipelineDataStore by preferencesDataStore(name = "pipeline_config")

class PipelineConfigRepository(private val context: Context) {
    private val configKey = stringPreferencesKey("pipeline_config_json")

    val config: Flow<PipelineConfig> = context.pipelineDataStore.data
        .map { preferences ->
            preferences[configKey]
                ?.let { runCatching { ArtifactJson.codec.decodeFromString<PipelineConfig>(it).normalized() }.getOrNull() }
                ?: PipelineConfig()
        }
        .catch { emit(PipelineConfig()) }

    suspend fun save(config: PipelineConfig) {
        context.pipelineDataStore.edit { it[configKey] = ArtifactJson.compact.encodeToString(config.normalized()) }
    }

    /** Read/modify/write in the DataStore transaction so independent screens cannot lose updates. */
    suspend fun update(transform: (PipelineConfig) -> PipelineConfig): PipelineConfig {
        var result = PipelineConfig()
        context.pipelineDataStore.edit { preferences ->
            val current = preferences[configKey]?.let {
                ArtifactJson.codec.decodeFromString<PipelineConfig>(it)
            } ?: PipelineConfig()
            result = transform(current).normalized()
            preferences[configKey] = ArtifactJson.compact.encodeToString(result)
        }
        return result
    }

    suspend fun recordFeedback(feedback: ArticleFeedback) {
        require(feedback.link.isNotBlank()) { "文章没有有效链接" }
        require(feedback.topic.length <= 80) { "主题最多 80 字" }
        require(feedback.kind != FeedbackKind.LESS_TOPIC || feedback.topic.isNotBlank()) { "请填写希望减少的主题" }
        val bounded = feedback.copy(title = feedback.title.take(300), source = feedback.source.take(120), topic = feedback.topic.trim())
        update { it.copy(articleFeedback = it.articleFeedback.filterNot { item -> item.link == bounded.link } + bounded) }
    }

    suspend fun removeFeedback(link: String) {
        update { it.copy(articleFeedback = it.articleFeedback.filterNot { item -> item.link == link }) }
    }
}
