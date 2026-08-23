package com.dailynews.llm

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class StructuredLlm(
    private val provider: LlmProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxTransientRetries: Int = 2,
    private val retryDelay: suspend (Long) -> Unit = { millis -> delay(millis) },
    /**
     * Base for the first backoff delay; doubles with each retry.
     *
     * It used to be 250ms — against a 429 that equals slamming into the rate-limit
     * window again immediately, burning all three attempts in under a second. When the
     * server supplies `Retry-After` it takes precedence; this curve is only the
     * fallback for when no instruction is given.
     */
    private val baseRetryDelayMillis: Long = 1_000L,
) {
    suspend fun completeObject(
        request: LlmRequest,
        beforeAttempt: () -> Unit = {},
        onAttempt: suspend (index: Int, response: LlmResponse?, outcome: String) -> Unit = { _, _, _ -> },
    ): Pair<JsonObject, LlmResponse> {
        var physicalIndex = 0

        suspend fun invokeWithRetries(callRequest: LlmRequest, phase: String): Pair<Int, LlmResponse> {
            var retry = 0
            var activeRequest = callRequest
            val attemptedFallbacks = mutableSetOf<StructuredMode>()
            while (true) {
                val index = physicalIndex++
                beforeAttempt()
                val response = try {
                    provider.complete(activeRequest)
                } catch (unsupported: StructuredOutputUnsupportedException) {
                    onAttempt(index, null, "${phase}structured_${unsupported.mode.name.lowercase()}_unsupported")
                    if (!attemptedFallbacks.add(unsupported.fallbackMode)) throw unsupported
                    activeRequest = activeRequest.copy(structuredMode = unsupported.fallbackMode)
                    continue
                } catch (error: Exception) {
                    onAttempt(index, null, "${phase}failed: ${error.message}")
                    val transport = error as? LlmTransportException
                    if (transport?.retryable == true && retry < maxTransientRetries) {
                        retryDelay(transport.retryAfterMillis ?: (baseRetryDelayMillis shl retry))
                        retry += 1
                        continue
                    }
                    throw error
                }
                if (response.wasTruncated()) {
                    onAttempt(index, response, "${phase}truncated")
                    throw LlmProtocolException(
                        "LLM output was truncated (${response.stopReason}); deterministic retry with the same token cap was suppressed",
                    )
                }
                return index to response
            }
        }

        val (firstIndex, first) = invokeWithRetries(request, "")
        return try {
            val objectResult = JsonExtractor.extractObject(first.text, json)
            onAttempt(firstIndex, first, "success")
            objectResult to first
        } catch (firstError: LlmProtocolException) {
            onAttempt(firstIndex, first, "invalid_json")
            val (repairIndex, repair) = invokeWithRetries(
                request.copy(
                    system = "You repair malformed JSON. Return exactly one valid JSON object and no prose.",
                    userContent = "Repair this output without inventing new facts:\n\n${first.text}",
                    temperature = null,
                    assistantPrefill = null,
                ),
                "repair_",
            )
            try {
                val objectResult = JsonExtractor.extractObject(repair.text, json)
                onAttempt(repairIndex, repair, "repair_success")
                objectResult to repair
            } catch (repairError: LlmProtocolException) {
                onAttempt(repairIndex, repair, "repair_invalid_json")
                throw LlmProtocolException("LLM output stayed invalid after one JSON repair", repairError)
            }
        }
    }
}
