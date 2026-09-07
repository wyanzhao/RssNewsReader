package com.dailynews.pipeline

import com.dailynews.pipeline.flow.stableRecoveryInput
import com.dailynews.pipeline.flow.recoveryHash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EditorialCheckpointTest {
    @Test fun identityAndJsonKeyOrderAreIgnoredButDateArticleAndArrayOrderAreBound() {
        val first = """{"meta":{"date":"2026-09-07","run_id":"old","generated_at_utc":"then","report_path":"/old"},"articles":[{"title":"A","text":"body"},{"title":"B"}]}"""
        val retry = """{"articles":[{"text":"body","title":"A"},{"title":"B"}],"meta":{"date":"2026-09-07","run_id":"new","generated_at_utc":"now","report_path":"/new"}}"""
        fun hash(value: String) = recoveryHash(stableRecoveryInput(value))
        assertEquals(hash(first), hash(retry))
        assertNotEquals(hash(first), hash(retry.replace("2026-09-07", "2026-09-08")))
        assertNotEquals(hash(first), hash(retry.replace("body", "changed")))
        assertNotEquals(hash(first), hash(retry.replace("[{\"text\":\"body\",\"title\":\"A\"},{\"title\":\"B\"}]", "[{\"title\":\"B\"},{\"text\":\"body\",\"title\":\"A\"}]")))
    }
}
