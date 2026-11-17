package com.example.ai.provider

import android.os.SystemClock
import com.example.data.model.BrainLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

class MultiAiOrchestrator(
    private val registry: ModelCapabilityRegistry,
    private val proxyClient: BackendAiProxyClient? = null
) {
    suspend fun checkBackendHealth(backendServerUrl: String): Result<BackendHealthInfo> {
        val cleanUrl = backendServerUrl.trim()
        val client = proxyClient
            ?: return Result.failure(IllegalStateException("Backend proxy client unavailable."))
        return client.checkHealth(cleanUrl)
    }

    suspend fun fetchBackendRegistrarStatus(backendServerUrl: String): Result<BackendRegistrarStatusInfo> {
        val cleanUrl = backendServerUrl.trim()
        val client = proxyClient
            ?: return Result.failure(IllegalStateException("Backend proxy client unavailable."))
        return client.fetchRegistrarStatus(cleanUrl)
    }

    suspend fun refreshExternalProviderStatuses(
        backendServerUrl: String,
        freeOnly: Boolean = false
    ): Result<List<ProviderLiveProbeState>> {
        val cleanUrl = backendServerUrl.trim()
        val client = proxyClient
        if (cleanUrl.isBlank() || client == null) {
            registry.resetExternalProvidersDisconnected("No remote backend URL configured.")
            return Result.failure(IllegalStateException("Backend server URL is not configured."))
        }

        val result = client.probeProviders(cleanUrl, liveProbe = true, freeOnly = freeOnly)
        result.onSuccess { probes ->
            registry.applyProbeResults(probes)
        }.onFailure { err ->
            registry.resetExternalProvidersDisconnected(err.message ?: "Offline: probe failed.")
        }
        return result
    }

    suspend fun saveAndVerifyProviderKey(
        backendServerUrl: String,
        providerId: ProviderId,
        apiKey: String,
        accountId: String = ""
    ): Result<ProviderLiveProbeState> {
        val client = proxyClient
            ?: return Result.failure(IllegalStateException("Backend proxy client unavailable."))
        val result = client.saveProviderApiKey(
            baseUrl = backendServerUrl,
            providerId = providerId,
            apiKey = apiKey,
            accountId = accountId
        )
        result.onSuccess { probeState ->
            registry.applySingleProbeResult(probeState)
        }
        return result
    }

    suspend fun deleteProviderKey(
        backendServerUrl: String,
        providerId: ProviderId
    ): Result<ProviderLiveProbeState> {
        val client = proxyClient
            ?: return Result.failure(IllegalStateException("Backend proxy client unavailable."))
        val result = client.deleteProviderApiKey(
            baseUrl = backendServerUrl,
            providerId = providerId
        )
        result.onSuccess { probeState ->
            registry.applySingleProbeResult(probeState)
        }
        return result
    }

    suspend fun runParallelComparison(
        prompt: String,
        targetModels: List<AiModelDescriptor>,
        languagePreference: BrainLanguage,
        backendServerUrl: String
    ): List<ParallelModelResponse> = coroutineScope {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Prompt must not be empty." }

        val langDirective = when (languagePreference) {
            BrainLanguage.BN -> "Respond concisely and accurately in Bengali (বাংলা)."
            BrainLanguage.BN_EN -> "Respond concisely in clear Bangla-English (Benglish)."
            else -> "Respond concisely, accurately, and practically."
        }
        val sysPrompt = "You are participating in a MYRAA Multi-AI side-by-side comparison. $langDirective"

        targetModels.distinctBy { "${it.providerId.wireId}:${it.modelId}" }.map { model ->
            async {
                val startedAt = SystemClock.elapsedRealtime()
                val liveProvider = registry.getLiveProvider(model.providerId)
                if (liveProvider == null || !model.isLiveInCurrentBuild) {
                    val currentStatus = registry.getAnyProvider(model.providerId)?.descriptor?.status
                    val statusReason = when (currentStatus) {
                        ProviderConnectionStatus.MISSING_API_KEY ->
                            "${model.displayName} is not connected: Missing API key in Backend Settings."
                        ProviderConnectionStatus.RATE_LIMITED ->
                            "${model.displayName} hit provider rate limit (429)."
                        ProviderConnectionStatus.QUOTA_EXPIRED ->
                            "${model.displayName} free quota/credits expired."
                        ProviderConnectionStatus.OFFLINE ->
                            "${model.displayName} backend/provider is currently offline."
                        else ->
                            "${model.displayName} is not connected. Configure backend URL & API key first."
                    }
                    return@async ParallelModelResponse(
                        providerId = model.providerId,
                        providerName = model.providerId.displayName,
                        modelId = model.modelId,
                        displayModelName = model.displayName,
                        content = "",
                        latencyMs = 0L,
                        promptTokens = 0L,
                        completionTokens = 0L,
                        estimatedCostUsdMicros = 0L,
                        errorMessage = statusReason
                    )
                }

                try {
                    val out = withTimeout(COMPARISON_TIMEOUT_MS) {
                        liveProvider.generateRawText(
                            prompt = cleanPrompt,
                            systemPrompt = sysPrompt,
                            modelId = model.modelId,
                            backendServerUrl = backendServerUrl
                        )
                    }
                    ParallelModelResponse(
                        providerId = model.providerId,
                        providerName = model.providerId.displayName,
                        modelId = model.modelId,
                        displayModelName = model.displayName,
                        content = out.text,
                        latencyMs = out.latencyMs,
                        promptTokens = out.promptTokens,
                        completionTokens = out.completionTokens,
                        estimatedCostUsdMicros = out.estimatedCostUsdMicros
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    registry.reportProviderFailure(model.providerId, e)
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                    ParallelModelResponse(
                        providerId = model.providerId,
                        providerName = model.providerId.displayName,
                        modelId = model.modelId,
                        displayModelName = model.displayName,
                        content = "",
                        latencyMs = elapsed,
                        promptTokens = 0L,
                        completionTokens = 0L,
                        estimatedCostUsdMicros = 0L,
                        errorMessage = e.localizedMessage ?: "Provider execution failed."
                    )
                }
            }
        }.awaitAll()
    }

    suspend fun synthesizeConsensus(
        originalPrompt: String,
        responses: List<ParallelModelResponse>,
        languagePreference: BrainLanguage,
        backendServerUrl: String = ""
    ): ConsensusSynthesisResult {
        val succeeded = responses.filter { it.errorMessage == null && it.content.isNotBlank() }
        if (succeeded.isEmpty()) {
            throw IllegalStateException("No successful live model outputs available to synthesize consensus.")
        }
        val startedAt = SystemClock.elapsedRealtime()
        val liveFreeModels = registry.getAllLiveFreeModels()
        val synthesizer = registry.getPrimaryLiveProvider()
        val synthModelId = synthesizer.descriptor.models
            .firstOrNull { it.isLiveInCurrentBuild }?.modelId
            ?: liveFreeModels.firstOrNull()?.modelId
            ?: ModelRouter.MODEL_GEMINI_FLASH

        val joined = succeeded.joinToString("\n\n---\n\n") { resp ->
            "MODEL: ${resp.displayModelName} (${resp.providerName}, ${resp.latencyMs}ms)\n${resp.content.take(2500)}"
        }
        val sysPrompt =
            "You are the MYRAA Consensus Arbitrator. Synthesize the strongest, error-free final engineering consensus from multiple model answers. Clearly note agreements, discrepancies, and the recommended action."
        val userPrompt = """
            |Original User Prompt:
            |$originalPrompt
            |
            |Preferred Language: ${languagePreference.wireValue}
            |
            |Candidate Model Outputs:
            |$joined
        """.trimMargin()

        val synthOut = withTimeout(COMPARISON_TIMEOUT_MS) {
            synthesizer.generateRawText(
                prompt = userPrompt,
                systemPrompt = sysPrompt,
                modelId = synthModelId,
                backendServerUrl = backendServerUrl
            )
        }

        val totalLatency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L) +
            succeeded.maxOf { it.latencyMs }
        val totalCost = succeeded.sumOf { it.estimatedCostUsdMicros } + synthOut.estimatedCostUsdMicros

        return ConsensusSynthesisResult(
            consensusText = synthOut.text,
            synthesizedByModelId = synthModelId,
            comparedResponses = responses,
            totalLatencyMs = totalLatency,
            totalEstimatedCostUsdMicros = totalCost
        )
    }

    companion object {
        private const val COMPARISON_TIMEOUT_MS = 60_000L
    }
}
