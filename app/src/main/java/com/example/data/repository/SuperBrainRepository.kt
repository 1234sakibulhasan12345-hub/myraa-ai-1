package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildJob
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
import com.example.data.model.ProjectStatus
import com.example.data.model.ProjectVersion
import com.example.data.model.RoutingPolicy
import com.example.data.model.UsageLog
import com.example.data.model.UserWorkspace
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SuperBrainRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeWorkspace(userId: String): Flow<UserWorkspace?> {
        val docRef = db.collection("users").document(userId)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(UserWorkspace::class.java, ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path, auth)
                }
                throw error
            }
    }

    suspend fun ensureWorkspaceExists(displayName: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        try {
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val safeName = displayName.trim().ifBlank { "Commander" }.take(100)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "preferredLanguage" to BrainLanguage.AUTO.wireValue,
                    "routingPolicy" to RoutingPolicy.AUTO_BALANCED.wireValue,
                    "backendServiceUrl" to "",
                    "preferredBuildType" to BuildType.DEBUG.wireValue,
                    "defaultPackagePrefix" to "com.myraa.app",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateWorkspacePreferences(
        preferredLanguage: BrainLanguage,
        routingPolicy: RoutingPolicy,
        activeProjectId: String?
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val updates = buildMap<String, Any?> {
            put("preferredLanguage", preferredLanguage.wireValue)
            put("routingPolicy", routingPolicy.wireValue)
            if (activeProjectId != null) {
                put("activeProjectId", activeProjectId)
            }
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateBackendConfiguration(
        backendServiceUrl: String,
        preferredBuildType: BuildType,
        defaultPackagePrefix: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val sanitizedUrl = backendServiceUrl.trim().trimEnd('/').take(512)
        val sanitizedPrefix = defaultPackagePrefix.trim().ifBlank { "com.myraa.app" }.take(80)
        val updates = mapOf(
            "backendServiceUrl" to sanitizedUrl,
            "preferredBuildType" to preferredBuildType.wireValue,
            "defaultPackagePrefix" to sanitizedPrefix,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(updates).await()
            } else {
                val safeName = auth.currentUser?.displayName?.trim()?.ifBlank { "Commander" }?.take(100) ?: "Commander"
                val createPayload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "preferredLanguage" to BrainLanguage.AUTO.wireValue,
                    "routingPolicy" to RoutingPolicy.AUTO_BALANCED.wireValue,
                    "backendServiceUrl" to sanitizedUrl,
                    "preferredBuildType" to preferredBuildType.wireValue,
                    "defaultPackagePrefix" to sanitizedPrefix,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    fun observeProjects(userId: String): Flow<List<BrainProject>> {
        val colRef = db.collection("users").document(userId).collection("projects")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(BrainProject::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun createProject(project: BrainProject): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("projects")
        val docRef = colRef.document()
        val payload = buildMap<String, Any> {
            put("userId", uid)
            put("name", project.name.trim().ifBlank { "Untitled Project" }.take(120))
            put("targetFramework", project.targetFramework)
            put("requirementsSummary", project.requirementsSummary.take(4000))
            put("detectedLanguage", project.detectedLanguage)
            put("architectureSummary", project.architectureSummary.take(4000))
            put("completedFeatures", project.completedFeatures.take(25).map { it.take(500) })
            put("pendingFeatures", project.pendingFeatures.take(25).map { it.take(500) })
            put("status", project.status)
            put("appCategory", project.appCategory.take(40))
            put("packageName", project.packageName.ifBlank { "com.myraa.generated.app" }.take(128))
            put("appVersionName", project.appVersionName.ifBlank { "1.0.0" }.take(32))
            put("appVersionCode", project.appVersionCode.coerceIn(1, 1000000))
            put("iconStyle", project.iconStyle.take(64))
            put("selectedPermissions", project.selectedPermissions.take(20).map { it.take(120) })
            project.latestBuildJobId?.takeIf { it.isNotBlank() }?.let { put("latestBuildJobId", it) }
            put("latestBuildStatus", project.latestBuildStatus.take(40))
            put("fileCount", project.fileCount.coerceIn(0, 500))
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun getProject(projectId: String): Result<BrainProject?> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        try {
            docRef.get().await()
                .toObject(BrainProject::class.java, ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateProjectMemory(project: BrainProject): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(project.id.isNotBlank()) { "Project ID must not be blank for update" }
        val docRef = db.collection("users").document(uid).collection("projects").document(project.id)
        val updates = buildMap<String, Any> {
            put("name", project.name.trim().ifBlank { "Untitled Project" }.take(120))
            put("targetFramework", project.targetFramework)
            put("requirementsSummary", project.requirementsSummary.take(4000))
            put("detectedLanguage", project.detectedLanguage)
            put("architectureSummary", project.architectureSummary.take(4000))
            put("completedFeatures", project.completedFeatures.take(25).map { it.take(500) })
            put("pendingFeatures", project.pendingFeatures.take(25).map { it.take(500) })
            put("status", project.status)
            put("appCategory", project.appCategory.take(40))
            put("packageName", project.packageName.ifBlank { "com.myraa.generated.app" }.take(128))
            put("appVersionName", project.appVersionName.ifBlank { "1.0.0" }.take(32))
            put("appVersionCode", project.appVersionCode.coerceIn(1, 1000000))
            put("iconStyle", project.iconStyle.take(64))
            put("selectedPermissions", project.selectedPermissions.take(20).map { it.take(120) })
            project.latestBuildJobId?.takeIf { it.isNotBlank() }?.let { put("latestBuildJobId", it) }
            put("latestBuildStatus", project.latestBuildStatus.take(40))
            put("fileCount", project.fileCount.coerceIn(0, 500))
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun archiveProject(projectId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        try {
            docRef.update(
                mapOf(
                    "status" to ProjectStatus.ARCHIVED.wireValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    fun observeMessages(userId: String, projectId: String): Flow<List<BrainMessage>> {
        val colRef = db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("messages")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(BrainMessage::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun addMessage(projectId: String, message: BrainMessage): Result<String> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("messages").document()
        val payload = mapOf(
            "userId" to uid,
            "projectId" to projectId,
            "role" to message.role,
            "content" to message.content.ifBlank { "(empty)" }.take(16000),
            "detectedLanguage" to message.detectedLanguage,
            "selectedProviderId" to message.selectedProviderId.take(64),
            "selectedModelId" to message.selectedModelId.take(64),
            "intentSummary" to message.intentSummary.take(1000),
            "assignedAgents" to message.assignedAgents.take(13).map { it.take(500) },
            "subtasks" to message.subtasks.take(20).map { it.take(500) },
            "latencyMs" to message.latencyMs.coerceIn(0L, 600000L),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    fun observeProjectFiles(userId: String, projectId: String): Flow<List<ProjectFile>> {
        val colRef = db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("files")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(ProjectFile::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.path.lowercase() }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun upsertProjectFile(projectId: String, file: ProjectFile): Result<String> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val normalizedPath = sanitizeRelativePath(file.path)
        val fileDocId = fileIdForPath(normalizedPath)
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("files").document(fileDocId)

        val boundedContent = file.content.take(115000)
        val checksum = sha256Hex(boundedContent)
        val fileName = normalizedPath.substringAfterLast('/').ifBlank { "file.txt" }.take(128)
        val language = file.language.ifBlank { inferLanguageFromPath(normalizedPath) }.take(32)
        val sizeBytes = boundedContent.toByteArray(StandardCharsets.UTF_8).size.toLong()

        try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "path" to normalizedPath,
                        "fileName" to fileName,
                        "language" to language,
                        "content" to boundedContent,
                        "sizeBytes" to sizeBytes,
                        "checksumSha256" to checksum,
                        "updatedByAgent" to file.updatedByAgent.ifBlank { "Coding Agent" }.take(80),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "projectId" to projectId,
                        "path" to normalizedPath,
                        "fileName" to fileName,
                        "language" to language,
                        "content" to boundedContent,
                        "sizeBytes" to sizeBytes,
                        "checksumSha256" to checksum,
                        "updatedByAgent" to file.updatedByAgent.ifBlank { "Coding Agent" }.take(80),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            fileDocId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteProjectFile(projectId: String, fileId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank() && fileId.isNotBlank())
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("files").document(fileId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    suspend fun saveProjectFilesAndVersion(
        projectId: String,
        filesToSave: List<ProjectFile>,
        allCurrentFilesAfterMerge: List<ProjectFile>,
        versionNumber: Int,
        summary: String,
        createdByAgent: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        filesToSave.forEach { file ->
            upsertProjectFile(projectId, file).getOrThrow()
        }

        val versionRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("versions").document()

        val boundedEntries = allCurrentFilesAfterMerge
            .take(35)
            .map { item ->
                mapOf(
                    "path" to sanitizeRelativePath(item.path),
                    "language" to item.language.ifBlank { inferLanguageFromPath(item.path) }.take(32),
                    "content" to item.content.take(24000)
                )
            }

        try {
            versionRef.set(
                mapOf(
                    "userId" to uid,
                    "projectId" to projectId,
                    "versionNumber" to versionNumber.coerceIn(1, 100000),
                    "summary" to summary.trim().ifBlank { "Workspace update" }.take(500),
                    "createdByAgent" to createdByAgent.ifBlank { "Coding Agent" }.take(80),
                    "fileEntries" to boundedEntries,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val projRef = db.collection("users").document(uid).collection("projects").document(projectId)
            val snap = projRef.get().await()
            if (snap.exists() && snap.getString("status") != ProjectStatus.ARCHIVED.wireValue) {
                projRef.update(
                    mapOf(
                        "fileCount" to allCurrentFilesAfterMerge.size.coerceIn(0, 500),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            versionRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, versionRef.path, auth)
            throw e
        }
    }

    fun observeProjectVersions(userId: String, projectId: String): Flow<List<ProjectVersion>> {
        val colRef = db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("versions")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(ProjectVersion::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.versionNumber }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun restoreProjectVersion(
        projectId: String,
        version: ProjectVersion,
        existingFiles: List<ProjectFile>,
        nextVersionNumber: Int
    ): Result<Unit> = runCatching {
        val restoredFiles = version.fileEntries.mapNotNull { entry ->
            val path = entry["path"] ?: return@mapNotNull null
            val content = entry["content"] ?: ""
            val lang = entry["language"] ?: inferLanguageFromPath(path)
            ProjectFile(
                path = path,
                fileName = path.substringAfterLast('/'),
                language = lang,
                content = content,
                updatedByAgent = "Version Restore (v${version.versionNumber})"
            )
        }
        val restoredPaths = restoredFiles.map { sanitizeRelativePath(it.path) }.toSet()
        existingFiles.forEach { existing ->
            if (sanitizeRelativePath(existing.path) !in restoredPaths && existing.id.isNotBlank()) {
                deleteProjectFile(projectId, existing.id).getOrThrow()
            }
        }
        saveProjectFilesAndVersion(
            projectId = projectId,
            filesToSave = restoredFiles,
            allCurrentFilesAfterMerge = restoredFiles,
            versionNumber = nextVersionNumber,
            summary = "Restored from v${version.versionNumber}: ${version.summary}",
            createdByAgent = "Version Controller"
        ).getOrThrow()
    }

    fun observeBuildJobs(userId: String, projectId: String): Flow<List<BuildJob>> {
        val colRef = db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("build_jobs")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(BuildJob::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun createBuildJob(projectId: String, job: BuildJob): Result<String> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val docRef = if (job.id.isNotBlank()) {
            db.collection("users").document(uid)
                .collection("projects").document(projectId)
                .collection("build_jobs").document(job.id)
        } else {
            db.collection("users").document(uid)
                .collection("projects").document(projectId)
                .collection("build_jobs").document()
        }
        val payload = mapOf(
            "userId" to uid,
            "projectId" to projectId,
            "status" to job.status,
            "buildType" to job.buildType,
            "appName" to job.appName.ifBlank { "MyraaGeneratedApp" }.take(120),
            "packageName" to job.packageName.ifBlank { "com.myraa.generated.app" }.take(128),
            "versionName" to job.versionName.ifBlank { "1.0.0" }.take(32),
            "versionCode" to job.versionCode.coerceIn(1, 1000000),
            "targetServerUrl" to job.targetServerUrl.take(512),
            "startedByModel" to job.startedByModel.ifBlank { "gemini-3.8-flash" }.take(64),
            "logSummary" to job.logSummary.take(32000),
            "errorSummary" to job.errorSummary.take(8000),
            "durationMs" to job.durationMs.coerceIn(0L, 1800000L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            val projRef = db.collection("users").document(uid).collection("projects").document(projectId)
            val snap = projRef.get().await()
            if (snap.exists() && snap.getString("status") != ProjectStatus.ARCHIVED.wireValue) {
                projRef.update(
                    mapOf(
                        "latestBuildJobId" to docRef.id,
                        "latestBuildStatus" to job.status,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateBuildJobState(
        projectId: String,
        jobId: String,
        status: BuildJobStatus,
        logSummary: String,
        errorSummary: String,
        durationMs: Long
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank() && jobId.isNotBlank())
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("build_jobs").document(jobId)
        try {
            docRef.update(
                mapOf(
                    "status" to status.wireValue,
                    "logSummary" to logSummary.take(32000),
                    "errorSummary" to errorSummary.take(8000),
                    "durationMs" to durationMs.coerceIn(0L, 1800000L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val projRef = db.collection("users").document(uid).collection("projects").document(projectId)
            val snap = projRef.get().await()
            if (snap.exists() && snap.getString("status") != ProjectStatus.ARCHIVED.wireValue) {
                projRef.update(
                    mapOf(
                        "latestBuildStatus" to status.wireValue,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    fun observeBuildArtifacts(userId: String, projectId: String): Flow<List<BuildArtifact>> {
        val colRef = db.collection("users").document(userId)
            .collection("projects").document(projectId)
            .collection("artifacts")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(BuildArtifact::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun recordBuildArtifact(projectId: String, artifact: BuildArtifact): Result<String> = runCatching {
        val uid = requireUserId()
        require(projectId.isNotBlank() && artifact.jobId.isNotBlank()) { "Project & Job ID required" }
        require(artifact.apkVerified && artifact.sizeBytes > 0L) {
            "Only verified non-empty APK artifacts may be recorded."
        }
        val docRef = db.collection("users").document(uid)
            .collection("projects").document(projectId)
            .collection("artifacts").document()
        val payload = mapOf(
            "userId" to uid,
            "projectId" to projectId,
            "jobId" to artifact.jobId,
            "fileName" to artifact.fileName.take(160),
            "downloadUrl" to artifact.downloadUrl.take(1024),
            "sha256" to artifact.sha256.take(64),
            "sizeBytes" to artifact.sizeBytes.coerceAtLeast(1L),
            "buildType" to artifact.buildType,
            "apkVerified" to true,
            "expiresAtEpochMs" to artifact.expiresAtEpochMs.coerceAtLeast(1L),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    fun observeUsageLogs(userId: String): Flow<List<UsageLog>> {
        val colRef = db.collection("users").document(userId).collection("usage_logs")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(UsageLog::class.java, ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                }
                throw error
            }
    }

    suspend fun recordUsageLog(log: UsageLog): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("usage_logs").document()
        val payload = mapOf(
            "userId" to uid,
            "projectId" to log.projectId.ifBlank { "default_session" }.take(128),
            "providerId" to log.providerId.ifBlank { "google_gemini" }.take(64),
            "modelId" to log.modelId.ifBlank { "gemini-3.8-flash" }.take(64),
            "taskRole" to log.taskRole.ifBlank { "MASTER_PLANNING" }.take(64),
            "inputLanguage" to log.inputLanguage,
            "estimatedInputChars" to log.estimatedInputChars.coerceAtLeast(0L),
            "estimatedOutputChars" to log.estimatedOutputChars.coerceAtLeast(0L),
            "latencyMs" to log.latencyMs.coerceAtLeast(0L),
            "succeeded" to log.succeeded,
            "promptTokens" to log.promptTokens.coerceAtLeast(0L),
            "completionTokens" to log.completionTokens.coerceAtLeast(0L),
            "estimatedCostUsdMicros" to log.estimatedCostUsdMicros.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    companion object {
        fun sanitizeRelativePath(rawPath: String): String {
            val cleaned = rawPath.trim()
                .replace("\\", "/")
                .trimStart('/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
            return cleaned.ifBlank { "src/Main.kt" }.take(300)
        }

        fun fileIdForPath(path: String): String =
            sha256Hex(sanitizeRelativePath(path)).take(32)

        fun sha256Hex(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun inferLanguageFromPath(path: String): String {
            return when {
                path.endsWith(".kt") || path.endsWith(".kts") -> "kotlin"
                path.endsWith(".xml") -> "xml"
                path.endsWith(".json") -> "json"
                path.endsWith(".js") || path.endsWith(".mjs") -> "javascript"
                path.endsWith(".ts") || path.endsWith(".tsx") -> "typescript"
                path.endsWith(".py") -> "python"
                path.endsWith(".html") -> "html"
                path.endsWith(".css") -> "css"
                path.endsWith(".toml") -> "toml"
                path.endsWith(".properties") -> "properties"
                path.endsWith(".md") -> "markdown"
                else -> "text"
            }
        }
    }
}
