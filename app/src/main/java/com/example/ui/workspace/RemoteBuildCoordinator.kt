package com.example.ui.workspace

import android.content.Context
import android.net.Uri
import com.example.build.BuildServerClient
import com.example.coding.KotlinAndGradleStaticAnalyzer
import com.example.data.model.BrainProject
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildJob
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
import com.example.data.repository.SuperBrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RemoteBuildCoordinator(
    private val repository: SuperBrainRepository,
    private val buildServerClient: BuildServerClient,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<EngineeringStudioUiState>
) {
    private var buildPollingJob: Job? = null

    fun startBuild(
        proj: BrainProject?,
        files: List<ProjectFile>,
        buildType: BuildType
    ) {
        val backendUrl = uiState.value.backendUrlDraft.trim()
        if (proj == null || files.isEmpty()) {
            uiState.update { it.copy(errorMessage = "Generate or open a project with Android Gradle files first.") }
            return
        }
        if (backendUrl.isBlank()) {
            uiState.update { it.copy(errorMessage = "Configure Remote Build Server URL in Project Settings before running an APK build.") }
            return
        }
        val report = KotlinAndGradleStaticAnalyzer.analyze(files)
        if (report.hasErrors) {
            uiState.update {
                it.copy(
                    staticAnalysis = report,
                    errorMessage = "Resolve ${report.errorCount} static/policy error(s) before dispatching remote build (try Auto-Fix)."
                )
            }
            return
        }
        if (uiState.value.isSubmittingBuild) return

        scope.launch {
            uiState.update { it.copy(isSubmittingBuild = true, errorMessage = null, apkVerificationNotice = null) }
            val submitRes = buildServerClient.submitBuild(
                baseUrl = backendUrl,
                projectId = proj.id,
                buildType = buildType,
                appName = proj.name,
                packageName = proj.packageName,
                versionName = proj.appVersionName,
                versionCode = proj.appVersionCode,
                files = files
            )
            submitRes.onFailure { err ->
                uiState.update { it.copy(isSubmittingBuild = false, errorMessage = err.localizedMessage ?: "Build dispatch rejected.") }
                return@launch
            }
            val queued = submitRes.getOrThrow()
            repository.createBuildJob(
                projectId = proj.id,
                job = BuildJob(
                    id = queued.jobId,
                    projectId = proj.id,
                    status = queued.status.wireValue,
                    buildType = buildType.wireValue,
                    appName = proj.name,
                    packageName = proj.packageName,
                    versionName = proj.appVersionName,
                    versionCode = proj.appVersionCode,
                    targetServerUrl = backendUrl,
                    logSummary = queued.logs
                )
            )
            uiState.update {
                it.copy(
                    isSubmittingBuild = false,
                    activeRemoteBuild = queued,
                    statusMessage = "Build job ${queued.jobId} queued on Linux Gradle worker."
                )
            }
            pollBuild(backendUrl, proj.id, queued.jobId)
        }
    }

    private fun pollBuild(backendUrl: String, projectId: String, jobId: String) {
        buildPollingJob?.cancel()
        buildPollingJob = scope.launch {
            while (true) {
                delay(1500L)
                val poll = buildServerClient.getBuildStatus(backendUrl, jobId)
                poll.onFailure { err ->
                    uiState.update { it.copy(errorMessage = "Build status polling error: ${err.localizedMessage}") }
                    return@launch
                }
                val remote = poll.getOrThrow()
                uiState.update { it.copy(activeRemoteBuild = remote) }
                if (remote.status.isTerminal) {
                    repository.updateBuildJobState(
                        projectId = projectId,
                        jobId = remote.jobId,
                        status = remote.status,
                        logSummary = remote.logs,
                        errorSummary = remote.errorSummary,
                        durationMs = remote.durationMs
                    )
                    val art = remote.artifact
                    if (remote.status == BuildJobStatus.SUCCEEDED && art != null && art.apkVerified && art.sizeBytes > 0L && art.sha256.length == 64) {
                        repository.recordBuildArtifact(
                            projectId = projectId,
                            artifact = BuildArtifact(
                                projectId = projectId,
                                jobId = remote.jobId,
                                fileName = art.fileName,
                                downloadUrl = art.downloadUrl,
                                sha256 = art.sha256,
                                sizeBytes = art.sizeBytes,
                                buildType = art.buildType.wireValue,
                                apkVerified = true,
                                expiresAtEpochMs = art.expiresAtEpochMs
                            )
                        )
                        uiState.update { it.copy(statusMessage = "APK Build Succeeded (${art.sizeBytes / 1024} KB, SHA-256 verified).") }
                    } else if (remote.status == BuildJobStatus.FAILED) {
                        uiState.update { it.copy(errorMessage = remote.errorSummary.ifBlank { "Remote Gradle build failed." }) }
                    }
                    return@launch
                }
            }
        }
    }

    fun downloadArtifactToSafUri(context: Context, targetUri: Uri) {
        val artifact = uiState.value.pendingExportArtifact ?: return
        uiState.update { it.copy(pendingExportArtifact = null, isDownloadingApk = true, errorMessage = null) }
        scope.launch {
            val res = runCatching {
                val stream = context.contentResolver.openOutputStream(targetUri)
                    ?: throw IllegalStateException("Unable to open chosen document location.")
                stream.use { out ->
                    buildServerClient.downloadVerifiedApk(artifact.downloadUrl, artifact.sha256, out).getOrThrow()
                }
            }
            res.onSuccess { verified ->
                uiState.update {
                    it.copy(
                        isDownloadingApk = false,
                        apkVerificationNotice = "Saved verified APK: ${artifact.fileName} (${verified.bytesWritten / 1024} KB) • SHA-256 ${verified.sha256Hex.take(16)}...",
                        statusMessage = "APK verified & saved safely via Android Storage Access Framework."
                    )
                }
            }.onFailure { err ->
                uiState.update {
                    it.copy(
                        isDownloadingApk = false,
                        errorMessage = err.localizedMessage ?: "APK download verification failed."
                    )
                }
            }
        }
    }
}
