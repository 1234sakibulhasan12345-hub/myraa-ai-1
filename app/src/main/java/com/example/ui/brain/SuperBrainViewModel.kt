package com.example.ui.brain

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.brain.MasterAiBrain
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.BrainStreamEvent
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ModelRoutingDecision
import com.example.ai.provider.ProviderId
import com.example.ai.provider.StructuredOrchestrationPlan
import com.example.core.security.AppCheckDiagnosticStatus
import com.example.core.security.appCheckDiagnosticFlow
import com.example.core.security.getAppCheckBackendUrl
import com.example.core.security.regenerateAndStoreDebugToken
import com.example.core.security.verifyOrAutoRegisterAppCheckToken
import com.example.data.model.BrainActivityPhase
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import com.example.data.model.MessageRole
import com.example.data.model.ProjectStatus
import com.example.data.model.RoutingPolicy
import com.example.data.model.TargetFramework
import com.example.data.model.UsageLog
import com.example.data.model.UserWorkspace
import com.example.data.repository.SuperBrainRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SuperBrainVM"
private const val STOP_TIMEOUT_MS = 5000L

sealed interface StreamUiState<out T> {
    data object Loading : StreamUiState<Nothing>
    data class Success<T>(val data: T) : StreamUiState<T>
    data class Error(val message: String) : StreamUiState<Nothing>
}

