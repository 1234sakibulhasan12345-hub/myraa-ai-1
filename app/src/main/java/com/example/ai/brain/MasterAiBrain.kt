package com.example.ai.brain

import android.os.SystemClock
import android.util.Log
import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiModelProvider
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.BrainStreamEvent
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ProviderId
import com.example.ai.provider.StructuredOrchestrationPlan
import com.example.ai.provider.TokenAndCostTracker
import com.example.core.security.AppCheckDiagnosticException
import com.example.core.security.isAppCheckTokenError
import com.example.data.model.BrainActivityPhase
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "MasterAiBrain"

class MasterAiBrain(
    private val registry: ModelCapabilityRegistry,
    private val router: ModelRouter
) {
    fun execute(
        request: BrainGenerationRequest,
        onPhaseChanged: (BrainActivityPhase) -> Unit
    ): Flow<BrainStreamEvent> = flow {
        val startMs = SystemClock.elapsedRealtime()

        onPhaseChanged(BrainActivityPhase.ANALYZING_INTENT)
        onPhaseChanged(BrainActivityPhase.SELECTING_MODEL)

        val routing = router.selectModel(request)
        val fallbackQueue = router.buildFallbackCandidates(request, routing)

        onPhaseChanged(BrainActivityPhase.PLANNING_SUBTASKS)

        var activeProvider: AiModelProvider = registry.getLiveProvider(routing.selectedProvider)
            ?: registry.getPrimaryLiveProvider()
        var activeModel: AiModelDescriptor = routing.selectedModel
        var fallbackRationaleNote: String? = null

        val plan: StructuredOrchestrationPlan = try {
            activeProvider.analyzeAndPlan(request, activeModel.modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (primaryError: Exception) {
            val isAppCheckErr = primaryError is AppCheckDiagnosticException || isAppCheckTokenError(primaryError)
            if (isAppCheckErr) {
                // Only fall back away from Google Gemini if an external Free provider is actually live.
                // Never retry another Gemini model on App Check rejection so diagnostic guidance surfaces honestly.
                val crossProviderCandidates = fallbackQueue.filter { it.providerId != ProviderId.GOOGLE_GEMINI }
                if (crossProviderCandidates.isEmpty()) {
                    throw primaryError
                }
                executePlanWithFallbackQueue(
                    request = request,
                    candidates = crossProviderCandidates,
                    primaryError = primaryError,
                    onChosen = { prov, model, reason ->
                        activeProvider = prov
                        activeModel = model
                        fallbackRationaleNote = reason
                    }
                )
            } else {
                Log.w(TAG, "Primary planning model ${activeModel.modelId} failed, trying fallback queue", primaryError)
                registry.reportProviderFailure(activeProvider.descriptor.providerId, primaryError)
                val candidates = fallbackQueue.ifEmpty {
                    val sameProv = activeProvider.descriptor.models.firstOrNull { it.modelId == routing.fallbackModelId }
                    listOfNotNull(sameProv)
                }
                if (candidates.isEmpty()) throw primaryError
                executePlanWithFallbackQueue(
                    request = request,
                    candidates = candidates,
                    primaryError = primaryError,
                    onChosen = { prov, model, reason ->
                        activeProvider = prov
                        activeModel = model
                        fallbackRationaleNote = reason
                    }
                )
            }
        }

        val updatedRouting = if (activeModel.modelId == routing.selectedModel.modelId &&
            activeProvider.descriptor.providerId == routing.selectedProvider
        ) {
            routing
        } else {
            routing.copy(
                selectedProvider = activeProvider.descriptor.providerId,
                selectedModel = activeModel,
                rationale = fallbackRationaleNote
                    ?: "${routing.rationale} (Auto-Fallback to ${activeModel.displayName})"
            )
        }

        onPhaseChanged(BrainActivityPhase.COORDINATING_AGENTS)
        emit(BrainStreamEvent.PlanReady(plan = plan, routing = updatedRouting))

        onPhaseChanged(BrainActivityPhase.STREAMING_RESPONSE)
        var latestText = ""
        try {
            activeProvider.streamSynthesizedResponse(
                request = request,
                plan = plan,
                modelId = activeModel.modelId
            ).collect { accumulated ->
                latestText = accumulated
                emit(BrainStreamEvent.TextChunk(accumulated))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (streamError: Exception) {
            val isAppCheckErr = streamError is AppCheckDiagnosticException || isAppCheckTokenError(streamError)
            val streamCandidates = if (isAppCheckErr) {
                fallbackQueue.filter { it.providerId != ProviderId.GOOGLE_GEMINI }
            } else {
                registry.reportProviderFailure(activeProvider.descriptor.providerId, streamError)
                fallbackQueue.filter {
                    !(it.providerId == activeProvider.descriptor.providerId && it.modelId == activeModel.modelId)
                }
            }

            if (latestText.isNotBlank() || streamCandidates.isEmpty()) {
                throw streamError
            }

            var recovered = false
            var lastErr: Exception = streamError
            for (candidate in streamCandidates) {
                val candProvider = registry.getLiveProvider(candidate.providerId) ?: continue
                try {
                    candProvider.streamSynthesizedResponse(
                        request = request,
                        plan = plan,
                        modelId = candidate.modelId
                    ).collect { accumulated ->
                        latestText = accumulated
                        emit(BrainStreamEvent.TextChunk(accumulated))
                    }
                    activeProvider = candProvider
                    activeModel = candidate
                    recovered = true
                    break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (candErr: Exception) {
                    registry.reportProviderFailure(candProvider.descriptor.providerId, candErr)
                    lastErr = candErr
                }
            }
            if (!recovered) throw lastErr
        }

        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        val promptTokens = TokenAndCostTracker.estimateTokens(request.prompt + "\n" + request.projectContextSummary)
        val completionTokens = TokenAndCostTracker.estimateTokens(latestText)
        val costMicros = TokenAndCostTracker.computeCostUsdMicros(
            modelId = activeModel.modelId,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isFreeTierOverride = activeModel.isFreeTier && activeModel.providerId != ProviderId.GOOGLE_GEMINI
        )

        onPhaseChanged(BrainActivityPhase.READY)
        emit(
            BrainStreamEvent.Completed(
                fullText = latestText,
                latencyMs = elapsedMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                estimatedCostUsdMicros = costMicros
            )
        )
    }

    private suspend fun executePlanWithFallbackQueue(
        request: BrainGenerationRequest,
        candidates: List<AiModelDescriptor>,
        primaryError: Exception,
        onChosen: (AiModelProvider, AiModelDescriptor, String) -> Unit
    ): StructuredOrchestrationPlan {
        var lastErr: Exception = primaryError
        for (candidate in candidates) {
            val candProvider = registry.getLiveProvider(candidate.providerId)
                ?: registry.getAnyProvider(candidate.providerId)
                ?: continue
            try {
                val result = candProvider.analyzeAndPlan(request, candidate.modelId)
                val reason = "Auto-Fallback to ${candidate.displayName} (${candidate.providerId.displayName}) after primary limit/outage"
                onChosen(candProvider, candidate, reason)
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                registry.reportProviderFailure(candidate.providerId, err)
                lastErr = err
            }
        }
        throw lastErr
    }

    fun buildProjectMemoryContext(
        project: BrainProject?,
        recentMessages: List<BrainMessage>
    ): String {
        if (project == null) {
            return "No active project selected yet; analyze prompt as a new candidate Super Brain project."
        }
        return buildString {
            appendLine("Project Name: ${project.name}")
            appendLine("Package Name: ${project.packageName}")
            appendLine("Target Framework: ${project.targetFramework}")
            appendLine("Status: ${project.status}")
            if (project.requirementsSummary.isNotBlank()) {
                appendLine("Stored Requirements: ${project.requirementsSummary.take(1200)}")
            }
            if (project.architectureSummary.isNotBlank()) {
                appendLine("Architecture Summary: ${project.architectureSummary.take(1000)}")
            }
            if (project.completedFeatures.isNotEmpty()) {
                appendLine("Completed Features: ${project.completedFeatures.joinToString(", ")}")
            }
            if (project.pendingFeatures.isNotEmpty()) {
                appendLine("Pending Subtasks: ${project.pendingFeatures.joinToString(", ")}")
            }
            if (recentMessages.isNotEmpty()) {
                appendLine("Stored Message Count: ${recentMessages.size}")
            }
        }.trim()
    }
}
