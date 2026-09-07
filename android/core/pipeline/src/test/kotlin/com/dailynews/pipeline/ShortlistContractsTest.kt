package com.dailynews.pipeline

import com.dailynews.model.Part1ShortlistPayload
import com.dailynews.model.ShortlistExclusion
import com.dailynews.pipeline.editorial.ShortlistContracts
import org.junit.jupiter.api.Test
import kotlin.test.*

class ShortlistContractsTest {
    private val pool = (1..47).map { "https://example.test/$it" }
    private val valid = Part1ShortlistPayload(pool.take(34), pool.drop(34).map { ShortlistExclusion(it, "广告促销，没有新的行业事件") })
    private fun errors(draft: Part1ShortlistPayload) = ShortlistContracts.errors(draft, pool, 40, 45)
    @Test fun `34 of 47 is accepted only with complete exclusion accounting`() {
        assertTrue(errors(valid).isEmpty())
        assertTrue(errors(valid.copy(excluded = valid.excluded.dropLast(1))).isNotEmpty())
        assertTrue(errors(valid.copy(excluded = emptyList())).isNotEmpty())
    }
    @Test fun `source membership uniqueness and explanation bounds are enforced`() {
        assertTrue(errors(valid.copy(excluded = valid.excluded + valid.excluded.first())).isNotEmpty())
        assertTrue(errors(valid.copy(excluded = valid.excluded + ShortlistExclusion(pool.first(), "重复"))).isNotEmpty())
        for (reason in listOf("", "x".repeat(201), "访问 https://malicious.test")) {
            assertTrue(errors(valid.copy(excluded = valid.excluded.map { it.copy(reason = reason) })).isNotEmpty())
        }
        assertTrue(errors(valid.copy(links = valid.links + "https://foreign.test/")).isNotEmpty())
    }
    @Test fun `normal size remains compatible and empty reports still cannot proceed`() {
        assertTrue(errors(Part1ShortlistPayload(pool.take(40))).isEmpty())
        assertTrue(errors(Part1ShortlistPayload(emptyList(), pool.map { ShortlistExclusion(it, "重复") })).isNotEmpty())
        assertTrue(errors(Part1ShortlistPayload(pool)).isNotEmpty())
    }
}
