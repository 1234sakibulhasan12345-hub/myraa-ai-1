package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildJob
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.MessageRole
import com.example.data.model.ProjectFile
import com.example.data.model.ProjectStatus
import com.example.data.model.TargetFramework
import com.example.data.model.UsageLog
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class SuperBrainRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureWorkspaceAndCreateProject_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)

        val workspaceResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureWorkspaceExists("Alice Developer")
        }
        assertTrue(workspaceResult.isSuccess)

        val backendResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateBackendConfiguration(
                backendServiceUrl = "https://myraa-build.example.com",
                preferredBuildType = BuildType.DEBUG,
                defaultPackagePrefix = "com.alice.apps"
            )
        }
        assertTrue(backendResult.isSuccess)

        val project = BrainProject(
            name = "Myraa Test ${UUID.randomUUID().toString().take(8)}",
            targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
            requirementsSummary = "Create an Android app in Bengali and English.",
            detectedLanguage = BrainLanguage.BN_EN.wireValue,
            architectureSummary = "MVVM + Compose",
            completedFeatures = listOf("Core Setup"),
            pendingFeatures = listOf("Master AI Brain"),
            status = ProjectStatus.PLANNING.wireValue,
            appCategory = AppCategory.CHATTING.wireValue,
            packageName = "com.alice.myrachats"
        )
        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.createProject(project) }
        assertTrue(createResult.isSuccess)
        val projectId = createResult.getOrThrow()
        assertTrue(projectId.isNotBlank())

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProjects(aliceUid).first { list -> list.any { it.id == projectId } }
        }
        assertTrue(observed.any { it.id == projectId && it.userId == aliceUid && it.packageName == "com.alice.myrachats" })
    }

    @Test
    fun addMessage_withExistingProject_succeeds_andMissingProject_failsWithPermissionDenied() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)

        val projectId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createProject(
                BrainProject(
                    name = "Parent Gate Check",
                    targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
                    requirementsSummary = "Check parent project gate",
                    detectedLanguage = BrainLanguage.EN.wireValue,
                    status = ProjectStatus.ACTIVE.wireValue
                )
            ).getOrThrow()
        }

        val validMessage = BrainMessage(
            role = MessageRole.USER.wireValue,
            content = "Build a secure fintech tracker",
            detectedLanguage = BrainLanguage.EN.wireValue,
            selectedProviderId = "google_gemini",
            selectedModelId = "gemini-3.8-flash",
            intentSummary = "Fintech tracker blueprint",
            assignedAgents = listOf("Master Planner Agent"),
            subtasks = listOf("Design schema"),
            latencyMs = 150L
        )

        val addResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addMessage(projectId, validMessage)
        }
        assertTrue(addResult.isSuccess)

        val missingResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addMessage("non_existent_${UUID.randomUUID()}", validMessage)
        }
        assertTrue(missingResult.isFailure)
        val firestoreEx = missingResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun workspaceFilesVersionsAndRestore_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)

        val projectId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createProject(
                BrainProject(
                    name = "Workspace Versioning Check",
                    targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
                    requirementsSummary = "Verify file tree and version restore",
                    detectedLanguage = BrainLanguage.EN.wireValue,
                    status = ProjectStatus.ACTIVE.wireValue
                )
            ).getOrThrow()
        }

        val initialFile = ProjectFile(
            path = "app/src/main/java/com/myraa/MainActivity.kt",
            content = "package com.myraa\n// v1",
            updatedByAgent = "App Builder Studio"
        )
        val v1Id = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveProjectFilesAndVersion(
                projectId = projectId,
                filesToSave = listOf(initialFile),
                allCurrentFilesAfterMerge = listOf(initialFile),
                versionNumber = 1,
                summary = "Initial scaffold",
                createdByAgent = "App Builder Studio"
            ).getOrThrow()
        }
        assertTrue(v1Id.isNotBlank())

        val v2File = initialFile.copy(content = "package com.myraa\n// v2 modified")
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveProjectFilesAndVersion(
                projectId = projectId,
                filesToSave = listOf(v2File),
                allCurrentFilesAfterMerge = listOf(v2File),
                versionNumber = 2,
                summary = "Updated code",
                createdByAgent = "Coding Agent"
            ).getOrThrow()
        }

        val versions = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProjectVersions(aliceUid, projectId).first { it.size >= 2 }
        }
        val v1Snapshot = versions.first { it.versionNumber == 1 }
        val currentFiles = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProjectFiles(aliceUid, projectId).first { it.isNotEmpty() }
        }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.restoreProjectVersion(projectId, v1Snapshot, currentFiles, nextVersionNumber = 3).getOrThrow()
        }

        val restoredFiles = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProjectFiles(aliceUid, projectId)
                .first { list -> list.any { it.content.contains("// v1") } }
        }
        assertTrue(restoredFiles.single().content.contains("// v1"))
    }

    @Test
    fun buildJobAndVerifiedArtifact_enforceTerminalLockAndIntegrity() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)

        val projectId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createProject(
                BrainProject(
                    name = "APK Build Verification",
                    targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
                    requirementsSummary = "Verify build job terminal lock & verified APK artifacts",
                    detectedLanguage = BrainLanguage.EN.wireValue,
                    status = ProjectStatus.ACTIVE.wireValue,
                    packageName = "com.myraa.testbuild"
                )
            ).getOrThrow()
        }

        val jobId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createBuildJob(
                projectId,
                BuildJob(
                    status = BuildJobStatus.QUEUED.wireValue,
                    buildType = BuildType.DEBUG.wireValue,
                    appName = "TestBuild",
                    packageName = "com.myraa.testbuild",
                    versionName = "1.0.0",
                    versionCode = 1,
                    targetServerUrl = "https://builder.example.com",
                    startedByModel = "gemini-3.8-flash",
                    logSummary = "Job queued"
                )
            ).getOrThrow()
        }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateBuildJobState(
                projectId = projectId,
                jobId = jobId,
                status = BuildJobStatus.SUCCEEDED,
                logSummary = "BUILD SUCCESSFUL",
                errorSummary = "",
                durationMs = 4500L
            ).getOrThrow()
        }

        // Terminal lock: subsequent update after SUCCEEDED must fail with PERMISSION_DENIED
        val postFinishUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateBuildJobState(
                projectId = projectId,
                jobId = jobId,
                status = BuildJobStatus.FAILED,
                logSummary = "Tampered",
                errorSummary = "Tampered",
                durationMs = 100L
            )
        }
        assertTrue(postFinishUpdate.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            postFinishUpdate.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val artifactId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.recordBuildArtifact(
                projectId,
                BuildArtifact(
                    jobId = jobId,
                    fileName = "testbuild-debug.apk",
                    downloadUrl = "https://builder.example.com/v1/artifacts/$jobId/apk",
                    sha256 = "b".repeat(64),
                    sizeBytes = 2048576L,
                    buildType = BuildType.DEBUG.wireValue,
                    apkVerified = true,
                    expiresAtEpochMs = System.currentTimeMillis() + 86400_000L
                )
            ).getOrThrow()
        }
        assertTrue(artifactId.isNotBlank())

        val artifacts = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeBuildArtifacts(aliceUid, projectId).first { it.any { a -> a.id == artifactId } }
        }
        assertEquals(jobId, artifacts.first { it.id == artifactId }.jobId)
    }

    @Test
    fun updateProject_afterArchived_failsWithPermissionDenied() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)

        val projectId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createProject(
                BrainProject(
                    name = "Archive Lock Check",
                    targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
                    requirementsSummary = "Verify terminal status lock",
                    detectedLanguage = BrainLanguage.EN.wireValue,
                    status = ProjectStatus.ACTIVE.wireValue
                )
            ).getOrThrow()
        }

        val archiveResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.archiveProject(projectId) }
        assertTrue(archiveResult.isSuccess)

        val loaded = withTimeout(DEFAULT_TIMEOUT_MS) { repository.getProject(projectId).getOrThrow() }
        assertNotNull(loaded)

        val postArchiveUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateProjectMemory(loaded!!.copy(name = "Should Be Rejected"))
        }
        assertTrue(postArchiveUpdate.isFailure)
        val firestoreEx = postArchiveUpdate.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun observeProjects_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SuperBrainRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createProject(
                BrainProject(
                    name = "Alice Secret Project",
                    targetFramework = TargetFramework.WEB_APP.wireValue,
                    requirementsSummary = "Private spec",
                    detectedLanguage = BrainLanguage.EN.wireValue,
                    status = ProjectStatus.PLANNING.wireValue
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = SuperBrainRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeProjects(aliceUid).first()
            }
            fail("Expected cross-user observeProjects to fail with PERMISSION_DENIED")
        } catch (e: Exception) {
            val firestoreEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
        }
    }

    @Test
    fun observeProjects_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = SuperBrainRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeProjects("some_user_123").first()
            }
            fail("Expected unauthenticated observeProjects to fail with PERMISSION_DENIED")
        } catch (e: Exception) {
            val firestoreEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
        }
    }

    @Test
    fun recordUsageLog_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SuperBrainRepository(firestore, auth)
        val projectId = "proj_${UUID.randomUUID().toString().take(8)}"

        val logResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.recordUsageLog(
                UsageLog(
                    projectId = projectId,
                    providerId = "google_gemini",
                    modelId = "gemini-3.8-flash",
                    taskRole = "MASTER_PLANNING",
                    inputLanguage = BrainLanguage.BN.wireValue,
                    estimatedInputChars = 120L,
                    estimatedOutputChars = 640L,
                    latencyMs = 520L,
                    succeeded = true,
                    promptTokens = 30L,
                    completionTokens = 160L,
                    estimatedCostUsdMicros = 12L
                )
            )
        }
        assertTrue(logResult.isSuccess)
        val logs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeUsageLogs(aliceUid).first { it.any { item -> item.projectId == projectId } }
        }
        assertTrue(logs.isNotEmpty())
        assertEquals(30L, logs.first { it.projectId == projectId }.promptTokens)
    }

    @Test
    fun updateBackendConfiguration_createsWorkspaceDocWhenMissing() = runBlocking {
        val freshEmail = "fresh_${UUID.randomUUID().toString().take(8)}@test.com"
        val uid = signInTestUser(freshEmail)
        val repository = SuperBrainRepository(firestore, auth)

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateBackendConfiguration(
                backendServiceUrl = "https://myraa-fresh.example.run.app/",
                preferredBuildType = BuildType.RELEASE,
                defaultPackagePrefix = "com.myraa.fresh"
            )
        }
        assertTrue("Expected updateBackendConfiguration to succeed without pre-existing doc: ${saveResult.exceptionOrNull()}", saveResult.isSuccess)

        val workspace = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeWorkspace(uid).first { it != null && it.backendServiceUrl.isNotBlank() }
        }
        assertEquals("https://myraa-fresh.example.run.app", workspace?.backendServiceUrl)
        assertEquals(BuildType.RELEASE.wireValue, workspace?.preferredBuildType)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_myra@test.com"
        const val BOB_EMAIL = "bob_myra@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
