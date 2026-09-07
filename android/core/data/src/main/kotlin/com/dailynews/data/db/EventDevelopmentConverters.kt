package com.dailynews.data.db

import androidx.room.TypeConverter
import com.dailynews.model.ArtifactJson
import com.dailynews.model.EventDevelopment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class EventDevelopmentConverters {
    @TypeConverter fun encode(value: EventDevelopment?): String? = value?.let { ArtifactJson.compact.encodeToString(it) }
    @TypeConverter fun decode(value: String?): EventDevelopment? = value?.let { ArtifactJson.codec.decodeFromString<EventDevelopment>(it) }
}
