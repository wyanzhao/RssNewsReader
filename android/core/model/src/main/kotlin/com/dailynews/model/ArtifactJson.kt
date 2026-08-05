package com.dailynews.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi

/** One JSON policy for every artifact boundary. Unknown additive fields stay compatible. */
@OptIn(ExperimentalSerializationApi::class)
object ArtifactJson {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    val compact: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }
}
