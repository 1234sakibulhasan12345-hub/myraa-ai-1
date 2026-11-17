package com.example.ui.workspace

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.MultiAiOrchestrator
import com.example.ai.provider.ProviderId
import com.example.build.BuildServerClient
import com.example.builder.AppBuilderOrchestrator
import com.example.coding.CodingAgentOrchestrator
import com.example.coding.KotlinAndGradleStaticAnalyzer
import com.example.core.security.AppCheckDiagnosticStatus
import com.example.core.security.BackendUrlLocalStore
import com.example.core.security.appCheckDiagnosticFlow
import com.example.core.security.setAppCheckBackendUrl
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildJob
import com.example.data.model.BuildType
import com.example.data.model.IconStylePreset
import com.example.data.model.ProjectFile
import com.example.data.model.ProjectVersion
import com.example.data.model.UserWorkspace
import com.example.data.repository.SuperBrainRepository
import com.example.ui.brain.StreamUiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "EngineeringStudioVM"
private const val STOP_TIMEOUT_MS = 5000L

@OptIn(ExperimentalCoroutinesApi::class)
class EngineeringStudioViewModel(
    private val repository: SuperBrainRepository,
    private val registry: ModelCapabilityRegistry,
    multiAiOrchestrator: MultiAiOrchestrator,
    codingOrchestrator: CodingAgentOrchestrator,
    appBuilderOrchestrator: AppBuilderOrchestrator,
    buildServerClient: BuildServerClient,
    private val activeProjectFlow: StateFlow<BrainProject?>,
    private val workspaceStateFlow: StateFlow<StreamUiState<UserWorkspace?>>,
    onActivateProject: (String) -> Unit,
    private val currentUserId: String,
    private val backendUrlLocalStore: BackendUrlLocalStore? = null,
    private val defaultBackendUrl: String = BuildConfig.MYRAA_DEFAULT_BACKEND_URL
) : ViewModel() {

    private val _uiState = MutableStateFlow(EngineeringStudioUiState())
    val uiState: StateFlow<EngineeringStudioUiState> = _uiState.asStateFlow()

    val catalogState: StateFlow<List<AiProviderDescriptor>> = registry.catalogState
    val freeTierCatalogState: StateFlow<List<AiProviderDescriptor>> = registry.freeTierCatalogState
    val activeProject: StateFlow<BrainProject?> = activeProjectFlow
    val appCheckStatus: StateFlow<AppCheckDiagnosticStatus> = appCheckDiagnosticFlow

    private var lastSyncedWorkspaceUrl: String? = null

    private val hubCoordinator = MultiAiHubCoordinator(
        repository = repository,
        registry = registry,
        multiAiOrchestrator = multiAiOrchestrator,
        scope = viewModelScope,
        uiState = _uiState,
        catalogState = catalogState,
        onSyncedUrlUpdated = { lastSyncedWorkspaceUrl = it },
        backendUrlLocalStore = backendUrlLocalStore,
        currentUserId = currentUserId
    )

    private val buildCoordinator = RemoteBuildCoordinator(
        repository = repository,
        buildServerClient = buildServerClient,
        scope = viewModelScope,
        uiState = _uiState
    )

    private val workspaceCoordinator = AppWorkspaceCoordinator(
        repository = repository,
        codingOrchestrator = codingOrchestrator,
        appBuilderOrchestrator = appBuilderOrchestrator,
        scope = viewModelScope,
        uiState = _uiState,
        catalogState = catalogState,
        activeProjectFlow = activeProjectFlow,
        currentFilesProvider = ::currentFilesList,
        nextVersionProvider = ::nextVersionNumber,
        onActivateProject = onActivateProject,
        currentUserId = currentUserId
    )

    val projectFilesState: StateFlow<StreamUiState<List<ProjectFile>>> =
        activeProjectFlow
            .flatMapLatest { project ->
                if (project == null) flowOf(StreamUiState.Success(emptyList()))
                else repository.observeProjectFiles(currentUserId, project.id)
                    .map<List<ProjectFile>, StreamUiState<List<ProjectFile>>> { files ->
                        syncSelectionAndAnalysis(files)
                        StreamUiState.Success(files)
                    }
                    .catch { err -> handleStreamError(err, "files") { emit(it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState.Success(emptyList()))

    val projectVersionsState: StateFlow<StreamUiState<List<ProjectVersion>>> =
        activeProjectFlow
            .flatMapLatest { project ->
                if (project == null) flowOf(StreamUiState.Success(emptyList()))
                else repository.observeProjectVersions(currentUserId, project.id)
                    .map<List<ProjectVersion>, StreamUiState<List<ProjectVersion>>> { StreamUiState.Success(it) }
                    .catch { err -> handleStreamError(err, "versions") { emit(it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState.Success(emptyList()))

    val buildJobsState: StateFlow<StreamUiState<List<BuildJob>>> =
        activeProjectFlow
            .flatMapLatest { project ->
                if (project == null) flowOf(StreamUiState.Success(emptyList()))
                else repository.observeBuildJobs(currentUserId, project.id)
                    .map<List<BuildJob>, StreamUiState<List<BuildJob>>> { StreamUiState.Success(it) }
                    .catch { err -> handleStreamError(err, "build jobs") { emit(it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState.Success(emptyList()))

    val buildArtifactsState: StateFlow<StreamUiState<List<BuildArtifact>>> =
        activeProjectFlow
            .flatMapLatest { project ->
                if (project == null) flowOf(StreamUiState.Success(emptyList()))
                else repository.observeBuildArtifacts(currentUserId, project.id)
                    .map<List<BuildArtifact>, StreamUiState<List<BuildArtifact>>> { StreamUiState.Success(it) }
                    .catch { err -> handleStreamError(err, "artifacts") { emit(it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState.Success(emptyList()))

    private var hasBootstrappedAppCheck = false

    init {
        val defaultBuildConfigUrl = defaultBackendUrl
            .trim()
            .trim('"')
            .trimEnd('/')
            .takeIf { it.isNotBlank() && BackendAiProxyClient.validateBackendUrlError(it) == null }
            .orEmpty()
        val localSavedUrl = backendUrlLocalStore?.readBackendUrl(currentUserId).orEmpty()
        val initialBootstrapUrl = localSavedUrl.ifBlank { defaultBuildConfigUrl }
        if (localSavedUrl.isBlank() && defaultBuildConfigUrl.isNotBlank()) {
            backendUrlLocalStore?.writeBackendUrl(currentUserId, defaultBuildConfigUrl)
        }
        if (initialBootstrapUrl.isNotBlank()) {
            lastSyncedWorkspaceUrl = initialBootstrapUrl
            setAppCheckBackendUrl(initialBootstrapUrl)
            _uiState.update {
                it.copy(
                    backendUrlDraft = initialBootstrapUrl,
                    backendConnectionStatus = BackendConnectionStatus.TESTING,
                    backendConnectionDetail = "Checking saved backend $initialBootstrapUrl..."
                )
            }
            hubCoordinator.autoBootstrapAppCheck(initialBootstrapUrl)
            hubCoordinator.testBackendConnection(initialBootstrapUrl)
        }

        viewModelScope.launch {
            workspaceStateFlow.collect { wsState ->
                val ws = (wsState as? StreamUiState.Success)?.data ?: return@collect
                val remoteUrl = ws.backendServiceUrl.trim().trimEnd('/')
                val effectiveUrl = remoteUrl
                    .ifBlank { backendUrlLocalStore?.readBackendUrl(currentUserId).orEmpty() }
                    .ifBlank { defaultBuildConfigUrl }
                if (remoteUrl.isNotBlank()) {
                    backendUrlLocalStore?.writeBackendUrl(currentUserId, remoteUrl)
                }
                val firstSync = !hasBootstrappedAppCheck
                if (lastSyncedWorkspaceUrl != effectiveUrl || firstSync) {
                    hasBootstrappedAppCheck = true
                    lastSyncedWorkspaceUrl = effectiveUrl
                    setAppCheckBackendUrl(effectiveUrl)
                    _uiState.update {
                        it.copy(
                            backendUrlDraft = effectiveUrl,
                            preferredBuildType = BuildType.fromWire(ws.preferredBuildType),
                            defaultPackagePrefix = ws.defaultPackagePrefix.ifBlank { "com.myraa.app" },
                            backendConnectionStatus = if (effectiveUrl.isBlank()) {
                                BackendConnectionStatus.NOT_CONFIGURED
                            } else {
                                it.backendConnectionStatus
                            },
                            builderPackageName = if (it.builderPackageName == "com.myraa.app.studio") {
                                "${ws.defaultPackagePrefix.ifBlank { "com.myraa.app" }}.studio"
                            } else {
                                it.builderPackageName
                            }
                        )
                    }
                    hubCoordinator.autoBootstrapAppCheck(effectiveUrl)
                    if (effectiveUrl.isNotBlank()) {
                        verifyBackendAndProviders(effectiveUrl)
                    }
                }
            }
        }
    }

    fun updateComparisonPrompt(value: String) = _uiState.update { it.copy(comparisonPrompt = value) }

    fun toggleCompareModel(model: AiModelDescriptor) {
        val key = "${model.providerId.wireId}:${model.modelId}"
        _uiState.update { s ->
            val next = s.selectedCompareModelKeys.toMutableSet()
            if (key in next && next.size > 1) next.remove(key) else next.add(key)
            s.copy(selectedCompareModelKeys = next)
        }
    }

    fun selectCodingModel(model: AiModelDescriptor) =
        _uiState.update { it.copy(selectedCodingModelKey = "${model.providerId.wireId}:${model.modelId}") }

    fun updateCodingDirectiveDraft(value: String) = _uiState.update { it.copy(codingDirectiveDraft = value) }

    fun updateBuilderFields(
        category: AppCategory? = null,
        ideaPrompt: String? = null,
        appName: String? = null,
        packageName: String? = null,
        versionName: String? = null,
        versionCode: String? = null,
        iconStyle: IconStylePreset? = null,
        includeFirebase: Boolean? = null
    ) {
        _uiState.update { s ->
            s.copy(
                builderCategory = category ?: s.builderCategory,
                builderIdeaPrompt = ideaPrompt ?: s.builderIdeaPrompt,
                builderAppName = appName ?: s.builderAppName,
                builderPackageName = packageName ?: s.builderPackageName,
                builderVersionName = versionName ?: s.builderVersionName,
                builderVersionCode = versionCode ?: s.builderVersionCode,
                builderIconStyle = iconStyle ?: s.builderIconStyle,
                includeFirebaseRules = includeFirebase ?: s.includeFirebaseRules
            )
        }
    }

    fun toggleBuilderPermission(manifestName: String) {
        _uiState.update { s ->
            val next = s.builderPermissions.toMutableSet()
            if (manifestName in next) next.remove(manifestName) else next.add(manifestName)
            s.copy(builderPermissions = next)
        }
    }

    fun updateClarificationAnswer(questionId: String, answer: String) =
        _uiState.update { s -> s.copy(clarificationAnswers = s.clarificationAnswers + (questionId to answer)) }

    fun selectFile(file: ProjectFile) =
        _uiState.update { it.copy(selectedFilePath = file.path, editorContentDraft = file.content, isEditorDirty = false) }

    fun updateEditorDraft(newContent: String) =
        _uiState.update { it.copy(editorContentDraft = newContent, isEditorDirty = true) }

    fun verifyBackendAndProviders(urlOverride: String? = null) =
        hubCoordinator.verifyBackendAndProviders(urlOverride)

    fun saveBackendUrl(
        url: String,
        buildType: BuildType = _uiState.value.preferredBuildType,
        prefix: String = _uiState.value.defaultPackagePrefix
    ) = hubCoordinator.saveBackendUrl(url, buildType, prefix)

    fun testBackendConnection(urlOverride: String? = null) =
        hubCoordinator.testBackendConnection(urlOverride)

    fun verifyAndRefreshAppCheckToken() =
        hubCoordinator.verifyAndRefreshAppCheckToken()

    fun regenerateDebugToken(context: Context) =
        hubCoordinator.regenerateDebugToken(context)

    fun saveBackendConfiguration(url: String, buildType: BuildType, prefix: String) =
        hubCoordinator.saveBackendConfiguration(url, buildType, prefix)

    fun saveProviderApiKey(providerId: ProviderId, apiKey: String, accountId: String = "") =
        hubCoordinator.saveProviderApiKey(providerId, apiKey, accountId)

    fun deleteProviderApiKey(providerId: ProviderId) =
        hubCoordinator.deleteProviderApiKey(providerId)

    fun runParallelComparison(language: BrainLanguage) =
        hubCoordinator.runParallelComparison(language, activeProjectFlow.value?.id)

    fun synthesizeConsensus(language: BrainLanguage) =
        hubCoordinator.synthesizeConsensus(language, activeProjectFlow.value?.id)

    fun startRemoteApkBuild(buildType: BuildType) =
        buildCoordinator.startBuild(activeProjectFlow.value, currentFilesList(), buildType)

    fun prepareApkExport(artifact: BuildArtifact) =
        _uiState.update { it.copy(pendingExportArtifact = artifact) }

    fun clearPendingApkExport() =
        _uiState.update { it.copy(pendingExportArtifact = null) }

    fun downloadArtifactToSafUri(context: Context, targetUri: Uri) =
        buildCoordinator.downloadArtifactToSafUri(context, targetUri)

    fun fetchMinimalClarifications(language: BrainLanguage) =
        workspaceCoordinator.fetchMinimalClarifications(language)

    fun generateAppWorkspace(language: BrainLanguage, enhanceWithAi: Boolean, onGeneratedOpenWorkspace: () -> Unit) =
        workspaceCoordinator.generateAppWorkspace(language, enhanceWithAi, onGeneratedOpenWorkspace)

    fun executeCodingDirective(language: BrainLanguage) =
        workspaceCoordinator.executeCodingDirective(language)

    fun autoFixWorkspaceErrors() =
        workspaceCoordinator.autoFixWorkspaceErrors()

    fun saveCurrentFileEdit() {
        val proj = activeProjectFlow.value ?: return
        val path = _uiState.value.selectedFilePath ?: return
        val content = _uiState.value.editorContentDraft
        val files = currentFilesList()
        val updated = ProjectFile(
            id = files.firstOrNull { it.path == path }?.id ?: SuperBrainRepository.fileIdForPath(path),
            userId = currentUserId,
            projectId = proj.id,
            path = path,
            fileName = path.substringAfterLast('/'),
            language = SuperBrainRepository.inferLanguageFromPath(path),
            content = content,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            checksumSha256 = SuperBrainRepository.sha256Hex(content),
            updatedByAgent = "Manual Editor"
        )
        val merged = CodingAgentOrchestrator.mergeFiles(files, listOf(updated))
        viewModelScope.launch {
            repository.saveProjectFilesAndVersion(proj.id, listOf(updated), merged, nextVersionNumber(), "Edited $path", "Manual Editor")
                .onSuccess {
                    _uiState.update { it.copy(isEditorDirty = false, staticAnalysis = KotlinAndGradleStaticAnalyzer.analyze(merged), statusMessage = "Saved $path.") }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Failed to save file.") }
                }
        }
    }

    fun createNewFile(rawPath: String) {
        val proj = activeProjectFlow.value ?: return
        val cleanPath = SuperBrainRepository.sanitizeRelativePath(rawPath)
        val initContent = when {
            cleanPath.endsWith(".kt") -> "package ${proj.packageName}\n\n// Created in MYRAA Workspace\n"
            cleanPath.endsWith(".xml") -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources />\n"
            else -> "// $cleanPath\n"
        }
        val newFile = ProjectFile(id = SuperBrainRepository.fileIdForPath(cleanPath), path = cleanPath, fileName = cleanPath.substringAfterLast('/'), language = SuperBrainRepository.inferLanguageFromPath(cleanPath), content = initContent, updatedByAgent = "Manual Editor")
        val merged = CodingAgentOrchestrator.mergeFiles(currentFilesList(), listOf(newFile))
        viewModelScope.launch {
            repository.saveProjectFilesAndVersion(proj.id, listOf(newFile), merged, nextVersionNumber(), "Created $cleanPath", "Manual Editor")
                .onSuccess {
                    _uiState.update { it.copy(selectedFilePath = cleanPath, editorContentDraft = initContent, isEditorDirty = false, statusMessage = "Created $cleanPath.") }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Failed to create file.") }
                }
        }
    }

    fun deleteFile(file: ProjectFile) {
        val proj = activeProjectFlow.value ?: return
        if (file.id.isBlank()) return
        viewModelScope.launch {
            repository.deleteProjectFile(proj.id, file.id)
                .onSuccess {
                    if (_uiState.value.selectedFilePath == file.path) {
                        _uiState.update { it.copy(selectedFilePath = null, editorContentDraft = "", isEditorDirty = false) }
                    }
                    _uiState.update { it.copy(statusMessage = "Deleted ${file.path}.") }
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Failed to delete file.") }
                }
        }
    }

    fun restoreVersion(version: ProjectVersion) {
        val proj = activeProjectFlow.value ?: return
        viewModelScope.launch {
            repository.restoreProjectVersion(proj.id, version, currentFilesList(), nextVersionNumber())
                .onSuccess { _uiState.update { it.copy(statusMessage = "Restored snapshot v${version.versionNumber}.") } }
                .onFailure { err -> _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Restore failed.") } }
        }
    }

    private fun currentFilesList(): List<ProjectFile> = (projectFilesState.value as? StreamUiState.Success)?.data.orEmpty()
    private fun nextVersionNumber(): Int = ((projectVersionsState.value as? StreamUiState.Success)?.data.orEmpty().maxOfOrNull { it.versionNumber } ?: 0) + 1

    private fun syncSelectionAndAnalysis(files: List<ProjectFile>) {
        val report = KotlinAndGradleStaticAnalyzer.analyze(files)
        _uiState.update { s ->
            val currentFile = files.firstOrNull { it.path == s.selectedFilePath }
            val nextFile = currentFile ?: files.firstOrNull { it.path.endsWith("AppHomeScreen.kt") } ?: files.firstOrNull()
            val shouldUpdateDraft = !s.isEditorDirty || currentFile == null
            s.copy(
                staticAnalysis = report,
                selectedFilePath = nextFile?.path,
                editorContentDraft = if (shouldUpdateDraft) nextFile?.content.orEmpty() else s.editorContentDraft,
                isEditorDirty = if (shouldUpdateDraft) false else s.isEditorDirty
            )
        }
    }

    private suspend inline fun <T> handleStreamError(error: Throwable, label: String, emitError: suspend (StreamUiState<T>) -> Unit) {
        val fs = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (fs == null && error is CancellationException) throw error
        Log.w(TAG, "Error observing $label", fs ?: error)
        emitError(StreamUiState.Error((fs ?: error).message ?: "Failed to load $label"))
    }
}
