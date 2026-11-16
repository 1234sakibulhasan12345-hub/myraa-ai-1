package com.example.ai.provider

import com.example.data.model.BrainLanguage
import com.example.data.model.TargetFramework
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

class BackendProxyAiProvider(
    val providerId: ProviderId,
    private val proxyClient: BackendAiProxyClient,
    private val backendUrlSupplier: () -> String
) : AiModelProvider {

    @Volatile
    private var liveStatus: ProviderConnectionStatus =
        if (providerId.isFreeProvider) ProviderConnectionStatus.MISSING_API_KEY
        else ProviderConnectionStatus.PLANNED_PART_02

    @Volatile
    private var diagnosticMessage: String =
        if (providerId.isFreeProvider) {
            if (providerId.requiresAccountId) {
                "Missing ${providerId.envKeyName} & CLOUDFLARE_ACCOUNT_ID. Save credentials in Backend & API Settings."
            } else {
                "Missing ${providerId.envKeyName}. Save your key in Backend & API Settings."
            }
        } else {
            "Not Connected • Configure backend server URL & API key in Project Settings."
        }

    @Volatile
    private var keySource: String = "NONE"

    @Volatile
    private var maskedKeyHint: String = ""

    @Volatile
    private var hasAccountId: Boolean = false

    @Volatile
    private var discoveredModelIds: List<String> = emptyList()

    override val descriptor: AiProviderDescriptor
        get() {
            val operational = liveStatus.isOperational
            return AiProviderDescriptor(
                providerId = providerId,
                displayName = providerId.displayName,
                status = liveStatus,
                securityNote = diagnosticMessage,
                models = defaultModelsFor(providerId, operational, discoveredModelIds),
                isFreeProvider = providerId.isFreeProvider,
                keySource = keySource,
                maskedKeyHint = maskedKeyHint,
                requiresAccountId = providerId.requiresAccountId,
                hasAccountId = hasAccountId
            )
        }

    fun applyProbeState(probeState: ProviderLiveProbeState) {
        if (probeState.providerId != providerId) return
        liveStatus = probeState.status
        diagnosticMessage = probeState.diagnosticMessage.ifBlank { probeState.status.label }
        keySource = probeState.keySource
        maskedKeyHint = probeState.maskedKeyHint
        hasAccountId = probeState.hasAccountId
        if (probeState.discoveredModels.isNotEmpty()) {
            discoveredModelIds = probeState.discoveredModels
        }
    }

    fun reportRuntimeFailure(throwable: Throwable) {
        val status = classifyProviderError(throwable)
        liveStatus = status
        diagnosticMessage = throwable.localizedMessage?.takeIf { it.isNotBlank() } ?: status.label
        if (status == ProviderConnectionStatus.MISSING_API_KEY) {
            keySource = "NONE"
            maskedKeyHint = ""
        }
    }

    fun resetDisconnected(reason: String = "Backend proxy unreachable or unconfigured.") {
        val isOfflineReason = reason.contains("offline", ignoreCase = true) ||
            reason.contains("unreachable", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true)
        liveStatus = when {
            isOfflineReason && keySource != "NONE" -> ProviderConnectionStatus.OFFLINE
            providerId.isFreeProvider && keySource == "NONE" -> ProviderConnectionStatus.MISSING_API_KEY
            providerId.isFreeProvider -> ProviderConnectionStatus.OFFLINE
            else -> ProviderConnectionStatus.PLANNED_PART_02
        }
        diagnosticMessage = reason
    }

    override suspend fun analyzeAndPlan(
        request: BrainGenerationRequest,
        modelId: String
    ): StructuredOrchestrationPlan {
        val baseUrl = request.backendServerUrl.ifBlank { backendUrlSupplier() }
        val systemPrompt =
            "You are ${providerId.displayName} acting inside MYRAA AI SUPER BRAIN. Respond ONLY in JSON matching keys: detectedLanguage (EN|BN|BN_EN), detectedGoal, intentSummary, selectedTaskCategory (MASTER_PLANNING|ARCHITECTURE_DESIGN|CODE_REASONING|MULTILINGUAL_CHAT|VISION_ANALYSIS|QUICK_SUBTASK), recommendedFramework (ANDROID_COMPOSE|WEB_APP|BACKEND_API|FULLSTACK_SYSTEM), assignedAgents (array), subtasks (array), architectureNotes, detectedRisksOrGaps (array)."
        val prompt = buildString {
            appendLine("Language preference: ${request.languagePreference.wireValue}")
            appendLine("Project Context:\n${request.projectContextSummary}")
            appendLine("Directive:\n${request.prompt}")
        }
        val result = proxyClient.generateViaProxy(
            baseUrl = baseUrl,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt,
            prompt = prompt,
            jsonMode = true
        ).getOrThrow()

        val cleanJson = result.text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = runCatching { JSONObject(cleanJson) }.getOrNull() ?: JSONObject()
        return StructuredOrchestrationPlan(
            detectedLanguage = BrainLanguage.fromWire(json.optString("detectedLanguage", "AUTO")),
            detectedGoal = json.optString("detectedGoal", request.prompt.take(120)),
            intentSummary = json.optString("intentSummary", request.prompt.take(160)),
            selectedTaskCategory = TaskCategory.fromWire(json.optString("selectedTaskCategory", "MASTER_PLANNING")),
            recommendedFramework = TargetFramework.fromWire(json.optString("recommendedFramework", "ANDROID_COMPOSE")),
            assignedAgents = json.optJSONArray("assignedAgents")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.orEmpty().ifEmpty { listOf("Master Planner Agent", "Coding Agent") },
            subtasks = json.optJSONArray("subtasks")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.orEmpty().ifEmpty { listOf("Analyze requirements", "Implement architecture", "Verify build") },
            architectureNotes = json.optString("architectureNotes", "Planned via ${providerId.displayName}"),
            detectedRisksOrGaps = json.optJSONArray("detectedRisksOrGaps")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.orEmpty()
        )
    }

    override fun streamSynthesizedResponse(
        request: BrainGenerationRequest,
        plan: StructuredOrchestrationPlan,
        modelId: String
    ): Flow<String> = flow {
        val baseUrl = request.backendServerUrl.ifBlank { backendUrlSupplier() }
        val sys = "You are ${providerId.displayName} in MYRAA AI SUPER BRAIN. Give clear, accurate technical engineering answers matching the user's language (${plan.detectedLanguage.nativeLabel})."
        val userPrompt = "Goal: ${plan.detectedGoal}\nSubtasks: ${plan.subtasks.joinToString("; ")}\n\nRequest:\n${request.prompt}"
        emitAll(
            proxyClient.streamViaProxy(
                baseUrl = baseUrl,
                providerId = providerId,
                modelId = modelId,
                systemPrompt = sys,
                prompt = userPrompt
            )
        )
    }

    override suspend fun generateRawText(
        prompt: String,
        systemPrompt: String,
        modelId: String,
        backendServerUrl: String
    ): ProviderGenerationOutput {
        val baseUrl = backendServerUrl.ifBlank { backendUrlSupplier() }
        val result = proxyClient.generateViaProxy(
            baseUrl = baseUrl,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt,
            prompt = prompt,
            jsonMode = false
        ).getOrThrow()
        val costMicros = if (providerId.isFreeProvider) {
            0L
        } else {
            TokenAndCostTracker.computeCostUsdMicros(
                modelId = result.modelId,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens
            )
        }
        return ProviderGenerationOutput(
            text = result.text,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            latencyMs = result.latencyMs,
            estimatedCostUsdMicros = costMicros
        )
    }

    companion object {
        fun defaultModelsFor(
            providerId: ProviderId,
            isLive: Boolean,
            discoveredModelIds: List<String> = emptyList()
        ): List<AiModelDescriptor> {
            return when (providerId) {
                ProviderId.GROQ -> listOf(
                    AiModelDescriptor(
                        modelId = "llama-3.3-70b-versatile",
                        providerId = ProviderId.GROQ,
                        displayName = "Llama 3.3 70B Versatile",
                        roleSummary = "Ultra-fast general & engineering inference on Groq LPU",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.FAST_CHAT,
                            ModelCapability.STRUCTURED_JSON,
                            ModelCapability.MULTILINGUAL_BN_EN
                        ),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Groq Free Tier • Rate-limited RPM/TPM/TPD"
                    ),
                    AiModelDescriptor(
                        modelId = "llama-3.1-8b-instant",
                        providerId = ProviderId.GROQ,
                        displayName = "Llama 3.1 8B Instant",
                        roleSummary = "Lowest-latency free model for fast subtasks & summaries",
                        capabilities = setOf(ModelCapability.FAST_CHAT, ModelCapability.CODING),
                        latencyTier = "Instant",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Groq Free Tier • High RPM allowance"
                    ),
                    AiModelDescriptor(
                        modelId = "openai/gpt-oss-120b",
                        providerId = ProviderId.GROQ,
                        displayName = "GPT-OSS 120B (Groq)",
                        roleSummary = "Open-weight reasoning powerhouse hosted free on Groq",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Groq Free Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "openai/gpt-oss-20b",
                        providerId = ProviderId.GROQ,
                        displayName = "GPT-OSS 20B (Groq)",
                        roleSummary = "Compact fast reasoning & structured generation on Groq",
                        capabilities = setOf(ModelCapability.FAST_CHAT, ModelCapability.CODING),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Groq Free Tier"
                    )
                )

                ProviderId.OPENROUTER_FREE -> {
                    val baseFree = listOf(
                        AiModelDescriptor(
                            modelId = "openrouter/free",
                            providerId = ProviderId.OPENROUTER_FREE,
                            displayName = "OpenRouter Free Auto-Router",
                            roleSummary = "Automatically routes across verified healthy :free models",
                            capabilities = setOf(
                                ModelCapability.REASONING,
                                ModelCapability.CODING,
                                ModelCapability.FAST_CHAT
                            ),
                            latencyTier = "Fast",
                            costTier = "FREE",
                            isLiveInCurrentBuild = isLive,
                            requiresBillingOrBackendProxy = true,
                            isFreeTier = true,
                            freeTierLimitsSummary = "OpenRouter :free pool • Zero credit spend"
                        ),
                        AiModelDescriptor(
                            modelId = "meta-llama/llama-3.3-70b-instruct:free",
                            providerId = ProviderId.OPENROUTER_FREE,
                            displayName = "Llama 3.3 70B Instruct (:free)",
                            roleSummary = "Multilingual instruction & Kotlin/Android architecture",
                            capabilities = setOf(
                                ModelCapability.REASONING,
                                ModelCapability.CODING,
                                ModelCapability.MULTILINGUAL_BN_EN
                            ),
                            latencyTier = "Fast",
                            costTier = "FREE",
                            isLiveInCurrentBuild = isLive,
                            requiresBillingOrBackendProxy = true,
                            isFreeTier = true,
                            freeTierLimitsSummary = "OpenRouter :free endpoint"
                        ),
                        AiModelDescriptor(
                            modelId = "qwen/qwen3-coder:free",
                            providerId = ProviderId.OPENROUTER_FREE,
                            displayName = "Qwen3 Coder (:free)",
                            roleSummary = "Dedicated code synthesis, bug fixing & Compose scaffolding",
                            capabilities = setOf(ModelCapability.CODING, ModelCapability.REASONING),
                            latencyTier = "Fast",
                            costTier = "FREE",
                            isLiveInCurrentBuild = isLive,
                            requiresBillingOrBackendProxy = true,
                            isFreeTier = true,
                            freeTierLimitsSummary = "OpenRouter :free endpoint"
                        ),
                        AiModelDescriptor(
                            modelId = "deepseek/deepseek-r1-0528:free",
                            providerId = ProviderId.OPENROUTER_FREE,
                            displayName = "DeepSeek R1 (:free)",
                            roleSummary = "Deep step-by-step architectural reasoning (:free endpoint)",
                            capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                            latencyTier = "Standard",
                            costTier = "FREE",
                            isLiveInCurrentBuild = isLive,
                            requiresBillingOrBackendProxy = true,
                            isFreeTier = true,
                            freeTierLimitsSummary = "OpenRouter :free endpoint"
                        )
                    )
                    // Only append dynamically discovered models that are explicitly :free or openrouter/free
                    val knownIds = baseFree.map { it.modelId }.toSet()
                    val extraFree = discoveredModelIds
                        .filter { id -> id == "openrouter/free" || id.endsWith(":free") }
                        .filter { id -> id !in knownIds }
                        .take(3)
                        .map { id ->
                            AiModelDescriptor(
                                modelId = id,
                                providerId = ProviderId.OPENROUTER_FREE,
                                displayName = id.substringAfter('/').removeSuffix(":free")
                                    .replace('-', ' ').replaceFirstChar { it.uppercase() } + " (:free)",
                                roleSummary = "Live discovered OpenRouter :free model",
                                capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                                latencyTier = "Fast",
                                costTier = "FREE",
                                isLiveInCurrentBuild = isLive,
                                requiresBillingOrBackendProxy = true,
                                isFreeTier = true,
                                freeTierLimitsSummary = "Discovered via OpenRouter /models"
                            )
                        }
                    baseFree + extraFree
                }

                ProviderId.MISTRAL -> listOf(
                    AiModelDescriptor(
                        modelId = "mistral-small-latest",
                        providerId = ProviderId.MISTRAL,
                        displayName = "Mistral Small Latest",
                        roleSummary = "Balanced instruction, JSON structuring & multilingual reasoning",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.STRUCTURED_JSON,
                            ModelCapability.FAST_CHAT
                        ),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Mistral Free Experiment Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "codestral-latest",
                        providerId = ProviderId.MISTRAL,
                        displayName = "Codestral Latest",
                        roleSummary = "Specialized Kotlin, TypeScript & multi-file coding generation",
                        capabilities = setOf(ModelCapability.CODING, ModelCapability.REASONING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Mistral Free Experiment Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "ministral-8b-latest",
                        providerId = ProviderId.MISTRAL,
                        displayName = "Ministral 8B Latest",
                        roleSummary = "Lightweight fast assistant for rapid subtasks",
                        capabilities = setOf(ModelCapability.FAST_CHAT, ModelCapability.CODING),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Mistral Free Experiment Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "open-mistral-nemo",
                        providerId = ProviderId.MISTRAL,
                        displayName = "Open Mistral Nemo",
                        roleSummary = "Open multilingual 12B architecture for EN/BN cross-chat",
                        capabilities = setOf(ModelCapability.MULTILINGUAL_BN_EN, ModelCapability.FAST_CHAT),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Mistral Free Experiment Tier"
                    )
                )

                ProviderId.COHERE -> listOf(
                    AiModelDescriptor(
                        modelId = "command-a-03-2025",
                        providerId = ProviderId.COHERE,
                        displayName = "Command A (03-2025)",
                        roleSummary = "Flagship Cohere reasoning, multilingual & grounded synthesis (/v2/chat)",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.MULTILINGUAL_BN_EN
                        ),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cohere Free Trial API Key"
                    ),
                    AiModelDescriptor(
                        modelId = "command-r-plus-08-2024",
                        providerId = ProviderId.COHERE,
                        displayName = "Command R+ (08-2024)",
                        roleSummary = "Enterprise RAG, tool-use & structured engineering synthesis",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.MULTILINGUAL_BN_EN),
                        latencyTier = "Medium",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cohere Free Trial API Key"
                    ),
                    AiModelDescriptor(
                        modelId = "command-r7b-12-2024",
                        providerId = ProviderId.COHERE,
                        displayName = "Command R7B (12-2024)",
                        roleSummary = "Fast compact Cohere model for quick comparison & fallback",
                        capabilities = setOf(ModelCapability.FAST_CHAT, ModelCapability.CODING),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cohere Free Trial API Key"
                    )
                )

                ProviderId.CLOUDFLARE_AI -> listOf(
                    AiModelDescriptor(
                        modelId = "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                        providerId = ProviderId.CLOUDFLARE_AI,
                        displayName = "Llama 3.3 70B FP8 Fast (CF)",
                        roleSummary = "Edge serverless 70B inference inside Cloudflare's 10K Neurons/day free tier",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING, ModelCapability.FAST_CHAT),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cloudflare 10,000 Neurons/day Free"
                    ),
                    AiModelDescriptor(
                        modelId = "@cf/meta/llama-3.1-8b-instruct",
                        providerId = ProviderId.CLOUDFLARE_AI,
                        displayName = "Llama 3.1 8B Instruct (CF)",
                        roleSummary = "Low-neuron-footprint fast worker model on Cloudflare Edge",
                        capabilities = setOf(ModelCapability.FAST_CHAT),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cloudflare 10,000 Neurons/day Free"
                    ),
                    AiModelDescriptor(
                        modelId = "@cf/meta/llama-4-scout-17b-16e-instruct",
                        providerId = ProviderId.CLOUDFLARE_AI,
                        displayName = "Llama 4 Scout 17B (CF)",
                        roleSummary = "MoE instruction model for code architecture & rapid edge fallback",
                        capabilities = setOf(ModelCapability.CODING, ModelCapability.REASONING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Cloudflare 10,000 Neurons/day Free"
                    )
                )

                ProviderId.NVIDIA_NIM -> listOf(
                    AiModelDescriptor(
                        modelId = "meta/llama-3.3-70b-instruct",
                        providerId = ProviderId.NVIDIA_NIM,
                        displayName = "Llama 3.3 70B Instruct (NIM)",
                        roleSummary = "GPU-accelerated NVIDIA Build serverless API with free credits",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "NVIDIA Build Free Developer Credits"
                    ),
                    AiModelDescriptor(
                        modelId = "deepseek-ai/deepseek-r1",
                        providerId = ProviderId.NVIDIA_NIM,
                        displayName = "DeepSeek R1 (NVIDIA NIM)",
                        roleSummary = "Deep step-by-step architecture & static debugging reasoning",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Standard",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "NVIDIA Build Free Developer Credits"
                    ),
                    AiModelDescriptor(
                        modelId = "qwen/qwen2.5-coder-32b-instruct",
                        providerId = ProviderId.NVIDIA_NIM,
                        displayName = "Qwen 2.5 Coder 32B (NIM)",
                        roleSummary = "Specialized code synthesis, Gradle build & bug fixes",
                        capabilities = setOf(ModelCapability.CODING, ModelCapability.REASONING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "NVIDIA Build Free Developer Credits"
                    )
                )

                ProviderId.SAMBANOVA -> listOf(
                    AiModelDescriptor(
                        modelId = "Meta-Llama-3.3-70B-Instruct",
                        providerId = ProviderId.SAMBANOVA,
                        displayName = "Meta Llama 3.3 70B (SN)",
                        roleSummary = "High-throughput SambaNova RDU free developer inference",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING, ModelCapability.FAST_CHAT),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "SambaNova Free Developer Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "DeepSeek-R1",
                        providerId = ProviderId.SAMBANOVA,
                        displayName = "DeepSeek R1 (SambaNova)",
                        roleSummary = "Accelerated DeepSeek R1 reasoning hosted on SambaNova",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "SambaNova Free Developer Tier"
                    ),
                    AiModelDescriptor(
                        modelId = "Qwen3-32B",
                        providerId = ProviderId.SAMBANOVA,
                        displayName = "Qwen3 32B (SambaNova)",
                        roleSummary = "Fast multilingual & coding assistant hosted on SambaNova",
                        capabilities = setOf(ModelCapability.CODING, ModelCapability.MULTILINGUAL_BN_EN),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = true,
                        freeTierLimitsSummary = "SambaNova Free Developer Tier"
                    )
                )

                // Legacy Paid Providers (NOT Free; never shown in Free Model Hub)
                ProviderId.OPENAI_GPT -> listOf(
                    AiModelDescriptor(
                        modelId = "gpt-4.1-mini",
                        providerId = ProviderId.OPENAI_GPT,
                        displayName = "GPT-4.1 Mini",
                        roleSummary = "Fast structured engineering & side-by-side comparison",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.STRUCTURED_JSON,
                            ModelCapability.MULTILINGUAL_BN_EN
                        ),
                        latencyTier = "Fast",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    ),
                    AiModelDescriptor(
                        modelId = "gpt-4.1",
                        providerId = ProviderId.OPENAI_GPT,
                        displayName = "GPT-4.1",
                        roleSummary = "Flagship architecture & multi-file coding via proxy",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.STRUCTURED_JSON
                        ),
                        latencyTier = "Medium",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    )
                )

                ProviderId.ANTHROPIC_CLAUDE -> listOf(
                    AiModelDescriptor(
                        modelId = "claude-sonnet-4-20250514",
                        providerId = ProviderId.ANTHROPIC_CLAUDE,
                        displayName = "Claude Sonnet 4",
                        roleSummary = "Deep architectural code review, refactoring & consensus",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.MULTILINGUAL_BN_EN
                        ),
                        latencyTier = "Medium",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    )
                )

                ProviderId.DEEPSEEK -> listOf(
                    AiModelDescriptor(
                        modelId = "deepseek-chat",
                        providerId = ProviderId.DEEPSEEK,
                        displayName = "DeepSeek Chat",
                        roleSummary = "Cost-efficient coding agent & debugging synthesis",
                        capabilities = setOf(ModelCapability.CODING, ModelCapability.REASONING),
                        latencyTier = "Fast",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    ),
                    AiModelDescriptor(
                        modelId = "deepseek-reasoner",
                        providerId = ProviderId.DEEPSEEK,
                        displayName = "DeepSeek Reasoner",
                        roleSummary = "Deep step-by-step static debugging & verification",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Standard",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    )
                )

                ProviderId.META_LLAMA -> listOf(
                    AiModelDescriptor(
                        modelId = "Llama-3.3-70B-Instruct",
                        providerId = ProviderId.META_LLAMA,
                        displayName = "Meta Llama 3.3 70B",
                        roleSummary = "Open-weights coding & multi-AI comparison model",
                        capabilities = setOf(ModelCapability.FAST_CHAT, ModelCapability.CODING),
                        latencyTier = "Fast",
                        costTier = "Paid API Key",
                        isLiveInCurrentBuild = isLive,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    )
                )

                ProviderId.GOOGLE_GEMINI -> emptyList()
            }
        }
    }
}