data class BrainConsoleUiState(
    val activityPhase: BrainActivityPhase = BrainActivityPhase.IDLE,
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val latestPlan: StructuredOrchestrationPlan? = null,
    val latestRouting: ModelRoutingDecision? = null,
    val errorMessage: String? = null,
    val promptDraft: String = "",
    val attachedBitmap: Bitmap? = null,
    val selectedLanguage: BrainLanguage = BrainLanguage.AUTO,
    val selectedRoutingPolicy: RoutingPolicy = RoutingPolicy.AUTO_BALANCED,
    val selectedPreferredProviderId: ProviderId? = null,
    val activeProjectId: String? = null,
    val statusBanner: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SuperBrainViewModel(
    private val repository: SuperBrainRepository,
    private val masterAiBrain: MasterAiBrain,
    private val registry: ModelCapabilityRegistry,
    private val currentUserId: String,
    private val currentUserDisplayName: String
) : ViewModel() {

    private val _consoleState = MutableStateFlow(BrainConsoleUiState())
    val consoleState: StateFlow<BrainConsoleUiState> = _consoleState.asStateFlow()

    val providerCatalog: List<AiProviderDescriptor> = registry.catalog
    val freeTierCatalogState: StateFlow<List<AiProviderDescriptor>> = registry.freeTierCatalogState
    val appCheckStatus: StateFlow<AppCheckDiagnosticStatus> = appCheckDiagnosticFlow

    val workspaceState: StateFlow<StreamUiState<UserWorkspace?>> =
        repository.observeWorkspace(currentUserId)
            .map<UserWorkspace?, StreamUiState<UserWorkspace?>> { workspace ->
                if (workspace != null) {
                    _consoleState.update { state ->
                        state.copy(
                            selectedLanguage = BrainLanguage.fromWire(workspace.preferredLanguage),
                            selectedRoutingPolicy = RoutingPolicy.fromWire(workspace.routingPolicy),
                            activeProjectId = state.activeProjectId ?: workspace.activeProjectId
                        )
                    }
                }
                StreamUiState.Success(workspace)
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing workspace", rootError)
                emit(StreamUiState.Error(rootError.message ?: "Failed to load workspace"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = StreamUiState.Loading
            )

    val projectsState: StateFlow<StreamUiState<List<BrainProject>>> =
        repository.observeProjects(currentUserId)
            .map<List<BrainProject>, StreamUiState<List<BrainProject>>> { projects ->
                val currentActiveId = _consoleState.value.activeProjectId
                if (currentActiveId == null && projects.isNotEmpty()) {
                    _consoleState.update { it.copy(activeProjectId = projects.first().id) }
                } else if (currentActiveId != null && projects.none { it.id == currentActiveId }) {
                    _consoleState.update { it.copy(activeProjectId = projects.firstOrNull()?.id) }
                }
                StreamUiState.Success(projects)
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing projects", rootError)
                emit(StreamUiState.Error(rootError.message ?: "Failed to load projects"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = StreamUiState.Loading
            )

    private val activeProjectIdFlow: StateFlow<String?> = _consoleState
        .map { it.activeProjectId }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val activeMessagesState: StateFlow<StreamUiState<List<BrainMessage>>> =
        activeProjectIdFlow
            .flatMapLatest { projectId ->
                if (projectId.isNullOrBlank()) {
                    flowOf<StreamUiState<List<BrainMessage>>>(StreamUiState.Success(emptyList()))
                } else {
                    repository.observeMessages(currentUserId, projectId)
                        .map<List<BrainMessage>, StreamUiState<List<BrainMessage>>> {
                            StreamUiState.Success(it)
                        }
                        .catch { error ->
                            val firestoreError = unwrapFirestoreException(error)
                            if (firestoreError == null && error is CancellationException) throw error
                            val rootError = firestoreError ?: error
                            Log.w(TAG, "Error observing project messages", rootError)
                            emit(StreamUiState.Error(rootError.message ?: "Failed to load messages"))
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = StreamUiState.Success(emptyList())
            )

    val usageLogsState: StateFlow<StreamUiState<List<UsageLog>>> =
        repository.observeUsageLogs(currentUserId)
            .map<List<UsageLog>, StreamUiState<List<UsageLog>>> { StreamUiState.Success(it) }
            .catch { error ->
                val firestoreError = unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing usage logs", rootError)
                emit(StreamUiState.Error(rootError.message ?: "Failed to load usage telemetry"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = StreamUiState.Loading
            )

    val activeProject: StateFlow<BrainProject?> =
        combine(projectsState, activeProjectIdFlow) { projState, activeId ->
            val list = (projState as? StreamUiState.Success)?.data.orEmpty()
            list.firstOrNull { it.id == activeId }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            repository.ensureWorkspaceExists(currentUserDisplayName)
                .onFailure { error ->
                    Log.e(TAG, "Failed to initialize user workspace", error)
                }
        }
    }

    fun updatePromptDraft(value: String) {
        _consoleState.update { it.copy(promptDraft = value) }
    }

    fun attachBitmap(bitmap: Bitmap?) {
        _consoleState.update { it.copy(attachedBitmap = bitmap) }
    }

    fun selectPreferredProvider(providerId: ProviderId?) {
        _consoleState.update { it.copy(selectedPreferredProviderId = providerId) }
    }

    fun clearError() {
        _consoleState.update {
            it.copy(
                errorMessage = null,
                activityPhase = if (it.activityPhase == BrainActivityPhase.ERROR) {
                    BrainActivityPhase.IDLE
                } else {
                    it.activityPhase
                }
            )
        }
    }

    fun clearStatusBanner() {
        _consoleState.update { it.copy(statusBanner = null) }
    }

    fun verifyAndRefreshAppCheck(forceAutoRegister: Boolean = false) {
        val currentBackendUrl = (workspaceState.value as? StreamUiState.Success)
            ?.data?.backendServiceUrl?.trim().orEmpty()
            .ifBlank { getAppCheckBackendUrl() }
        viewModelScope.launch {
            verifyOrAutoRegisterAppCheckToken(
                backendUrlOverride = currentBackendUrl.ifBlank { null },
                forceAutoRegister = forceAutoRegister
            ).onSuccess {
                registry.refreshSnapshots()
                _consoleState.update {
                    it.copy(
                        statusBanner = "Firebase App Check token verified! Attestation active.",
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                registry.refreshSnapshots()
                _consoleState.update {
                    it.copy(
                        errorMessage = err.localizedMessage ?: "App Check verification failed."
                    )
                }
            }
        }
    }

    fun regenerateDebugToken(context: Context) {
        val newSecret = regenerateAndStoreDebugToken(context)
        val hasBackend = getAppCheckBackendUrl().isNotBlank()
        registry.refreshSnapshots()
        _consoleState.update {
            it.copy(
                statusBanner = if (hasBackend) {
                    "Generated new debug token. Automatically registering with Firebase..."
                } else {
                    "Generated new debug token ($newSecret). Copy and register in Firebase Console or configure MYRAA Backend URL."
                },
                errorMessage = null
            )
        }
        if (hasBackend) {
            verifyAndRefreshAppCheck(forceAutoRegister = true)
        }
    }

    fun updatePreferences(language: BrainLanguage, policy: RoutingPolicy) {
        _consoleState.update {
            it.copy(
                selectedLanguage = language,
                selectedRoutingPolicy = policy
            )
        }
        viewModelScope.launch {
            repository.ensureWorkspaceExists(currentUserDisplayName)
            repository.updateWorkspacePreferences(
                preferredLanguage = language,
                routingPolicy = policy,
                activeProjectId = _consoleState.value.activeProjectId
            ).onFailure { err ->
                Log.e(TAG, "Failed to persist workspace preferences", err)
                _consoleState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Failed to save preferences")
                }
            }
        }
    }

    fun selectProject(projectId: String) {
        _consoleState.update {
            it.copy(
                activeProjectId = projectId,
                errorMessage = null,
                streamingText = "",
                statusBanner = "Resumed project context from Firestore Memory."
            )
        }
        viewModelScope.launch {
            repository.updateWorkspacePreferences(
                preferredLanguage = _consoleState.value.selectedLanguage,
                routingPolicy = _consoleState.value.selectedRoutingPolicy,
                activeProjectId = projectId
            )
        }
    }

    fun createNewProject(
        name: String,
        framework: TargetFramework,
        requirementsSummary: String
    ) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { "Myraa Super Brain Project" }
            val result = repository.createProject(
                BrainProject(
                    name = cleanName,
                    targetFramework = framework.wireValue,
                    requirementsSummary = requirementsSummary.trim(),
                    detectedLanguage = _consoleState.value.selectedLanguage.wireValue,
                    architectureSummary = "Initialized in PART 01 Master AI Brain workspace.",
                    completedFeatures = listOf("Project Memory Initialized"),
                    pendingFeatures = listOf("Generate initial orchestration blueprint"),
                    status = ProjectStatus.PLANNING.wireValue
                )
            )
            result.onSuccess { newId ->
                selectProject(newId)
            }.onFailure { err ->
                Log.e(TAG, "Failed to create project", err)
                _consoleState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Failed to create project")
                }
            }
        }
    }

    fun archiveProject(projectId: String) {
        viewModelScope.launch {
            repository.archiveProject(projectId)
                .onSuccess {
                    _consoleState.update { it.copy(statusBanner = "Project archived and locked.") }
                }
                .onFailure { err ->
                    _consoleState.update {
                        it.copy(errorMessage = err.localizedMessage ?: "Failed to archive project")
                    }
                }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
                .onSuccess {
                    if (_consoleState.value.activeProjectId == projectId) {
                        _consoleState.update { it.copy(activeProjectId = null) }
                    }
                    _consoleState.update { it.copy(statusBanner = "Project removed from Firestore.") }
                }
                .onFailure { err ->
                    _consoleState.update {
                        it.copy(errorMessage = err.localizedMessage ?: "Failed to delete project")
                    }
                }
        }
    }

    fun submitDirective(rawPrompt: String = _consoleState.value.promptDraft) {
        val cleanPrompt = rawPrompt.trim()
        val bitmapSnapshot = _consoleState.value.attachedBitmap
        if (cleanPrompt.isBlank() && bitmapSnapshot == null) return
        if (_consoleState.value.isGenerating) return

        val effectivePrompt = cleanPrompt.ifBlank { "Analyze the attached image and plan implementation steps." }
        val startMs = SystemClock.elapsedRealtime()

        viewModelScope.launch {
            _consoleState.update {
                it.copy(
                    isGenerating = true,
                    errorMessage = null,
                    streamingText = "",
                    promptDraft = "",
                    attachedBitmap = null,
                    activityPhase = BrainActivityPhase.ANALYZING_INTENT
                )
            }

            var targetProjectId: String? = _consoleState.value.activeProjectId
            var selectedModelId = ModelRouter.MODEL_GEMINI_FLASH
            var selectedProviderId = ProviderId.GOOGLE_GEMINI.wireId

            try {
                // Ensure workspace & active project exist in Firestore before attaching messages
                repository.ensureWorkspaceExists(currentUserDisplayName)

                val existingActiveProject = activeProject.value
                if (targetProjectId.isNullOrBlank() || existingActiveProject?.status == ProjectStatus.ARCHIVED.wireValue) {
                    val autoTitle = effectivePrompt
                        .replace("\n", " ")
                        .trim()
                        .take(48)
                        .ifBlank { "Super Brain Session" }
                    targetProjectId = repository.createProject(
                        BrainProject(
                            name = autoTitle,
                            targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
                            requirementsSummary = effectivePrompt,
                            detectedLanguage = _consoleState.value.selectedLanguage.wireValue,
                            architectureSummary = "Analyzing requirements via Master AI Brain...",
                            completedFeatures = listOf("Session Created"),
                            pendingFeatures = listOf("Subtask Decomposition"),
                            status = ProjectStatus.PLANNING.wireValue
                        )
                    ).getOrThrow()
                    _consoleState.update { it.copy(activeProjectId = targetProjectId) }
                    repository.updateWorkspacePreferences(
                        preferredLanguage = _consoleState.value.selectedLanguage,
                        routingPolicy = _consoleState.value.selectedRoutingPolicy,
                        activeProjectId = targetProjectId
                    )
                }

                val confirmedProjectId = checkNotNull(targetProjectId)

                // Persist user directive
                repository.addMessage(
                    confirmedProjectId,
                    BrainMessage(
                        role = MessageRole.USER.wireValue,
                        content = effectivePrompt,
                        detectedLanguage = _consoleState.value.selectedLanguage.wireValue,
                        selectedProviderId = selectedProviderId,
                        selectedModelId = selectedModelId,
                        intentSummary = "User Directive"
                    )
                ).getOrThrow()

                val recentMessages = (activeMessagesState.value as? StreamUiState.Success)?.data.orEmpty()
                val contextSummary = masterAiBrain.buildProjectMemoryContext(
                    project = activeProject.value,
                    recentMessages = recentMessages
                )

                val currentBackendUrl = (workspaceState.value as? StreamUiState.Success)
                    ?.data?.backendServiceUrl.orEmpty()
                val request = BrainGenerationRequest(
                    prompt = effectivePrompt,
                    languagePreference = _consoleState.value.selectedLanguage,
                    routingPolicy = _consoleState.value.selectedRoutingPolicy,
                    projectContextSummary = contextSummary,
                    recentHistory = recentMessages,
                    attachedBitmap = bitmapSnapshot,
                    preferredProviderId = _consoleState.value.selectedPreferredProviderId,
                    backendServerUrl = currentBackendUrl
                )

                var latestPlan: StructuredOrchestrationPlan? = null
                var latestRouting: ModelRoutingDecision? = null

                masterAiBrain.execute(request) { phase ->
                    _consoleState.update { it.copy(activityPhase = phase) }
                }.collect { event ->
                    when (event) {
                        is BrainStreamEvent.PlanReady -> {
                            latestPlan = event.plan
                            latestRouting = event.routing
                            selectedModelId = event.routing.selectedModel.modelId
                            selectedProviderId = event.routing.selectedProvider.wireId
                            _consoleState.update {
                                it.copy(
                                    latestPlan = event.plan,
                                    latestRouting = event.routing
                                )
                            }

                            // Update Project Memory in Firestore when project is editable
                            val projectSnap = activeProject.value
                            if (projectSnap != null && projectSnap.status != ProjectStatus.ARCHIVED.wireValue) {
                                val mergedReqs = if (projectSnap.requirementsSummary.isBlank()) {
                                    effectivePrompt
                                } else {
                                    "${projectSnap.requirementsSummary.take(2400)}\n• $effectivePrompt".take(3900)
                                }
                                repository.updateProjectMemory(
                                    projectSnap.copy(
                                        targetFramework = event.plan.recommendedFramework.wireValue,
                                        requirementsSummary = mergedReqs,
                                        detectedLanguage = event.plan.detectedLanguage.wireValue,
                                        architectureSummary = event.plan.architectureNotes.take(3900),
                                        pendingFeatures = event.plan.subtasks.take(25),
                                        status = ProjectStatus.ACTIVE.wireValue
                                    )
                                )
                            }
                        }

                        is BrainStreamEvent.TextChunk -> {
                            _consoleState.update { it.copy(streamingText = event.accumulatedText) }
                        }

                        is BrainStreamEvent.Completed -> {
                            val plan = latestPlan
                            repository.addMessage(
                                confirmedProjectId,
                                BrainMessage(
                                    role = MessageRole.BRAIN.wireValue,
                                    content = event.fullText,
                                    detectedLanguage = (plan?.detectedLanguage
                                        ?: _consoleState.value.selectedLanguage).wireValue,
                                    selectedProviderId = selectedProviderId,
                                    selectedModelId = selectedModelId,
                                    intentSummary = plan?.intentSummary.orEmpty(),
                                    assignedAgents = plan?.assignedAgents.orEmpty(),
                                    subtasks = plan?.subtasks.orEmpty(),
                                    latencyMs = event.latencyMs
                                )
                            )

                            repository.recordUsageLog(
                                UsageLog(
                                    projectId = confirmedProjectId,
                                    providerId = selectedProviderId,
                                    modelId = selectedModelId,
                                    taskRole = (latestRouting?.taskCategory?.wireValue ?: "MASTER_PLANNING"),
                                    inputLanguage = (plan?.detectedLanguage
                                        ?: _consoleState.value.selectedLanguage).wireValue,
                                    estimatedInputChars = effectivePrompt.length.toLong(),
                                    estimatedOutputChars = event.fullText.length.toLong(),
                                    latencyMs = event.latencyMs,
                                    succeeded = true,
                                    promptTokens = event.promptTokens,
                                    completionTokens = event.completionTokens,
                                    estimatedCostUsdMicros = event.estimatedCostUsdMicros
                                )
                            )

                            _consoleState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    activityPhase = BrainActivityPhase.READY
                                )
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Master AI Brain execution timed out", e)
                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                recordFailureTelemetry(targetProjectId, selectedProviderId, selectedModelId, effectivePrompt.length, elapsed)
                _consoleState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        activityPhase = BrainActivityPhase.ERROR,
                        errorMessage = "Master AI Brain timed out after 60 seconds. Check connection and try again."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Master AI Brain execution failed", e)
                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                recordFailureTelemetry(targetProjectId, selectedProviderId, selectedModelId, effectivePrompt.length, elapsed)
                _consoleState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        activityPhase = BrainActivityPhase.ERROR,
                        errorMessage = e.localizedMessage ?: "AI Brain orchestration failed."
                    )
                }
            }
        }
    }

    private suspend fun recordFailureTelemetry(
        projectId: String?,
        providerId: String,
        modelId: String,
        inputChars: Int,
        latencyMs: Long
    ) {
        runCatching {
            repository.recordUsageLog(
                UsageLog(
                    projectId = projectId ?: "unassigned_session",
                    providerId = providerId,
                    modelId = modelId,
                    taskRole = "MASTER_PLANNING",
                    inputLanguage = _consoleState.value.selectedLanguage.wireValue,
                    estimatedInputChars = inputChars.toLong(),
                    estimatedOutputChars = 0L,
                    latencyMs = latencyMs,
                    succeeded = false
                )
            )
        }
    }

    private fun unwrapFirestoreException(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }
}
