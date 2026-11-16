package com.example.ai.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ModelCapabilityRegistry(
    providers: List<AiModelProvider>
) {
    private val providerImplementations: Map<ProviderId, AiModelProvider> =
        providers.associateBy { it.descriptor.providerId }

    private val _catalogState = MutableStateFlow(buildSnapshot())
    val catalogState: StateFlow<List<AiProviderDescriptor>> = _catalogState.asStateFlow()

    private val _freeTierCatalogState = MutableStateFlow(buildFreeTierSnapshot())
    val freeTierCatalogState: StateFlow<List<AiProviderDescriptor>> = _freeTierCatalogState.asStateFlow()

    val catalog: List<AiProviderDescriptor>
        get() = buildSnapshot()

    fun getFreeTierCatalog(): List<AiProviderDescriptor> = buildFreeTierSnapshot()

    fun getLiveProvider(providerId: ProviderId): AiModelProvider? {
        val impl = providerImplementations[providerId] ?: return null
        return if (impl.descriptor.status.isOperational) impl else null
    }

    fun getAnyProvider(providerId: ProviderId): AiModelProvider? = providerImplementations[providerId]

    fun getPrimaryLiveProvider(): AiModelProvider =
        providerImplementations[ProviderId.GOOGLE_GEMINI]?.takeIf { it.descriptor.status.isOperational }
            ?: providerImplementations.values.firstOrNull {
                it.descriptor.isFreeProvider && it.descriptor.status.isOperational
            }
            ?: providerImplementations[ProviderId.GOOGLE_GEMINI]
            ?: providerImplementations.values.first { it.descriptor.status.isOperational }

    fun getAllLiveModels(): List<AiModelDescriptor> =
        buildSnapshot()
            .filter { it.status.isOperational }
            .flatMap { it.models }
            .filter { it.isLiveInCurrentBuild }

    fun getAllLiveFreeModels(): List<AiModelDescriptor> =
        buildFreeTierSnapshot()
            .filter { it.status.isOperational }
            .flatMap { it.models }
            .filter { it.isFreeTier && it.isLiveInCurrentBuild }

    fun applyProbeResults(probes: List<ProviderLiveProbeState>) {
        probes.forEach { probe ->
            val impl = providerImplementations[probe.providerId]
            if (impl is BackendProxyAiProvider) {
                impl.applyProbeState(probe)
            }
        }
        refreshSnapshots()
    }

    fun applySingleProbeResult(probe: ProviderLiveProbeState) {
        val impl = providerImplementations[probe.providerId]
        if (impl is BackendProxyAiProvider) {
            impl.applyProbeState(probe)
        }
        refreshSnapshots()
    }

    fun reportProviderFailure(providerId: ProviderId, throwable: Throwable) {
        when (val impl = providerImplementations[providerId]) {
            is BackendProxyAiProvider -> impl.reportRuntimeFailure(throwable)
            is GeminiFirebaseProvider -> impl.reportRuntimeFailure(throwable)
        }
        refreshSnapshots()
    }

    fun resetExternalProvidersDisconnected(reason: String) {
        providerImplementations.values.filterIsInstance<BackendProxyAiProvider>().forEach { proxy ->
            proxy.resetDisconnected(reason)
        }
        refreshSnapshots()
    }

    fun refreshSnapshots() {
        _catalogState.value = buildSnapshot()
        _freeTierCatalogState.value = buildFreeTierSnapshot()
    }

    private fun buildFreeTierSnapshot(): List<AiProviderDescriptor> = buildList {
        ProviderId.freeProviders.forEach { id ->
            val impl = providerImplementations[id]
            val desc = impl?.descriptor ?: defaultFreeBlueprint(id)
            // Strictly strip any non-free models (e.g., gemini-4-argon, gemini-nano-banana-2.1)
            val freeOnlyModels = desc.models.filter { it.isFreeTier }
            if (freeOnlyModels.isNotEmpty()) {
                add(desc.copy(isFreeProvider = true, models = freeOnlyModels))
            }
        }
    }

    private fun buildSnapshot(): List<AiProviderDescriptor> = buildList {
        val hasRegisteredExternalFree = ProviderId.externalFreeProviders.any { it in providerImplementations }
        val orderedIds = if (hasRegisteredExternalFree) {
            ProviderId.entries.toList()
        } else {
            listOf(
                ProviderId.GOOGLE_GEMINI,
                ProviderId.ANTHROPIC_CLAUDE,
                ProviderId.OPENAI_GPT,
                ProviderId.DEEPSEEK,
                ProviderId.META_LLAMA
            )
        }
        orderedIds.forEach { id ->
            val impl = providerImplementations[id]
            if (impl != null) {
                add(impl.descriptor)
            } else if (!hasRegisteredExternalFree) {
                val defaultDesc = defaultDisconnectedBlueprint(id)
                if (defaultDesc != null) add(defaultDesc)
            }
        }
    }

    private fun defaultFreeBlueprint(id: ProviderId): AiProviderDescriptor {
        val msg = if (id.requiresAccountId) {
            "Missing ${id.envKeyName} & CLOUDFLARE_ACCOUNT_ID. Configure backend URL & keys in Settings."
        } else {
            "Missing ${id.envKeyName.ifBlank { "configuration" }}. Configure backend URL & keys in Settings."
        }
        return AiProviderDescriptor(
            providerId = id,
            displayName = id.displayName,
            status = ProviderConnectionStatus.MISSING_API_KEY,
            securityNote = msg,
            models = BackendProxyAiProvider.defaultModelsFor(id, isLive = false),
            isFreeProvider = true,
            requiresAccountId = id.requiresAccountId
        )
    }

    private fun defaultDisconnectedBlueprint(id: ProviderId): AiProviderDescriptor? {
        if (id == ProviderId.GOOGLE_GEMINI) return null
        return AiProviderDescriptor(
            providerId = id,
            displayName = id.displayName,
            status = ProviderConnectionStatus.PLANNED_PART_02,
            securityNote = "Requires authenticated backend proxy URL & server API key (zero client secrets).",
            models = BackendProxyAiProvider.defaultModelsFor(id, isLive = false)
        )
    }
}
