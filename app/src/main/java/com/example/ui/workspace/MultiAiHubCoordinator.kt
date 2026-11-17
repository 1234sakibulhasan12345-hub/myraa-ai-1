package com.example.ui.workspace

import android.content.Context
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.MultiAiOrchestrator
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderHttpException
import com.example.ai.provider.ProviderId
import com.example.ai.provider.classifyProviderError
import com.example.core.security.BackendUrlLocalStore
import com.example.core.security.getAppCheckBackendUrl
import com.example.core.security.regenerateAndStoreDebugToken
import com.example.core.security.setAppCheckBackendUrl
import com.example.core.security.verifyOrAutoRegisterAppCheckToken
import com.example.data.model.BrainLanguage
import com.example.data.model.BuildType
import com.example.data.model.UsageLog
import com.example.data.repository.SuperBrainRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MultiAiHubCoordinator(
    private val repository: SuperBrainRepository,
    private val registry: ModelCapabilityRegistry,
    private val multiAiOrchestrator: MultiAiOrchestrator,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<EngineeringStudioUiState>,
    private val catalogState: StateFlow<List<AiProviderDescriptor>>,
    private val onSyncedUrlUpdated: (String) -> Unit,
    private val backendUrlLocalStore: BackendUrlLocalStore? = null,
    private val currentUserId: String = ""
) {
    fun autoBootstrapAppCheck(urlOverride: String? = null) {
        val targetUrl = (urlOverride ?: uiState.value.backendUrlDraft).trim()
        setAppCheckBackendUrl(targetUrl)
        if (uiState.value.isVerifyingAppCheck) return
        scope.launch {
            uiState.update { it.copy(isVerifyingAppCheck = true) }
            val result = verifyOrAutoRegisterAppCheckToken(
                backendUrlOverride = targetUrl.ifBlank { null },
                forceAutoRegister = false
            )
            registry.refreshSnapshots()
            uiState.update { s ->
                s.copy(
                    isVerifyingAppCheck = false,
                    statusMessage = if (result.isSuccess && targetUrl.isNotBlank()) {
                        "Firebase App Check automatically registered & verified!"
                    } else {
                        s.statusMessage
                    }
                )
            }
        }
    }

    fun saveBackendUrl(
        url: String,
        buildType: BuildType = uiState.value.preferredBuildType,
        prefix: String = uiState.value.defaultPackagePrefix
    ) {
        saveBackendConfiguration(
            url = url,
            buildType = buildType,
            prefix = prefix,
            requireNonBlankUrl = true
        )
    }

    fun testBackendConnection(urlOverride: String? = null) {
        val targetUrl = (urlOverride ?: uiState.value.backendUrlDraft).trim().trimEnd('/')
        if (targetUrl.isBlank()) {
            registry.resetExternalProvidersDisconnected(
                "Backend URL Missing. Save a Remote Backend Server URL above."
            )
            uiState.update {
                it.copy(
                    isTestingBackendConnection = false,
                    isProbingBackend = false,
                    backendConnectionStatus = BackendConnectionStatus.NOT_CONFIGURED,
                    backendConnectionDetail = "Enter and save a valid HTTPS Backend Server URL first.",
                    backendAuthDiagStatus = "Not checked (Backend URL missing)",
                    backendRegistrarDiagStatus = "Not checked",
                    backendHealthLatencyMs = null,
                    errorMessage = "Backend URL Missing: Enter and save your server URL above."
                )
            }
            return
        }

        val validationError = BackendAiProxyClient.validateBackendUrlError(targetUrl)
        if (validationError != null) {
            uiState.update {
                it.copy(
                    isTestingBackendConnection = false,
                    isProbingBackend = false,
                    backendConnectionStatus = BackendConnectionStatus.INVALID_URL,
                    backendConnectionDetail = validationError,
                    backendAuthDiagStatus = "Skipped (Invalid URL)",
                    backendRegistrarDiagStatus = "Not checked",
                    backendHealthLatencyMs = null,
                    errorMessage = validationError
                )
            }
            return
        }

        if (uiState.value.isTestingBackendConnection) return

        scope.launch {
            runHealthAndProviderCheck(targetUrl, updateProbeSummary = true)
        }
    }

    fun verifyBackendAndProviders(urlOverride: String? = null) {
        val url = (urlOverride ?: uiState.value.backendUrlDraft).trim().trimEnd('/')
        setAppCheckBackendUrl(url)
        if (url.isBlank()) {
            registry.resetExternalProvidersDisconnected(
                "Missing API Key & Backend URL. Configure Backend URL & keys in Settings."
            )
            uiState.update {
                it.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = BackendConnectionStatus.NOT_CONFIGURED,
                    backendConnectionDetail = "No Remote Backend Server URL configured yet.",
                    backendAuthDiagStatus = "Waiting for Backend URL",
                    backendRegistrarDiagStatus = "Not checked",
                    backendHealthLatencyMs = null,
                    lastProbeSummary = "Gemini Free Tier ready via App Check. External Free providers require Backend URL & API keys."
                )
            }
            return
        }

        val validationError = BackendAiProxyClient.validateBackendUrlError(url)
        if (validationError != null) {
            uiState.update {
                it.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = BackendConnectionStatus.INVALID_URL,
                    backendConnectionDetail = validationError,
                    backendAuthDiagStatus = "Skipped (Invalid URL)",
                    backendRegistrarDiagStatus = "Not checked",
                    backendHealthLatencyMs = null,
                    errorMessage = validationError
                )
            }
            return
        }

        scope.launch {
            runHealthAndProviderCheck(url, updateProbeSummary = true)
        }
    }

    private suspend fun runHealthAndProviderCheck(
        targetUrl: String,
        updateProbeSummary: Boolean
    ) {
        uiState.update {
            it.copy(
                isProbingBackend = true,
                isTestingBackendConnection = true,
                backendConnectionStatus = BackendConnectionStatus.TESTING,
                backendConnectionDetail = "Testing /health at $targetUrl...",
                backendAuthDiagStatus = "Requesting Firebase ID token...",
                backendRegistrarDiagStatus = "Pending /health...",
                errorMessage = null
            )
        }

        val healthResult = multiAiOrchestrator.checkBackendHealth(targetUrl)
        healthResult.onFailure { err ->
            val classified = classifyProviderError(err)
            val isOffline = classified == ProviderConnectionStatus.OFFLINE
            val detail = if (isOffline) {
                "Backend Offline: ${err.localizedMessage ?: "Could not reach $targetUrl"}"
            } else {
                "Backend health check failed: ${err.localizedMessage ?: "Unexpected server response"}"
            }
            registry.resetExternalProvidersDisconnected(detail)
            uiState.update {
                it.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = if (isOffline) {
                        BackendConnectionStatus.OFFLINE
                    } else {
                        BackendConnectionStatus.ERROR
                    },
                    backendConnectionDetail = detail,
                    backendAuthDiagStatus = if (isOffline) "Skipped (Backend unreachable)" else "Blocked (/health failed)",
                    backendRegistrarDiagStatus = "Unreachable",
                    backendHealthLatencyMs = null,
                    backendLastCheckedEpochMs = System.currentTimeMillis(),
                    probeStates = emptyList(),
                    lastProbeSummary = if (updateProbeSummary) detail else it.lastProbeSummary,
                    errorMessage = detail
                )
            }
            return
        }

        val health = healthResult.getOrThrow()
        if (!health.status.equals("ok", ignoreCase = true)) {
            val detail = "Backend responded with non-ok health status: ${health.status}"
            registry.resetExternalProvidersDisconnected(detail)
            uiState.update {
                it.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = BackendConnectionStatus.ERROR,
                    backendConnectionDetail = detail,
                    backendAuthDiagStatus = "Aborted (Incompatible /health response)",
                    backendRegistrarDiagStatus = "Incompatible service (${health.service})",
                    backendHealthLatencyMs = health.latencyMs,
                    backendLastCheckedEpochMs = System.currentTimeMillis(),
                    probeStates = emptyList(),
                    errorMessage = detail
                )
            }
            return
        }

        val registrarResult = multiAiOrchestrator.fetchBackendRegistrarStatus(targetUrl)
        val registrarInfo = registrarResult.getOrNull()
        val registrarErr = registrarResult.exceptionOrNull() as? ProviderHttpException

        val probeResult = multiAiOrchestrator.refreshExternalProviderStatuses(targetUrl)
        val nowCheckedMs = System.currentTimeMillis()
        val registrarSummary = when {
            registrarInfo != null && registrarInfo.configured ->
                "Active (${registrarInfo.credentialMode})"
            registrarInfo != null ->
                "Inactive (${registrarInfo.reason.ifBlank { registrarInfo.credentialMode }})"
            health.appCheckAutoRegistrarConfigured ->
                "Configured (/health verified)"
            else -> "Unavailable"
        }

        probeResult.onSuccess { probes ->
            val freeProbes = probes.filter { it.freeTier }
            val connectedFree = freeProbes.count { it.status.isOperational }
            val authNote = if (registrarInfo?.tokenRefreshedOnRetry == true) {
                "Verified (Bearer JWT refreshed on retry)"
            } else {
                "Verified (Firebase ID Bearer JWT)"
            }
            uiState.update { s ->
                val clearedFeedback = s.providerVaultFeedback.filterValues { fb ->
                    fb.status != ProviderKeyVaultStatus.BACKEND_OFFLINE &&
                        fb.status != ProviderKeyVaultStatus.BACKEND_URL_MISSING
                }
                s.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = BackendConnectionStatus.CONNECTED,
                    backendConnectionDetail = "Connected to ${health.service} (/health ok • ${health.latencyMs}ms) • $connectedFree/${freeProbes.size} Free providers active",
                    backendAuthDiagStatus = authNote,
                    backendRegistrarDiagStatus = registrarSummary,
                    backendHealthLatencyMs = health.latencyMs,
                    backendLastCheckedEpochMs = nowCheckedMs,
                    probeStates = probes,
                    providerVaultFeedback = clearedFeedback,
                    lastProbeSummary = "Verified Free Proxy ($connectedFree/${freeProbes.size} Free external providers live & verified).",
                    statusMessage = "Backend connected (${health.service}): $connectedFree/${freeProbes.size} external Free provider(s) active."
                )
            }
        }.onFailure { err ->
            val classified = classifyProviderError(err)
            val httpEx = generateSequence(err) { it.cause }
                .filterIsInstance<ProviderHttpException>()
                .firstOrNull() ?: registrarErr
            val isOffline = classified == ProviderConnectionStatus.OFFLINE
            val isAuthFailure = httpEx?.errorCode == "FIREBASE_AUTH_REQUIRED" || httpEx?.httpCode == 401
            val detail = err.localizedMessage ?: "Authenticated provider probe failed."
            uiState.update { s ->
                s.copy(
                    isProbingBackend = false,
                    isTestingBackendConnection = false,
                    backendConnectionStatus = when {
                        isOffline -> BackendConnectionStatus.OFFLINE
                        isAuthFailure -> BackendConnectionStatus.ERROR
                        else -> BackendConnectionStatus.CONNECTED
                    },
                    backendConnectionDetail = when {
                        isOffline -> "Backend Offline: $detail"
                        isAuthFailure -> "Firebase Auth required/rejected (401): $detail"
                        else -> "Connected to ${health.service} (/health ok • ${health.latencyMs}ms) • Provider check note: $detail"
                    },
                    backendAuthDiagStatus = when {
                        isOffline -> "Network unreachable"
                        isAuthFailure -> "Auth failed (${httpEx?.message ?: "401 Bearer rejected"})"
                        else -> "Auth checked (${detail.take(60)})"
                    },
                    backendRegistrarDiagStatus = registrarSummary,
                    backendHealthLatencyMs = health.latencyMs,
                    backendLastCheckedEpochMs = nowCheckedMs,
                    probeStates = emptyList(),
                    lastProbeSummary = "Server /health OK, provider probe note: $detail",
                    statusMessage = if (!isOffline && !isAuthFailure) "Backend /health reachable (${health.service})." else null,
                    errorMessage = detail
                )
            }
        }
    }

    fun saveProviderApiKey(
        providerId: ProviderId,
        apiKey: String,
        accountId: String = ""
    ) {
        val url = uiState.value.backendUrlDraft.trim().trimEnd('/')
        val cleanKey = apiKey.trim()
        val cleanAccountId = accountId.trim()

        if (url.isBlank() || BackendAiProxyClient.validateBackendUrlError(url) != null) {
            val msg = "Backend URL Missing: Save a valid Remote Backend Server URL above first."
            uiState.update {
                it.copy(
                    errorMessage = msg,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.BACKEND_URL_MISSING,
                            message = msg
                        )
                        )
                )
            }
            return
        }

        if (cleanKey.isBlank()) {
            val msg = "Missing API Key: Enter an API key for ${providerId.displayName}."
            uiState.update {
                it.copy(
                    errorMessage = msg,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.MISSING_API_KEY,
                            message = msg
                        )
                        )
                )
            }
            return
        }

        if (cleanKey.length < 8 || cleanKey.any { it.isWhitespace() }) {
            val msg = "Invalid API Key format: Enter at least 8 characters without spaces."
            uiState.update {
                it.copy(
                    errorMessage = msg,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.INVALID_API_KEY,
                            message = msg
                        )
                        )
                )
            }
            return
        }

        if (providerId.requiresAccountId && cleanAccountId.isBlank()) {
            val msg = "Cloudflare Account ID is required along with Cloudflare API Token."
            uiState.update {
                it.copy(
                    errorMessage = msg,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.SAVE_FAILED,
                            message = msg
                        )
                        )
                )
            }
            return
        }

        val prefixMismatch = BackendAiProxyClient.detectProviderKeyPrefixMismatch(providerId, cleanKey)
        if (prefixMismatch != null) {
            uiState.update {
                it.copy(
                    errorMessage = prefixMismatch,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.SAVE_FAILED,
                            message = prefixMismatch
                        )
                        )
                )
            }
            return
        }

        if (uiState.value.keyMutationBusyProviderId != null) return

        scope.launch {
            uiState.update {
                it.copy(
                    keyMutationBusyProviderId = providerId,
                    errorMessage = null,
                    statusMessage = null,
                    providerVaultFeedback = it.providerVaultFeedback + (
                        providerId.wireId to ProviderVaultFeedback(
                            status = ProviderKeyVaultStatus.VERIFICATION_IN_PROGRESS,
                            message = "Encrypting in vault & verifying live against ${providerId.displayName}..."
                        )
                        )
                )
            }
            val result = multiAiOrchestrator.saveAndVerifyProviderKey(
                backendServerUrl = url,
                providerId = providerId,
                apiKey = cleanKey,
                accountId = cleanAccountId
            )
            result.onSuccess { probeState ->
                val isInvalidKey = probeState.statusCode.equals("INVALID_API_KEY", ignoreCase = true) ||
                    probeState.statusCode.equals("INVALID_KEY", ignoreCase = true) ||
                    probeState.errorCategory.equals("INVALID_KEY", ignoreCase = true)
                val isNetworkOffline = probeState.statusCode.equals("OFFLINE", ignoreCase = true) ||
                    probeState.statusCode.equals("NETWORK_ERROR", ignoreCase = true) ||
                    probeState.errorCategory.equals("NETWORK_ERROR", ignoreCase = true) ||
                    probeState.status == ProviderConnectionStatus.OFFLINE
                val feedback = when {
                    probeState.status.isOperational -> ProviderVaultFeedback(
                        status = ProviderKeyVaultStatus.VERIFIED,
                        message = "Verified & encrypted in backend vault (${probeState.maskedKeyHint}).",
                        clearDraftToken = System.nanoTime()
                    )
                    isInvalidKey -> ProviderVaultFeedback(
                        status = ProviderKeyVaultStatus.INVALID_API_KEY,
                        message = probeState.diagnosticMessage.ifBlank {
                            "${providerId.displayName} rejected this API key (401/403). Key was not saved."
                        }
                    )
                    isNetworkOffline -> ProviderVaultFeedback(
                        status = ProviderKeyVaultStatus.BACKEND_OFFLINE,
                        message = probeState.diagnosticMessage.ifBlank {
                            "Network error reaching ${providerId.displayName} endpoint."
                        }
                    )
                    else -> ProviderVaultFeedback(
                        status = ProviderKeyVaultStatus.SAVE_FAILED,
                        message = probeState.diagnosticMessage.ifBlank {
                            "Verification returned ${probeState.statusCode}."
                        }
                    )
                }

                uiState.update { s ->
                    val updatedProbes = s.probeStates
                        .filterNot { it.providerId == probeState.providerId } + probeState
                    val backendStatusNext = if (feedback.status == ProviderKeyVaultStatus.VERIFIED) {
                        BackendConnectionStatus.CONNECTED
                    } else {
                        s.backendConnectionStatus
                    }
                    s.copy(
                        keyMutationBusyProviderId = null,
                        backendConnectionStatus = backendStatusNext,
                        probeStates = updatedProbes,
                        providerVaultFeedback = s.providerVaultFeedback + (providerId.wireId to feedback),
                        statusMessage = if (feedback.status == ProviderKeyVaultStatus.VERIFIED) {
                            "${providerId.displayName} key encrypted on backend & verified LIVE (${probeState.maskedKeyHint})."
                        } else null,
                        errorMessage = if (feedback.status != ProviderKeyVaultStatus.VERIFIED) {
                            "${providerId.displayName}: ${feedback.message}"
                        } else null
                    )
                }
            }.onFailure { err ->
                val classified = classifyProviderError(err)
                val httpEx = generateSequence(err) { it.cause }
                    .filterIsInstance<ProviderHttpException>()
                    .firstOrNull()
                val errMsg = err.localizedMessage
                    ?: "Failed to save encrypted key for ${providerId.displayName}."
                val failureStatus = when {
                    classified == ProviderConnectionStatus.OFFLINE -> ProviderKeyVaultStatus.BACKEND_OFFLINE
                    httpEx?.errorCode == "INVALID_API_KEY" || httpEx?.errorCode == "INVALID_KEY" ->
                        ProviderKeyVaultStatus.INVALID_API_KEY
                    else -> ProviderKeyVaultStatus.SAVE_FAILED
                }

                uiState.update { s ->
                    s.copy(
                        keyMutationBusyProviderId = null,
                        backendConnectionStatus = if (failureStatus == ProviderKeyVaultStatus.BACKEND_OFFLINE) {
                            BackendConnectionStatus.OFFLINE
                        } else {
                            s.backendConnectionStatus
                        },
                        providerVaultFeedback = s.providerVaultFeedback + (
                            providerId.wireId to ProviderVaultFeedback(
                                status = failureStatus,
                                message = errMsg
                            )
                            ),
                        errorMessage = errMsg
                    )
                }
            }
        }
    }

    fun deleteProviderApiKey(providerId: ProviderId) {
        val url = uiState.value.backendUrlDraft.trim()
        if (url.isBlank()) {
            uiState.update { it.copy(errorMessage = "Remote Backend Server URL is not configured.") }
            return
        }
        if (uiState.value.keyMutationBusyProviderId != null) return

        scope.launch {
            uiState.update {
                it.copy(
                    keyMutationBusyProviderId = providerId,
                    errorMessage = null,
                    statusMessage = null
                )
            }
            val result = multiAiOrchestrator.deleteProviderKey(
                backendServerUrl = url,
                providerId = providerId
            )
            result.onSuccess { probeState ->
                uiState.update { s ->
                    val updatedProbes = s.probeStates
                        .filterNot { it.providerId == probeState.providerId } + probeState
                    s.copy(
                        keyMutationBusyProviderId = null,
                        probeStates = updatedProbes,
                        providerVaultFeedback = s.providerVaultFeedback + (
                            providerId.wireId to ProviderVaultFeedback(
                                status = ProviderKeyVaultStatus.MISSING_API_KEY,
                                message = "Removed stored key from encrypted vault."
                            )
                            ),
                        statusMessage = "Removed stored key for ${providerId.displayName}."
                    )
                }
            }.onFailure { err ->
                uiState.update {
                    it.copy(
                        keyMutationBusyProviderId = null,
                        errorMessage = err.localizedMessage ?: "Failed to delete key for ${providerId.displayName}."
                    )
                }
            }
        }
    }

    fun verifyAndRefreshAppCheckToken(forceAutoRegister: Boolean = false) {
        if (uiState.value.isVerifyingAppCheck) return
        val targetUrl = uiState.value.backendUrlDraft.trim().ifBlank { getAppCheckBackendUrl() }
        scope.launch {
            uiState.update { it.copy(isVerifyingAppCheck = true, errorMessage = null) }
            val result = verifyOrAutoRegisterAppCheckToken(
                backendUrlOverride = targetUrl.ifBlank { null },
                forceAutoRegister = forceAutoRegister
            )
            registry.refreshSnapshots()
            result.onSuccess {
                uiState.update {
                    it.copy(
                        isVerifyingAppCheck = false,
                        statusMessage = "Firebase App Check token verified and active!"
                    )
                }
            }.onFailure { err ->
                uiState.update {
                    it.copy(
                        isVerifyingAppCheck = false,
                        errorMessage = err.localizedMessage ?: "Firebase App Check token verification failed."
                    )
                }
            }
        }
    }

    fun regenerateDebugToken(context: Context) {
        val newSecret = regenerateAndStoreDebugToken(context)
        val hasBackend = uiState.value.backendUrlDraft.isNotBlank() || getAppCheckBackendUrl().isNotBlank()
        registry.refreshSnapshots()
        uiState.update {
            it.copy(
                statusMessage = if (hasBackend) {
                    "Generated fresh debug token. Automatically registering with Firebase..."
                } else {
                    "Generated new debug token: $newSecret"
                }
            )
        }
        if (hasBackend) {
            verifyAndRefreshAppCheckToken(forceAutoRegister = true)
        }
    }

    fun saveBackendConfiguration(
        url: String,
        buildType: BuildType,
        prefix: String,
        requireNonBlankUrl: Boolean = false
    ) {
        val cleanUrl = url.trim().trimEnd('/')
        if (cleanUrl.isNotBlank() || requireNonBlankUrl) {
            val validationError = BackendAiProxyClient.validateBackendUrlError(cleanUrl)
            if (validationError != null) {
                uiState.update {
                    it.copy(
                        isSavingBackendUrl = false,
                        backendConnectionStatus = if (cleanUrl.isBlank()) {
                            BackendConnectionStatus.NOT_CONFIGURED
                        } else {
                            BackendConnectionStatus.INVALID_URL
                        },
                        backendConnectionDetail = validationError,
                        errorMessage = validationError
                    )
                }
                return
            }
        }

        // Persist non-secret server URL locally right away so Vault & Test Connection unlock
        // even if Firestore App Check / offline startup delays cloud sync.
        backendUrlLocalStore?.writeBackendUrl(currentUserId, cleanUrl)
        onSyncedUrlUpdated(cleanUrl)
        setAppCheckBackendUrl(cleanUrl)

        uiState.update { s ->
            val updatedFeedback = s.providerVaultFeedback.filterValues { fb ->
                fb.status != ProviderKeyVaultStatus.BACKEND_URL_MISSING
            }
            s.copy(
                backendUrlDraft = cleanUrl,
                preferredBuildType = buildType,
                defaultPackagePrefix = prefix,
                isSavingBackendUrl = true,
                backendConnectionStatus = if (cleanUrl.isBlank()) {
                    BackendConnectionStatus.NOT_CONFIGURED
                } else {
                    BackendConnectionStatus.SAVING
                },
                backendConnectionDetail = if (cleanUrl.isBlank()) {
                    "Backend URL cleared."
                } else {
                    "Saving $cleanUrl & testing live connection..."
                },
                providerVaultFeedback = updatedFeedback,
                errorMessage = null
            )
        }

        scope.launch {
            val firestoreRes = repository.updateBackendConfiguration(cleanUrl, buildType, prefix)
            uiState.update { s ->
                s.copy(
                    isSavingBackendUrl = false,
                    statusMessage = if (firestoreRes.isSuccess) {
                        if (cleanUrl.isBlank()) "Cleared backend URL." else "Saved Backend URL ($cleanUrl)."
                    } else {
                        "Saved Backend URL locally ($cleanUrl). Cloud sync pending: ${firestoreRes.exceptionOrNull()?.localizedMessage ?: "retry after App Check"}"
                    }
                )
            }
            if (cleanUrl.isNotBlank()) {
                autoBootstrapAppCheck(cleanUrl)
                runHealthAndProviderCheck(cleanUrl, updateProbeSummary = true)
            } else {
                verifyBackendAndProviders("")
            }
        }
    }

    fun runParallelComparison(language: BrainLanguage, activeProjectId: String?) {
        val prompt = uiState.value.comparisonPrompt.trim()
        if (prompt.isBlank() || uiState.value.isComparing) return
        val selectedKeys = uiState.value.selectedCompareModelKeys
        val targetModels = catalogState.value.flatMap { it.models }
            .filter { it.isFreeTier && "${it.providerId.wireId}:${it.modelId}" in selectedKeys }
            .ifEmpty { registry.getAllLiveFreeModels().take(2) }

        val backendUrl = uiState.value.backendUrlDraft
        scope.launch {
            uiState.update { it.copy(isComparing = true, consensusResult = null, errorMessage = null) }
            try {
                val results = multiAiOrchestrator.runParallelComparison(prompt, targetModels, language, backendUrl)
                val projId = activeProjectId ?: "model_hub_compare"
                results.forEach { r ->
                    if (r.errorMessage == null) {
                        repository.recordUsageLog(
                            UsageLog(
                                projectId = projId,
                                providerId = r.providerId.wireId,
                                modelId = r.modelId,
                                taskRole = "PARALLEL_COMPARISON",
                                inputLanguage = language.wireValue,
                                estimatedInputChars = prompt.length.toLong(),
                                estimatedOutputChars = r.content.length.toLong(),
                                latencyMs = r.latencyMs,
                                succeeded = true,
                                promptTokens = r.promptTokens,
                                completionTokens = r.completionTokens,
                                estimatedCostUsdMicros = r.estimatedCostUsdMicros
                            )
                        )
                    }
                }
                uiState.update { it.copy(isComparing = false, parallelResponses = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update { it.copy(isComparing = false, errorMessage = e.localizedMessage ?: "Parallel comparison failed.") }
            }
        }
    }

    fun synthesizeConsensus(language: BrainLanguage, activeProjectId: String?) {
        val prompt = uiState.value.comparisonPrompt.trim()
        val current = uiState.value.parallelResponses
        if (current.isEmpty() || uiState.value.isComparing) return

        scope.launch {
            uiState.update { it.copy(isComparing = true, errorMessage = null) }
            try {
                val consensus = multiAiOrchestrator.synthesizeConsensus(
                    originalPrompt = prompt,
                    responses = current,
                    languagePreference = language,
                    backendServerUrl = uiState.value.backendUrlDraft
                )
                repository.recordUsageLog(
                    UsageLog(
                        projectId = activeProjectId ?: "model_hub_consensus",
                        providerId = ProviderId.GOOGLE_GEMINI.wireId,
                        modelId = consensus.synthesizedByModelId,
                        taskRole = "CONSENSUS_SYNTHESIS",
                        inputLanguage = language.wireValue,
                        latencyMs = consensus.totalLatencyMs,
                        succeeded = true,
                        estimatedCostUsdMicros = consensus.totalEstimatedCostUsdMicros
                    )
                )
                uiState.update { it.copy(isComparing = false, consensusResult = consensus) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update { it.copy(isComparing = false, errorMessage = e.localizedMessage ?: "Consensus synthesis failed.") }
            }
        }
    }
}
