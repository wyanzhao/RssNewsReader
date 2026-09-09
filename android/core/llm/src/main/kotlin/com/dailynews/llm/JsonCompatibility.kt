package com.dailynews.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One physical request: neither repair nor retries may hide a compatibility failure. */
suspend fun testJsonCompatibility(provider: LlmProvider, model: String, effort: ReasoningEffort): String {
    require(model.isNotBlank()) { "请先填写要测试的模型名称" }
    val response = provider.complete(LlmRequest(
        model = model.trim(),
        system = "Return exactly this JSON object, with no extra fields or prose: {\"ok\":true,\"message\":\"连接成功\"}",
        userContent = "Return the requested JSON object now.",
        maxTokens = 4096,
        reasoningEffort = effort,
        responseSchema = StructuredOutputSchema("connection_check", Json.parseToJsonElement(
            """{"type":"object","properties":{"ok":{"type":"boolean"},"message":{"type":"string"}},"required":["ok","message"],"additionalProperties":false}""",
        ) as JsonObject),
    ))
    if (response.wasTruncated()) throw LlmProtocolException("连接已返回，但测试输出被截断；JSON 兼容性未通过（测试上限 4096 token）")
    val value = runCatching { Json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
    if (value?.keys != setOf("ok", "message") || value["ok"] != JsonPrimitive(true) || value["message"] != JsonPrimitive("连接成功")) {
        throw LlmProtocolException("连接已返回，但 JSON 字段或内容校验未通过；未自动修复或重试")
    }
    return "模型响应与 JSON 校验通过（一次请求）；已发送所选推理参数，服务端实际执行力度无法由响应确认。"
}
