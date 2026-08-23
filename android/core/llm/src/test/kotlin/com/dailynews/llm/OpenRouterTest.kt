package com.dailynews.llm

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class OpenRouterTest {
    @Test
    fun `compat config pointing at openrouter becomes the openrouter type`() {
        val migrated = ProviderConfig(
            "default",
            ProviderType.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1/chat/completions",
            "provider-default",
        ).canonicalize()

        assertEquals(ProviderType.OPENROUTER, migrated.type)
        assertEquals(OpenRouterDefaults.ROUTING, migrated.routing)
        assertTrue(migrated.supportsJsonMode)
    }

    @Test
    fun `explicit openrouter routing is kept during migration`() {
        val routing = ProviderRouting(listOf("openai/gpt-4o-mini"), ProviderSort.PRICE, requireParameters = false)
        val migrated = ProviderConfig(
            "or",
            ProviderType.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1",
            "alias",
            routing = routing,
        ).canonicalize()

        assertEquals(ProviderType.OPENROUTER, migrated.type)
        assertEquals(routing, migrated.routing)
    }

    @Test
    fun `openai and anthropic configs drop leftover routing`() {
        val leftover = ProviderRouting(sort = ProviderSort.THROUGHPUT, requireParameters = true)
        val openai = ProviderConfig("o", ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1", "a", routing = leftover)
            .canonicalize()
        val anthropic = ProviderConfig("a", ProviderType.ANTHROPIC, "https://api.anthropic.com", "a", routing = leftover)
            .canonicalize()

        assertTrue(openai.routing.isDefault)
        assertTrue(anthropic.routing.isDefault)
        assertEquals(ProviderType.OPENAI_COMPAT, openai.type)
    }

    @Test
    fun `transport routing is type gated`() {
        val configured = ProviderRouting(listOf("backup"), ProviderSort.LATENCY, requireParameters = true)
        assertTrue(configured.forTransport(ProviderType.OPENAI_COMPAT).isDefault)
        assertTrue(configured.forTransport(ProviderType.ANTHROPIC).isDefault)
        assertEquals(configured, configured.forTransport(ProviderType.OPENROUTER))
        assertEquals(OpenRouterDefaults.ROUTING, ProviderRouting().forTransport(ProviderType.OPENROUTER))
    }

    @Test
    fun `switching type replaces official default urls but keeps proxies`() {
        assertEquals(
            OpenRouterDefaults.BASE_URL,
            ProviderType.OPENROUTER.adjustedBaseUrl(ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            ProviderType.OPENAI_COMPAT.adjustedBaseUrl(ProviderType.OPENROUTER, OpenRouterDefaults.BASE_URL),
        )
        assertEquals(
            "https://proxy.example/v1",
            ProviderType.OPENROUTER.adjustedBaseUrl(ProviderType.OPENAI_COMPAT, "https://proxy.example/v1"),
        )
        assertFalse(OpenRouterDefaults.looksLikeHost("https://api.openai.com/v1"))
        assertTrue(OpenRouterDefaults.looksLikeHost("https://openrouter.ai/api/v1"))
    }
}
