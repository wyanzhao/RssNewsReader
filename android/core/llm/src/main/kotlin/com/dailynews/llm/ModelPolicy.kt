package com.dailynews.llm

/** Official-service policies; generic compatible endpoints retain their own protocol. */
data class ModelPolicy(val efforts: List<ReasoningEffort>) {
    fun requireEffort(effort: ReasoningEffort) {
        require(effort in efforts) { "此模型不支持推理力度 ${effort.displayLabel}，请选择 ${efforts.joinToString { it.displayLabel }}" }
    }
}

fun modelPolicyFor(config: ProviderConfig, model: String): ModelPolicy = config.modelPolicy(model)

fun ProviderConfig.modelPolicy(model: String): ModelPolicy = when {
    type == ProviderType.DEEPSEEK && model.trim().lowercase().startsWith("deepseek-v4-") ->
        ModelPolicy(listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX))
    type == ProviderType.ZAI && model.trim().lowercase() in setOf("glm-5.3", "glm-5.3-flash") ->
        ModelPolicy(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX))
    else -> ModelPolicy(ReasoningEffort.entries)
}
