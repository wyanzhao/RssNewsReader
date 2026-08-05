package com.dailynews.data.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PipelineConfig
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
}
