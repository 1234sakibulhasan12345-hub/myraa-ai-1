package com.example.ai.provider

import android.graphics.Bitmap
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.RoutingPolicy
import com.example.data.model.TargetFramework

enum class ProviderId(
    val wireId: String,
    val displayName: String,
    val shortBadge: String,
    val isFreeProvider: Boolean,
    val envKeyName: String = "",
    val requiresAccountId: Boolean = false,
    val freeTierSummary: String = ""
) {
    GOOGLE_GEMINI(
        wireId = "google_gemini",
        displayName = "Google Gemini",
        shortBadge = "FREE • APP CHECK",
        isFreeProvider = true,
        freeTierSummary = "Spark Free Tier via Firebase AI Logic"
    ),
    GROQ(
        wireId = "groq",
        displayName = "Groq",
        shortBadge = "FREE • LPU",
        isFreeProvider = true,
        envKeyName = "GROQ_API_KEY",
        freeTierSummary = "Free Developer Tier (ultra-fast LPU, daily token cap)"
    ),
    OPENROUTER_FREE(
        wireId = "openrouter_free",
        displayName = "OpenRouter Free Models",
        shortBadge = "FREE :free",
        isFreeProvider = true,
        envKeyName = "OPENROUTER_API_KEY",
        freeTierSummary = "Verified :free endpoints only (auto-discovers live free models)"
    ),
    MISTRAL(
        wireId = "mistral",
        displayName = "Mistral AI",
        shortBadge = "FREE TIER",
        isFreeProvider = true,
        envKeyName = "MISTRAL_API_KEY",
        freeTierSummary = "La Plateforme Free Experiment Tier"
    ),
    COHERE(
        wireId = "cohere",
        displayName = "Cohere",
        shortBadge = "FREE TRIAL",
        isFreeProvider = true,
        envKeyName = "COHERE_API_KEY",
        freeTierSummary = "Free Trial API key via Cohere /v2/chat"
    ),
    CLOUDFLARE_AI(
        wireId = "cloudflare_ai",
        displayName = "Cloudflare Workers AI",
        shortBadge = "10K NEURONS/DAY",
        isFreeProvider = true,
        envKeyName = "CLOUDFLARE_API_TOKEN",
        requiresAccountId = true,
        freeTierSummary = "10,000 Neurons/day Free Allocation (API Token + Account ID)"
    ),
    NVIDIA_NIM(
        wireId = "nvidia_nim",
        displayName = "NVIDIA NIM",
        shortBadge = "FREE CREDITS",
        isFreeProvider = true,
        envKeyName = "NVIDIA_NIM_API_KEY",
        freeTierSummary = "Free Developer Credits on integrate.api.nvidia.com"
    ),
    SAMBANOVA(
        wireId = "sambanova",
        displayName = "SambaNova Cloud",
        shortBadge = "FREE TIER",
        isFreeProvider = true,
        envKeyName = "SAMBANOVA_API_KEY",
        freeTierSummary = "Free Developer RDU Inference Tier"
    ),
    // Legacy Part 02 paid proxy adapters (kept for backward compatibility, excluded from Free Model Hub)
    ANTHROPIC_CLAUDE(
        wireId = "anthropic_claude",
        displayName = "Anthropic Claude",
        shortBadge = "PAID PROXY",
        isFreeProvider = false,
        envKeyName = "ANTHROPIC_API_KEY"
    ),
    OPENAI_GPT(
        wireId = "openai_gpt",
        displayName = "OpenAI GPT",
        shortBadge = "PAID PROXY",
        isFreeProvider = false,
        envKeyName = "OPENAI_API_KEY"
    ),
    DEEPSEEK(
        wireId = "deepseek",
        displayName = "DeepSeek",
        shortBadge = "PAID PROXY",
        isFreeProvider = false,
        envKeyName = "DEEPSEEK_API_KEY"
    ),
    META_LLAMA(
        wireId = "meta_llama",
        displayName = "Meta Llama",
        shortBadge = "PAID PROXY",
        isFreeProvider = false,
        envKeyName = "META_LLAMA_API_KEY"
    );

    companion object {
        val freeProviders: List<ProviderId>
            get() = entries.filter { it.isFreeProvider }

        val externalFreeProviders: List<ProviderId>
            get() = entries.filter { it.isFreeProvider && it != GOOGLE_GEMINI }

        fun fromWire(value: String): ProviderId? =
            entries.firstOrNull { it.wireId == value }
    }
}

enum class ModelCapability(val label: String) {
    REASONING("Deep Reasoning"),
    CODING("Code Architecture"),
    MULTILINGUAL_BN_EN("Bengali / EN / Banglish"),
    VISION_MULTIMODAL("Vision Input"),
    FAST_CHAT("Fast Streaming"),
    STRUCTURED_JSON("Schema JSON"),
    IMAGE_GEN("Image Studio (Upcoming)")
}

