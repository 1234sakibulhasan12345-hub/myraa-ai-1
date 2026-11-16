package com.example.ai.provider

import com.example.data.model.RoutingPolicy

class ModelRouter(
    private val registry: ModelCapabilityRegistry
) {
    fun selectModel(request: BrainGenerationRequest): ModelRoutingDecision {
        val taskCategory = inferInitialTaskCategory(request)

        // 1. If user explicitly chose an operational provider (and no vision bitmap for text-only external providers)
        val preferredProvider = request.preferredProviderId
            ?.takeIf { it == ProviderId.GOOGLE_GEMINI || request.attachedBitmap == null }
            ?.let { registry.getLiveProvider(it) }

        if (preferredProvider != null && preferredProvider.descriptor.providerId != ProviderId.GOOGLE_GEMINI) {
            val liveModels = preferredProvider.descriptor.models
                .filter { it.isLiveInCurrentBuild }
                .sortedByDescending { it.isFreeTier }
            if (liveModels.isNotEmpty()) {
                val chosenModel = chooseModelForTask(liveModels, taskCategory, request.routingPolicy)
                val fallbackId = nextFallbackCandidate(request, chosenModel)?.modelId ?: MODEL_GEMINI_FLASH
                return ModelRoutingDecision(
                    selectedProvider = preferredProvider.descriptor.providerId,
                    selectedModel = chosenModel,
                    fallbackModelId = fallbackId,
                    taskCategory = taskCategory,
                    rationale = "Routed directly to ${chosenModel.displayName} (${preferredProvider.descriptor.displayName}) with automatic Free-tier fallback."
                )
            }
        }

        // 2. Try Google Gemini when it is live/operational
        val geminiProvider = registry.getLiveProvider(ProviderId.GOOGLE_GEMINI)
        if (geminiProvider != null) {
            val liveModels = geminiProvider.descriptor.models.filter { it.isLiveInCurrentBuild }
            if (liveModels.isNotEmpty()) {
                val defaultFlash = liveModels.firstOrNull { it.modelId == MODEL_GEMINI_FLASH }
                    ?: liveModels.first()
                val liteModel = liveModels.firstOrNull { it.modelId == MODEL_GEMINI_FLASH_LITE }
                    ?: defaultFlash

                val (chosen, defaultFallback, rationale) = when {
                    request.attachedBitmap != null -> Triple(
                        defaultFlash,
                        liteModel.modelId,
                        "Selected ${defaultFlash.displayName} (Free Tier) for multimodal vision + multilingual orchestration."
                    )
                    request.routingPolicy == RoutingPolicy.ULTRA_FAST -> Triple(
                        liteModel,
                        defaultFlash.modelId,
                        "Ultra Fast policy routed request to ${liteModel.displayName} (Free Tier) with ${defaultFlash.displayName} fallback."
                    )
                    request.routingPolicy == RoutingPolicy.QUALITY_DEFAULT -> Triple(
                        defaultFlash,
                        liteModel.modelId,
                        "Deep Reasoning policy routed request to ${defaultFlash.displayName} (Free Tier) for structured architecture fidelity."
                    )
                    taskCategory == TaskCategory.QUICK_SUBTASK && request.prompt.length < 90 -> Triple(
                        liteModel,
                        defaultFlash.modelId,
                        "Auto Orchestrate selected ${liteModel.displayName} (Free Tier) for concise fast-turn prompt."
                    )
                    else -> Triple(
                        defaultFlash,
                        liteModel.modelId,
                        "Auto Orchestrate selected ${defaultFlash.displayName} (Free Tier) for multilingual planning & multi-agent coordination."
                    )
                }

                return ModelRoutingDecision(
                    selectedProvider = geminiProvider.descriptor.providerId,
                    selectedModel = chosen,
                    fallbackModelId = defaultFallback,
                    taskCategory = taskCategory,
                    rationale = rationale
                )
            }
        }

        // 3. Gemini is offline/rate-limited/blocked -> auto-select best operational Free external provider
        val liveFreeModels = registry.getAllLiveFreeModels()
        if (liveFreeModels.isNotEmpty()) {
            val chosen = chooseModelForTask(liveFreeModels, taskCategory, request.routingPolicy)
            val next = nextFallbackCandidate(request, chosen)?.modelId ?: chosen.modelId
            return ModelRoutingDecision(
                selectedProvider = chosen.providerId,
                selectedModel = chosen,
                fallbackModelId = next,
                taskCategory = taskCategory,
                rationale = "Auto-routed to operational Free model ${chosen.displayName} (${chosen.providerId.displayName})."
            )
        }

        // 4. Last resort fallback to primary provider descriptor (e.g. unit tests or unverified state)
        val primaryProvider = registry.getPrimaryLiveProvider()
        val models = primaryProvider.descriptor.models
        val fallbackModel = models.firstOrNull { it.modelId == MODEL_GEMINI_FLASH } ?: models.first()
        return ModelRoutingDecision(
            selectedProvider = primaryProvider.descriptor.providerId,
            selectedModel = fallbackModel,
            fallbackModelId = MODEL_GEMINI_FLASH_LITE,
            taskCategory = taskCategory,
            rationale = "Selected ${fallbackModel.displayName}."
        )
    }

    fun buildFallbackCandidates(
        request: BrainGenerationRequest,
        primaryDecision: ModelRoutingDecision
    ): List<AiModelDescriptor> {
        val seen = linkedSetOf("${primaryDecision.selectedProvider.wireId}:${primaryDecision.selectedModel.modelId}")
        val ordered = mutableListOf<AiModelDescriptor>()

        // Same-provider fallback first (when model matches fallbackModelId)
        val sameProviderModels = registry.getLiveProvider(primaryDecision.selectedProvider)
            ?.descriptor?.models.orEmpty()
            .filter { it.isLiveInCurrentBuild && it.modelId == primaryDecision.fallbackModelId }
        sameProviderModels.forEach { m ->
            val key = "${m.providerId.wireId}:${m.modelId}"
            if (seen.add(key)) ordered.add(m)
        }

        // Cross-provider live free models prioritized by quality/speed depending on task
        val allLiveFree = registry.getAllLiveFreeModels()
            .filter { request.attachedBitmap == null || it.providerId == ProviderId.GOOGLE_GEMINI }
        val prioritizedProviders = listOf(
            ProviderId.GROQ,
            ProviderId.GOOGLE_GEMINI,
            ProviderId.SAMBANOVA,
            ProviderId.OPENROUTER_FREE,
            ProviderId.MISTRAL,
            ProviderId.COHERE,
            ProviderId.CLOUDFLARE_AI,
            ProviderId.NVIDIA_NIM
        )

        prioritizedProviders.forEach { pid ->
            val providerModels = allLiveFree.filter { it.providerId == pid }
            if (providerModels.isNotEmpty()) {
                val pick = chooseModelForTask(providerModels, primaryDecision.taskCategory, request.routingPolicy)
                val key = "${pick.providerId.wireId}:${pick.modelId}"
                if (seen.add(key)) ordered.add(pick)
            }
        }
        return ordered
    }

    private fun nextFallbackCandidate(
        request: BrainGenerationRequest,
        chosen: AiModelDescriptor
    ): AiModelDescriptor? {
        val candidates = registry.getAllLiveFreeModels().filter {
            (request.attachedBitmap == null || it.providerId == ProviderId.GOOGLE_GEMINI) &&
                (it.providerId != chosen.providerId || it.modelId != chosen.modelId)
        }
        return candidates.firstOrNull { it.providerId != chosen.providerId } ?: candidates.firstOrNull()
    }

    private fun chooseModelForTask(
        candidates: List<AiModelDescriptor>,
        taskCategory: TaskCategory,
        policy: RoutingPolicy
    ): AiModelDescriptor {
        return when {
            policy == RoutingPolicy.ULTRA_FAST || taskCategory == TaskCategory.QUICK_SUBTASK ->
                candidates.firstOrNull { ModelCapability.FAST_CHAT in it.capabilities } ?: candidates.first()
            taskCategory == TaskCategory.CODE_REASONING || taskCategory == TaskCategory.ARCHITECTURE_DESIGN ->
                candidates.firstOrNull { ModelCapability.CODING in it.capabilities && ModelCapability.REASONING in it.capabilities }
                    ?: candidates.firstOrNull { ModelCapability.CODING in it.capabilities }
                    ?: candidates.first()
            else ->
                candidates.firstOrNull { ModelCapability.REASONING in it.capabilities } ?: candidates.first()
        }
    }

    private fun inferInitialTaskCategory(request: BrainGenerationRequest): TaskCategory {
        if (request.attachedBitmap != null) return TaskCategory.VISION_ANALYSIS
        val lower = request.prompt.lowercase()
        val isFullAppDirective = listOf(
            "অ্যাপ", "তৈরি", "বানাও", "create a ", "create an ",
            "build a ", "build an ", "complete android", "complete app", "master plan"
        ).any { it in lower }

        return when {
            isFullAppDirective -> TaskCategory.MASTER_PLANNING
            listOf("architecture", "database", "schema", "firebase", "firestore", "backend", "api", "rules")
                .any { it in lower } -> TaskCategory.ARCHITECTURE_DESIGN
            listOf("code", "kotlin", "compose", "react", "bug", "error", "fix", "function", "কোড")
                .any { it in lower } -> TaskCategory.CODE_REASONING
            listOf("app", "create", "build", "plan", "project", "প্ল্যান")
                .any { it in lower } -> TaskCategory.MASTER_PLANNING
            request.prompt.trim().length < 55 -> TaskCategory.QUICK_SUBTASK
            else -> TaskCategory.MULTILINGUAL_CHAT
        }
    }

    companion object {
        const val MODEL_GEMINI_FLASH = "gemini-3.8-flash"
        const val MODEL_GEMINI_FLASH_LITE = "gemini-3.5-flash-lite"
    }
}
