package com.example.ai.provider

import java.util.Locale

data class ModelPricingMicros(
    val inputMicrosPerMillionTokens: Long,
    val outputMicrosPerMillionTokens: Long
)

object TokenAndCostTracker {

    private val zeroCostFreeModels: Set<String> = setOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
        "openrouter/free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "qwen/qwen3-coder:free",
        "deepseek/deepseek-r1-0528:free",
        "mistral-small-latest",
        "codestral-latest",
        "ministral-8b-latest",
        "open-mistral-nemo",
        "command-a-03-2025",
        "command-r-plus-08-2024",
        "command-r7b-12-2024",
        "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        "@cf/meta/llama-3.1-8b-instruct",
        "@cf/meta/llama-4-scout-17b-16e-instruct",
        "meta/llama-3.3-70b-instruct",
        "deepseek-ai/deepseek-r1",
        "qwen/qwen2.5-coder-32b-instruct",
        "Meta-Llama-3.3-70B-Instruct",
        "DeepSeek-R1",
        "Qwen3-32B"
    )

    private val pricingByModel: Map<String, ModelPricingMicros> = mapOf(
        "gemini-3.8-flash" to ModelPricingMicros(150_000L, 600_000L),
        "gemini-3.5-flash-lite" to ModelPricingMicros(75_000L, 300_000L),
        "gemini-4-argon" to ModelPricingMicros(1_250_000L, 5_000_000L),
        "gpt-4.1" to ModelPricingMicros(2_000_000L, 8_000_000L),
        "gpt-4.1-mini" to ModelPricingMicros(400_000L, 1_600_000L),
        "o4-mini" to ModelPricingMicros(1_100_000L, 4_400_000L),
        "claude-sonnet-4-20250514" to ModelPricingMicros(3_000_000L, 15_000_000L),
        "claude-3-5-haiku-latest" to ModelPricingMicros(800_000L, 4_000_000L),
        "deepseek-chat" to ModelPricingMicros(270_000L, 1_100_000L),
        "deepseek-reasoner" to ModelPricingMicros(550_000L, 2_190_000L),
        "Llama-3.3-70B-Instruct" to ModelPricingMicros(180_000L, 350_000L),
        "Llama-4-Scout-17B-16E-Instruct" to ModelPricingMicros(150_000L, 300_000L)
    )

    private val defaultPricing = ModelPricingMicros(300_000L, 1_200_000L)

    fun estimateTokens(text: String): Long {
        if (text.isEmpty()) return 0L
        var bengaliChars = 0
        for (ch in text) {
            if (ch in '\u0980'..'\u09FF') bengaliChars++
        }
        val otherChars = (text.length - bengaliChars).coerceAtLeast(0)
        // Bengali UTF-8 script averages ~2 chars per token; English/code averages ~4 chars per token
        val approx = (bengaliChars / 2.0) + (otherChars / 4.0)
        return approx.toLong().coerceAtLeast(1L)
    }

    fun computeCostUsdMicros(
        modelId: String,
        promptTokens: Long,
        completionTokens: Long,
        isFreeTierOverride: Boolean = false
    ): Long {
        if (isFreeTierOverride || modelId in zeroCostFreeModels || modelId.endsWith(":free")) {
            return 0L
        }
        val pricing = pricingByModel[modelId] ?: defaultPricing
        val inputMicros = (promptTokens.coerceAtLeast(0L) * pricing.inputMicrosPerMillionTokens) / 1_000_000L
        val outputMicros = (completionTokens.coerceAtLeast(0L) * pricing.outputMicrosPerMillionTokens) / 1_000_000L
        val total = inputMicros + outputMicros
        return if (total == 0L && (promptTokens > 0L || completionTokens > 0L)) 1L else total
    }

    fun formatUsdMicros(usdMicros: Long): String {
        val dollars = usdMicros.coerceAtLeast(0L) / 1_000_000.0
        return String.format(Locale.US, "$%.5f", dollars)
    }
}