enum class TaskCategory(val wireValue: String, val displayLabel: String) {
    MASTER_PLANNING("MASTER_PLANNING", "Master Project Planning"),
    ARCHITECTURE_DESIGN("ARCHITECTURE_DESIGN", "Architecture Design"),
    CODE_REASONING("CODE_REASONING", "Technical Reasoning"),
    MULTILINGUAL_CHAT("MULTILINGUAL_CHAT", "Multilingual Dialogue"),
    VISION_ANALYSIS("VISION_ANALYSIS", "Multimodal Analysis"),
    QUICK_SUBTASK("QUICK_SUBTASK", "Rapid Subtask");

    companion object {
        fun fromWire(value: String): TaskCategory =
            entries.firstOrNull { it.wireValue == value } ?: MASTER_PLANNING
    }
}

enum class ProviderConnectionStatus(val label: String, val isOperational: Boolean) {
    CONNECTED_LIVE("Live via Firebase AI Logic", true),
    CONNECTED_PROXY("Connected • Verified Live", true),
    MISSING_API_KEY("Missing API Key", false),
    RATE_LIMITED("Rate Limited (429)", false),
    QUOTA_EXPIRED("Quota / Credits Expired", false),
    OFFLINE("Offline / Unreachable", false),
    UNAVAILABLE("Service Unavailable", false),
    PLANNED_PART_02("Not Connected • Backend Proxy & Key Required", false),
    PROBE_ERROR("Invalid Key / Probe Failed", false)
}

data class AiModelDescriptor(
    val modelId: String,
    val providerId: ProviderId,
    val displayName: String,
    val roleSummary: String,
    val capabilities: Set<ModelCapability>,
    val latencyTier: String,
    val costTier: String,
    val isLiveInCurrentBuild: Boolean,
    val requiresBillingOrBackendProxy: Boolean = false,
    val isFreeTier: Boolean = false,
    val freeTierLimitsSummary: String = ""
)

data class AiProviderDescriptor(
    val providerId: ProviderId,
    val displayName: String,
    val status: ProviderConnectionStatus,
    val securityNote: String,
    val models: List<AiModelDescriptor>,
    val isFreeProvider: Boolean = providerId.isFreeProvider,
    val keySource: String = "NONE",
    val maskedKeyHint: String = "",
    val requiresAccountId: Boolean = providerId.requiresAccountId,
    val hasAccountId: Boolean = false
)

data class ModelRoutingDecision(
    val selectedProvider: ProviderId,
    val selectedModel: AiModelDescriptor,
    val fallbackModelId: String,
    val taskCategory: TaskCategory,
    val rationale: String
)

data class BrainGenerationRequest(
    val prompt: String,
    val languagePreference: BrainLanguage,
    val routingPolicy: RoutingPolicy,
    val projectContextSummary: String,
    val recentHistory: List<BrainMessage>,
    val attachedBitmap: Bitmap? = null,
    val inferredTaskCategory: TaskCategory = TaskCategory.MASTER_PLANNING,
    val preferredProviderId: ProviderId? = null,
    val backendServerUrl: String = ""
)

data class StructuredOrchestrationPlan(
    val detectedLanguage: BrainLanguage,
    val detectedGoal: String,
    val intentSummary: String,
    val selectedTaskCategory: TaskCategory,
    val recommendedFramework: TargetFramework,
    val assignedAgents: List<String>,
    val subtasks: List<String>,
    val architectureNotes: String,
    val detectedRisksOrGaps: List<String>
)

data class ProviderGenerationOutput(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val latencyMs: Long,
    val estimatedCostUsdMicros: Long
)

data class ProviderLiveProbeState(
    val providerId: ProviderId,
    val status: ProviderConnectionStatus,
    val statusCode: String,
    val diagnosticMessage: String,
    val lastCheckedEpochMs: Long,
    val keySource: String = "NONE",
    val maskedKeyHint: String = "",
    val requiresAccountId: Boolean = providerId.requiresAccountId,
    val hasAccountId: Boolean = false,
    val discoveredModels: List<String> = emptyList(),
    val freeTier: Boolean = providerId.isFreeProvider,
    val errorCategory: String = ""
)

data class ProviderKeySummary(
    val providerId: ProviderId,
    val displayName: String,
    val keyConfigured: Boolean,
    val keySource: String,
    val maskedKeyHint: String,
    val requiresAccountId: Boolean,
    val hasAccountId: Boolean,
    val statusCode: String
)

class ProviderHttpException(
    val httpCode: Int,
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class ParallelModelResponse(
    val providerId: ProviderId,
    val providerName: String,
    val modelId: String,
    val displayModelName: String,
    val content: String,
    val latencyMs: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsdMicros: Long,
    val errorMessage: String? = null
)

data class ConsensusSynthesisResult(
    val consensusText: String,
    val synthesizedByModelId: String,
    val comparedResponses: List<ParallelModelResponse>,
    val totalLatencyMs: Long,
    val totalEstimatedCostUsdMicros: Long
)

sealed interface BrainStreamEvent {
    data class PlanReady(
        val plan: StructuredOrchestrationPlan,
        val routing: ModelRoutingDecision
    ) : BrainStreamEvent

    data class TextChunk(val accumulatedText: String) : BrainStreamEvent

    data class Completed(
        val fullText: String,
        val latencyMs: Long,
        val promptTokens: Long = 0L,
        val completionTokens: Long = 0L,
        val estimatedCostUsdMicros: Long = 0L
    ) : BrainStreamEvent
}
