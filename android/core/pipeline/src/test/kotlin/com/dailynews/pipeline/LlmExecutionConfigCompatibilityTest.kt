package com.dailynews.pipeline

import com.dailynews.model.ArtifactJson
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.PipelineConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

class LlmExecutionConfigCompatibilityTest {
    @Test
    fun `legacy operation token caps are ignored and not written back`() {
        val legacy = """
            {
              "llm_execution": {
                "connect_timeout_seconds": 25,
                "read_timeout_seconds": 240,
                "call_timeout_seconds": 480,
                "part1_shortlist_max_tokens": 2048,
                "part1_plan_max_tokens": 12288,
                "part2_batch_max_tokens": 6144
              }
            }
        """.trimIndent()

        val decoded = ArtifactJson.codec.decodeFromString<PipelineConfig>(legacy)
        val encoded = ArtifactJson.compact.encodeToString(decoded)

        assertEquals(LlmExecutionConfig(25, 240, 480), decoded.llmExecution)
        assertFalse(encoded.contains("part1_shortlist_max_tokens"))
        assertFalse(encoded.contains("part1_plan_max_tokens"))
        assertFalse(encoded.contains("part2_batch_max_tokens"))
    }
}
