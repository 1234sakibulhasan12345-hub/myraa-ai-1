package com.example.core.security

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ai.brain.MasterAiBrain
import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiModelProvider
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.BrainStreamEvent
import com.example.ai.provider.ModelCapability
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderId
import com.example.ai.provider.StructuredOrchestrationPlan
import com.example.data.model.BrainLanguage
import com.example.data.model.RoutingPolicy
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppCheckGuardTest {

    private fun ensureTestFirebaseApp(context: Context): FirebaseApp {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context) ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:514839656668:android:fe8f5c54f44c419f555c19")
                    .setProjectId("gen-lang-client-0645829429")
                    .setApiKey("fake-test-api-key")
                    .build()
            )
        }
        return FirebaseApp.getInstance()
    }

    @Test
    fun isAppCheckTokenError_matchesInvalidTokenAndCausalChainOnly() {
        assertTrue(isAppCheckTokenError(IllegalStateException("Firebase App Check token is invalid.")))
        assertTrue(
            isAppCheckTokenError(
                RuntimeException("Wrapper", IllegalStateException("Error obtaining AppCheck token"))
            )
        )
        assertTrue(isAppCheckTokenError(IllegalStateException("App attestation failed: 403")))
        assertTrue(isAppCheckTokenError(IllegalStateException("Too many attempts.")))
        assertTrue(isAppCheckTokenError(IllegalStateException("RESOURCE_EXHAUSTED: AppCheck throttled")))

        assertFalse(isAppCheckTokenError(IllegalStateException("Firebase ID token has expired.")))
        assertFalse(isAppCheckTokenError(IllegalStateException("Socket timeout connecting to model")))
        assertFalse(isAppCheckTokenError(IllegalArgumentException("Malformed JSON output")))
    }

    @Test
    fun configureAppCheck_debugIntentExtraCommitsSynchronouslyAndGuidesConsoleSetup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = ensureTestFirebaseApp(context)
        val storeName = "com.google.firebase.appcheck.debug.store.${app.persistenceKey}"
        val secretKey = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
        val prefs = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        prefs.edit().remove(secretKey).commit()

        val testIntentSecret = "11111111-2222-3333-4444-555555555555"
        val launchIntent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", testIntentSecret)

        configureAppCheck(context, launchIntent)

        assertEquals(testIntentSecret, prefs.getString(secretKey, null))
        val statusWithExtra = appCheckDiagnosticFlow.value
        assertEquals(AppCheckHealthStatus.INITIALIZED, statusWithExtra.status)
        assertEquals("DebugAppCheckProviderFactory", statusWithExtra.providerName)
        assertTrue(statusWithExtra.isDebugBuild)
        assertTrue(statusWithExtra.hasIntentDebugSecret)
        assertEquals(testIntentSecret, statusWithExtra.activeDebugSecret)

        // Test without intent extra (Phone APK launch flow):
        // Automatically generates and stores a stable UUID debug secret so phone users can copy it
        prefs.edit().remove(secretKey).commit()
        configureAppCheck(context, Intent())

        val statusWithoutExtra = appCheckDiagnosticFlow.value
        assertEquals(AppCheckHealthStatus.INITIALIZED, statusWithoutExtra.status)
        assertFalse(statusWithoutExtra.hasIntentDebugSecret)
        assertTrue(statusWithoutExtra.lastMessage.contains("in-app debug token"))
        assertTrue(statusWithoutExtra.lastMessage.contains("Automatic registration"))
        val autoToken = prefs.getString(secretKey, null)
        assertTrue("Expected auto-generated UUID secret in SharedPreferences", !autoToken.isNullOrBlank())
        assertEquals(autoToken, statusWithoutExtra.activeDebugSecret)

        // Test token regeneration (clearing lockout, installing fresh provider, and creating a fresh UUID)
        val appCheckInstance = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
        val getFactoryMethod = appCheckInstance.javaClass.getMethod("getInstalledAppCheckProviderFactory")
        val providerBeforeRegen = getFactoryMethod.invoke(appCheckInstance)
        val regenerated = regenerateAndStoreDebugToken(context)
        val providerAfterRegen = getFactoryMethod.invoke(appCheckInstance)
        assertTrue("Expected non-empty regenerated UUID", regenerated.isNotBlank())
        assertTrue("Expected fresh UUID different from previous autoToken", regenerated != autoToken)
        assertTrue("Expected fresh AppCheckProviderFactory instance in-process", providerBeforeRegen !== providerAfterRegen)
        assertEquals(regenerated, prefs.getString(secretKey, null))
        val statusRegenerated = appCheckDiagnosticFlow.value
        assertEquals(regenerated, statusRegenerated.activeDebugSecret)
        assertFalse(statusRegenerated.isCooldownActive)
    }

    @Test
    fun appCheckRecoveryHelper_refreshesOnceAndSucceedsOnSingleRetry() = runBlocking {
        var refreshCount = 0
        var blockCount = 0
        val recordedStates = mutableListOf<AppCheckHealthStatus>()

        val helper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                refreshCount++
                Result.success("fresh-valid-appcheck-jwt")
            },
            onStatusChanged = { status, _ -> recordedStates.add(status) },
            isDebugBuild = true,
            providerNameProvider = { "DebugAppCheckProviderFactory" }
        )

        val result = helper.runWithSingleRetry("Gemini test call") {
            blockCount++
            if (blockCount == 1) {
                throw IllegalStateException("Firebase App Check token is invalid.")
            }
            "ok-after-refresh"
        }

        assertEquals("ok-after-refresh", result)
        assertEquals(1, refreshCount)
        assertEquals(2, blockCount)
        assertEquals(
            listOf(AppCheckHealthStatus.REFRESHING, AppCheckHealthStatus.TOKEN_VALID),
            recordedStates
        )
    }

    @Test
    fun appCheckRecoveryHelper_stopsAfterOneRetryAndSurfacesDiagnosticWhenStillInvalid() = runBlocking {
        var refreshCount = 0
        var blockCount = 0
        val recordedStates = mutableListOf<AppCheckHealthStatus>()

        val helper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                refreshCount++
                Result.success("still-unregistered-debug-jwt")
            },
            onStatusChanged = { status, _ -> recordedStates.add(status) },
            isDebugBuild = true,
            providerNameProvider = { "DebugAppCheckProviderFactory" }
        )

        try {
            helper.runWithSingleRetry("Gemini planning") {
                blockCount++
                throw IllegalStateException("Firebase App Check token is invalid.")
            }
            fail("Expected AppCheckDiagnosticException")
        } catch (e: AppCheckDiagnosticException) {
            assertEquals(1, refreshCount)
            assertEquals(2, blockCount)
            assertEquals(AppCheckHealthStatus.TOKEN_INVALID, recordedStates.last())
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("DebugAppCheckProvider"))
            assertTrue(msg.contains("Manage debug tokens"))
            assertTrue(msg.contains("com.aistudio.myraa.qxkrmp"))
        }
    }

    @Test
    fun appCheckRecoveryHelper_abortsWhenRefreshFailsAndSkipsRefreshForNonAppCheckErrors() = runBlocking {
        var refreshCount = 0
        var blockCount = 0

        val failingRefreshHelper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                refreshCount++
                Result.failure(IllegalStateException("403 App Check token exchange rejected"))
            },
            onStatusChanged = { _, _ -> },
            isDebugBuild = true,
            providerNameProvider = { "DebugAppCheckProviderFactory" }
        )

        try {
            failingRefreshHelper.runWithSingleRetry("Gemini planning") {
                blockCount++
                throw IllegalStateException("Firebase App Check token is invalid.")
            }
            fail("Expected AppCheckDiagnosticException when token refresh fails")
        } catch (_: AppCheckDiagnosticException) {
            assertEquals(1, refreshCount)
            assertEquals(1, blockCount)
        }

        var nonAppCheckRefreshCount = 0
        var nonAppCheckBlockCount = 0
        val nonAppCheckHelper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                nonAppCheckRefreshCount++
                Result.success("unused")
            },
            onStatusChanged = { _, _ -> },
            isDebugBuild = true
        )

        try {
            nonAppCheckHelper.runWithSingleRetry("Network call") {
                nonAppCheckBlockCount++
                throw IllegalArgumentException("Unrelated validation bug")
            }
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unrelated validation bug", e.message)
            assertEquals(0, nonAppCheckRefreshCount)
            assertEquals(1, nonAppCheckBlockCount)
        }
    }

    @Test
    fun masterAiBrain_doesNotMaskAppCheckErrorWithFallbackModelRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val failingAppCheckProvider = object : AiModelProvider {
            override val descriptor: AiProviderDescriptor = AiProviderDescriptor(
                providerId = ProviderId.GOOGLE_GEMINI,
                displayName = "Google Gemini (Test)",
                status = ProviderConnectionStatus.CONNECTED_LIVE,
                securityNote = "App Check test",
                models = listOf(
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.8 Flash",
                        roleSummary = "Primary",
                        capabilities = setOf(ModelCapability.REASONING),
                        latencyTier = "Fast",
                        costTier = "Free",
                        isLiveInCurrentBuild = true
                    ),
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH_LITE,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.5 Flash-Lite",
                        roleSummary = "Fallback",
                        capabilities = setOf(ModelCapability.FAST_CHAT),
                        latencyTier = "Ultra Fast",
                        costTier = "Free",
                        isLiveInCurrentBuild = true
                    )
                )
            )

            override suspend fun analyzeAndPlan(
                request: BrainGenerationRequest,
                modelId: String
            ): StructuredOrchestrationPlan {
                calls.add(modelId)
                throw AppCheckDiagnosticException(
                    message = "Firebase App Check token is invalid. Register Debug UUID in Firebase Console.",
                    isDebugBuild = true,
                    providerName = "DebugAppCheckProviderFactory"
                )
            }

            override fun streamSynthesizedResponse(
                request: BrainGenerationRequest,
                plan: StructuredOrchestrationPlan,
                modelId: String
            ): Flow<String> = flow {}
        }

        val registry = ModelCapabilityRegistry(listOf(failingAppCheckProvider))
        val brain = MasterAiBrain(registry, ModelRouter(registry))

        try {
            brain.execute(
                request = BrainGenerationRequest(
                    prompt = "Plan an Android chat app",
                    languagePreference = BrainLanguage.EN,
                    routingPolicy = RoutingPolicy.AUTO_BALANCED,
                    projectContextSummary = "",
                    recentHistory = emptyList()
                ),
                onPhaseChanged = {}
            ).toList()
            fail("Expected AppCheckDiagnosticException to propagate without fallback masking")
        } catch (e: AppCheckDiagnosticException) {
            assertEquals(listOf(ModelRouter.MODEL_GEMINI_FLASH), calls)
            assertTrue(e.message.orEmpty().contains("Firebase App Check token is invalid"))
        }
    }

    @Test
    fun appCheckRecoveryHelper_failsFastDuringRateLimitCooldownWithoutCallingFirebase() = runBlocking {
        var blockCalls = 0
        var refreshCalls = 0
        val helper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                refreshCalls++
                Result.success("should-not-run")
            },
            onStatusChanged = { _, _ -> },
            isDebugBuild = true,
            providerNameProvider = { "DebugAppCheckProviderFactory" },
            isRateLimitedCooldownActive = { true },
            cooldownSecondsRemainingProvider = { 27 }
        )

        try {
            helper.runWithSingleRetry("Gemini planning") {
                blockCalls++
                "should-not-run"
            }
            fail("Expected AppCheckDiagnosticException during active rate-limit cooldown")
        } catch (e: AppCheckDiagnosticException) {
            assertEquals(0, blockCalls)
            assertEquals(0, refreshCalls)
            assertTrue(e.message.orEmpty().contains("27s"))
            assertTrue(e.message.orEmpty().contains("Reset / New"))
        }
    }

    @Test
    fun appCheckRecoveryHelper_preservesRateLimitedStatusWhenRefreshIsThrottled() = runBlocking {
        val recordedStates = mutableListOf<AppCheckHealthStatus>()
        val helper = AppCheckRecoveryHelper(
            forceRefreshToken = {
                Result.failure(
                    AppCheckDiagnosticException(
                        message = "Firebase App Check rate limit reached (Too many attempts).",
                        isDebugBuild = true,
                        providerName = "DebugAppCheckProviderFactory"
                    )
                )
            },
            onStatusChanged = { status, _ -> recordedStates.add(status) },
            isDebugBuild = true,
            providerNameProvider = { "DebugAppCheckProviderFactory" },
            isRateLimitedCooldownActive = { false }
        )

        try {
            helper.runWithSingleRetry("Gemini generation") {
                throw IllegalStateException("Firebase App Check token rejected (Too many attempts).")
            }
            fail("Expected AppCheckDiagnosticException")
        } catch (_: AppCheckDiagnosticException) {
            assertEquals(AppCheckHealthStatus.RATE_LIMITED, recordedStates.last())
        }
    }

    @Test
    fun appCheckAutoCoordinator_registersDebugTokenWithBackendAndVerifiesAcceptance() = runBlocking {
        val secret = "85db9e7c-1b4c-4296-bd09-2d106f22b2b4"
        var currentStatus = AppCheckDiagnosticStatus(
            status = AppCheckHealthStatus.INITIALIZED,
            isDebugBuild = true,
            providerName = "DebugAppCheckProviderFactory",
            activeDebugSecret = secret,
            backendUrlConfigured = true
        )
        var verifiedSecretCache: String? = null
        var prepareCalls = 0
        var backendCalls = 0
        var firebaseRefreshCalls = 0
        val observedStatuses = mutableListOf<AppCheckHealthStatus>()

        val coordinator = AppCheckAutoCoordinator(
            statusProvider = { currentStatus },
            updateStatus = { fn ->
                currentStatus = fn(currentStatus)
                observedStatuses.add(currentStatus.status)
            },
            backendUrlProvider = { "https://myraa-backend.example.run.app" },
            readVerifiedSecret = { verifiedSecretCache },
            writeVerifiedSecret = { verifiedSecretCache = it },
            registerWithBackend = { url, token ->
                backendCalls++
                assertEquals("https://myraa-backend.example.run.app", url)
                assertEquals(secret, token)
                Result.success(
                    AppCheckAutoRegisterResponse(
                        registered = true,
                        alreadyRegistered = false,
                        prunedCount = 0,
                        tokenName = "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/tok1",
                        displayName = "[MYRAA-AUTO] TECNO BG6 (user123)",
                        maskedToken = "85db9e7c-****"
                    )
                )
            },
            prepareDebugRefresh = { prepareCalls++ },
            forceFirebaseRefresh = {
                firebaseRefreshCalls++
                Result.success("valid-firebase-appcheck-jwt")
            },
            resetBackoff = {},
            recordFailure = { msg, _, err -> Result.failure(err ?: IllegalStateException(msg)) },
            propagationDelayMs = 0L
        )

        val result = coordinator.ensureRegisteredAndVerified()
        assertTrue(result.isSuccess)
        assertEquals(1, backendCalls)
        assertEquals(1, prepareCalls)
        assertEquals(1, firebaseRefreshCalls)
        assertEquals(secret, verifiedSecretCache)
        assertEquals(AppCheckHealthStatus.TOKEN_VALID, currentStatus.status)
        assertTrue(currentStatus.autoRegisteredWithBackend)
        assertEquals(
            listOf(
                AppCheckHealthStatus.AUTO_REGISTERING,
                AppCheckHealthStatus.REFRESHING,
                AppCheckHealthStatus.TOKEN_VALID
            ),
            observedStatuses
        )

        // Subsequent startup call while TOKEN_VALID short-circuits without network calls
        val second = coordinator.ensureRegisteredAndVerified()
        assertTrue(second.isSuccess)
        assertEquals(1, backendCalls)
        assertEquals(1, firebaseRefreshCalls)
    }

    @Test
    fun appCheckAutoCoordinator_failsClosedIfFirebaseStillRejectsAndBacksOffWithoutFirebaseCallOnBackendError() = runBlocking {
        val secret = "99999999-1111-4222-8333-444444444444"
        var statusA = AppCheckDiagnosticStatus(
            status = AppCheckHealthStatus.INITIALIZED,
            isDebugBuild = true,
            providerName = "DebugAppCheckProviderFactory",
            activeDebugSecret = secret,
            backendUrlConfigured = true
        )
        var verifiedCacheA: String? = null
        var firebaseCallsWhenBackendFails = 0

        // 1. Backend HTTP 503 missing secret -> applies backoff and NEVER burns Firebase quota
        val backendDownCoordinator = AppCheckAutoCoordinator(
            statusProvider = { statusA },
            updateStatus = { fn -> statusA = fn(statusA) },
            backendUrlProvider = { "https://myraa-backend.example.run.app" },
            readVerifiedSecret = { verifiedCacheA },
            writeVerifiedSecret = { verifiedCacheA = it },
            registerWithBackend = { _, _ ->
                Result.failure(
                    AppCheckBackendRegistrarException(
                        httpCode = 503,
                        errorCode = "SERVICE_ACCOUNT_NOT_CONFIGURED",
                        message = "Missing FIREBASE_SERVICE_ACCOUNT_JSON on backend."
                    )
                )
            },
            prepareDebugRefresh = {},
            forceFirebaseRefresh = {
                firebaseCallsWhenBackendFails++
                Result.success("should-not-execute")
            },
            resetBackoff = {},
            recordFailure = { msg, _, err ->
                statusA = statusA.copy(
                    status = AppCheckHealthStatus.TOKEN_INVALID,
                    nextAllowedRefreshEpochMs = System.currentTimeMillis() + 5_000L,
                    lastMessage = msg
                )
                Result.failure(err ?: IllegalStateException(msg))
            },
            propagationDelayMs = 0L
        )

        val fail503 = backendDownCoordinator.ensureRegisteredAndVerified()
        assertTrue(fail503.isFailure)
        assertEquals(0, firebaseCallsWhenBackendFails)
        assertNull(verifiedCacheA)
        assertEquals(AppCheckHealthStatus.TOKEN_INVALID, statusA.status)
        assertTrue(statusA.isCooldownActive)

        // Calling again immediately while cooldown is active fails fast cleanly
        val failCooldown = backendDownCoordinator.ensureRegisteredAndVerified()
        assertTrue(failCooldown.isFailure)
        assertTrue(failCooldown.exceptionOrNull()?.message.orEmpty().contains("cooldown"))

        // 2. Backend succeeds, but Firebase still rejects token -> fails closed (never marks TOKEN_VALID)
        var statusB = AppCheckDiagnosticStatus(
            status = AppCheckHealthStatus.INITIALIZED,
            isDebugBuild = true,
            providerName = "DebugAppCheckProviderFactory",
            activeDebugSecret = secret,
            backendUrlConfigured = true
        )
        var verifiedCacheB: String? = "stale-value"
        val rejectedAfterRegCoordinator = AppCheckAutoCoordinator(
            statusProvider = { statusB },
            updateStatus = { fn -> statusB = fn(statusB) },
            backendUrlProvider = { "https://myraa-backend.example.run.app" },
            readVerifiedSecret = { verifiedCacheB },
            writeVerifiedSecret = { verifiedCacheB = it },
            registerWithBackend = { _, _ ->
                Result.success(
                    AppCheckAutoRegisterResponse(
                        registered = true,
                        alreadyRegistered = false,
                        prunedCount = 0,
                        tokenName = "tok",
                        displayName = "[MYRAA-AUTO] TECNO BG6",
                        maskedToken = "99999999-****"
                    )
                )
            },
            prepareDebugRefresh = {},
            forceFirebaseRefresh = {
                statusB = statusB.copy(status = AppCheckHealthStatus.TOKEN_INVALID)
                Result.failure(
                    AppCheckDiagnosticException(
                        message = "403 App attestation failed",
                        isDebugBuild = true,
                        providerName = "DebugAppCheckProviderFactory"
                    )
                )
            },
            resetBackoff = {},
            recordFailure = { msg, _, err -> Result.failure(err ?: IllegalStateException(msg)) },
            propagationDelayMs = 0L
        )

        val rejectedResult = rejectedAfterRegCoordinator.ensureRegisteredAndVerified(forceAutoRegister = true)
        assertTrue(rejectedResult.isFailure)
        assertNull(verifiedCacheB)
        assertEquals(AppCheckHealthStatus.TOKEN_INVALID, statusB.status)
    }

    @Test
    fun backendAppCheckRegistrarClient_postsAuthenticatedPayloadAndSurfacesErrors() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var capturedAuthHeader = ""
        var capturedBody = ""
        var respondWithError = false

        server.createContext("/v1/appcheck/debug-tokens/register") { ex ->
            capturedAuthHeader = ex.requestHeaders.getFirst("Authorization").orEmpty()
            capturedBody = ex.requestBody.bufferedReader().readText()
            if (respondWithError) {
                val err = """{"error":"Missing FIREBASE_SERVICE_ACCOUNT_JSON","code":"SERVICE_ACCOUNT_NOT_CONFIGURED"}""".toByteArray()
                ex.sendResponseHeaders(503, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            } else {
                val ok = """
                    {
                      "registered": true,
                      "alreadyRegistered": false,
                      "prunedCount": 1,
                      "tokenName": "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/abc",
                      "displayName": "[MYRAA-AUTO] TECNO BG6 (uid1)",
                      "maskedToken": "85db9e7c-****"
                    }
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, ok.size.toLong())
                ex.responseBody.use { it.write(ok) }
            }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            val client = BackendAppCheckRegistrarClient(
                idTokenProvider = { "mock-firebase-id-token" },
                deviceModelProvider = { "TECNO BG6" }
            )
            val okRes = client.registerDebugToken(baseUrl, "85db9e7c-1b4c-4296-bd09-2d106f22b2b4")
            assertTrue(okRes.isSuccess)
            val data = okRes.getOrThrow()
            assertEquals(true, data.registered)
            assertEquals(1, data.prunedCount)
            assertEquals("Bearer mock-firebase-id-token", capturedAuthHeader)
            assertTrue(capturedBody.contains("com.aistudio.myraa.qxkrmp"))
            assertTrue(capturedBody.contains("TECNO BG6"))

            respondWithError = true
            val errRes = client.registerDebugToken(baseUrl, "85db9e7c-1b4c-4296-bd09-2d106f22b2b4")
            assertTrue(errRes.isFailure)
            val ex = errRes.exceptionOrNull() as AppCheckBackendRegistrarException
            assertEquals(503, ex.httpCode)
            assertEquals("SERVICE_ACCOUNT_NOT_CONFIGURED", ex.errorCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun appCheckRecoveryHelper_preFlightRegistersBeforeGeminiExecutes() = runBlocking {
        var preFlightCount = 0
        var blockCount = 0
        val helper = AppCheckRecoveryHelper(
            forceRefreshToken = { Result.success("unused") },
            onStatusChanged = { _, _ -> },
            isDebugBuild = true,
            isRateLimitedCooldownActive = { false },
            preFlightBootstrap = {
                preFlightCount++
                Result.success("auto-registered-and-verified")
            }
        )

        val value = helper.runWithSingleRetry("Gemini chat") {
            blockCount++
            assertEquals(1, preFlightCount)
            "gemini-response-ok"
        }

        assertEquals("gemini-response-ok", value)
        assertEquals(1, preFlightCount)
        assertEquals(1, blockCount)
    }

    @Test
    fun backendAppCheckRegistrarClient_refreshesExpiredIdTokenAndRetriesOn401() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val authHeadersSeen = mutableListOf<String>()
        server.createContext("/v1/appcheck/debug-tokens/register") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            authHeadersSeen.add(auth)
            if (auth != "Bearer fresh-firebase-jwt") {
                val err = """{"error":"Firebase ID token has expired."}""".toByteArray()
                ex.sendResponseHeaders(401, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val ok = """
                {
                  "registered": true,
                  "alreadyRegistered": true,
                  "prunedCount": 0,
                  "tokenName": "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/xyz",
                  "displayName": "[MYRAA-AUTO] TECNO BG6 (uid1)",
                  "maskedToken": "85db9e7c-****"
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        try {
            val forceRefreshCalls = mutableListOf<Boolean>()
            val client = BackendAppCheckRegistrarClient(
                tokenFetcher = FirebaseIdTokenFetcher { force ->
                    forceRefreshCalls.add(force)
                    if (force) "fresh-firebase-jwt" else "expired-firebase-jwt"
                },
                deviceModelProvider = { "TECNO BG6" }
            )

            val res = client.registerDebugToken(baseUrl, "85db9e7c-1b4c-4296-bd09-2d106f22b2b4").getOrThrow()
            assertTrue(res.registered)
            assertTrue(res.tokenRefreshedOnRetry)
            assertEquals(listOf(false, true), forceRefreshCalls)
            assertEquals(listOf("Bearer expired-firebase-jwt", "Bearer fresh-firebase-jwt"), authHeadersSeen)
        } finally {
            server.stop(0)
        }
    }
}
