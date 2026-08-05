package com.dailynews.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object JsonExtractor {
    private val fenced = Regex("^\\s*```(?:json)?\\s*([\\s\\S]*?)\\s*```\\s*$", RegexOption.IGNORE_CASE)

    fun extractObject(text: String, json: Json = Json): JsonObject {
        val candidate = fenced.matchEntire(text)?.groupValues?.get(1) ?: text
        val start = candidate.indexOf('{')
        if (start < 0) throw LlmProtocolException("LLM output contains no JSON object")

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until candidate.length) {
            val char = candidate[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        val balanced = candidate.substring(start, index + 1)
                        return try {
                            json.parseToJsonElement(balanced).jsonObject
                        } catch (error: Exception) {
                            throw LlmProtocolException("balanced LLM JSON is invalid", error)
                        }
                    }
                }
            }
        }
        throw LlmProtocolException("LLM JSON object is truncated")
    }
}
