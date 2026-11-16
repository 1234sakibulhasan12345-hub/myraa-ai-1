package com.example.ai.provider

import kotlinx.coroutines.flow.Flow

interface AiModelProvider {
    val descriptor: AiProviderDescriptor

    fun supports(capability: ModelCapability): Boolean {
        return descriptor.models.any { capability in it.capabilities }
    }

    suspend fun analyzeAndPlan(
        request: BrainGenerationRequest,
        modelId: String
    ): StructuredOrchestrationPlan

    fun streamSynthesizedResponse(
        request: BrainGenerationRequest,
        plan: StructuredOrchestrationPlan,
        modelId: String
    ): Flow<String>

    suspend fun generateRawText(
        prompt: String,
        systemPrompt: String,
        modelId: String,
        backendServerUrl: String = ""
    ): ProviderGenerationOutput {
        throw UnsupportedOperationException("${descriptor.displayName} does not support raw text calls in this configuration.")
    }
}
