package com.dailynews.llm

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class JsonExtractorTest {
    @Test
    fun `extracts fenced balanced object around prose`() {
        val result = JsonExtractor.extractObject("before ```json\n{\"value\":\"} inside\",\"n\":2}\n``` after")
        assertEquals("} inside", result["value"]?.toString()?.trim('"'))
    }

    @Test
    fun `rejects truncated object`() {
        assertFailsWith<LlmProtocolException> { JsonExtractor.extractObject("{\"value\": 1") }
    }
}
