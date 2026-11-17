package com.example.ui.projects

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.BackendProxyAiProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.MultiAiOrchestrator
import com.example.ai.provider.ProviderId
import com.example.build.BuildServerClient
import com.example.builder.AppBuilderOrchestrator
import com.example.coding.CodingAgentOrchestrator
import com.example.core.security.BackendUrlLocalStore
import com.example.data.model.BrainProject
import com.example.data.model.UserWorkspace
import com.example.data.repository.SuperBrainRepository
import com.example.ui.brain.StreamUiState
import com.example.ui.theme.MyraaSuperBrainTheme
import com.example.ui.workspace.BackendConnectionStatus
import com.example.ui.workspace.EngineeringStudioViewModel
import com.example.ui.workspace.ProviderKeyVaultStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var localStore: BackendUrlLocalStore
    private lateinit var viewModel: EngineeringStudioViewModel
    private lateinit var server: HttpServer
    private lateinit var serverUrl: String
    private val backendUrlRef = AtomicReference("")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testUid = "ui_test_uid"
        localStore = BackendUrlLocalStore(context)
        localStore.writeBackendUrl(testUid, "")

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.myraa.qxkrmp")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key-for-emulator")
                    .build()
            ).also { initialized ->
                val fs = FirebaseFirestore.getInstance(
                    initialized,
                    "ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6"
                )
                fs.useEmulator("127.0.0.1", 8085)
                fs.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
                FirebaseAuth.getInstance(initialized).useEmulator("127.0.0.1", 9099)
            }
        } else {
            FirebaseApp.getInstance()
        }

        val firestore = FirebaseFirestore.getInstance(
            app,
            "ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6"
        )
        val auth = FirebaseAuth.getInstance(app)
        val repository = SuperBrainRepository(firestore, auth)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val body = """{"service":"myraa-super-brain-backend","status":"ok","appCheckAutoRegistrarConfigured":true,"timestampEpochMs":1700000000000}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/appcheck/registrar/status") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            if (auth != "Bearer test_firebase_id_token") {
                val err = """{"error":"Missing Bearer Firebase ID token."}""".toByteArray()
                ex.sendResponseHeaders(401, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val body = """{"configured":true,"credentialMode":"SERVICE_ACCOUNT_ENV","projectId":"gen-lang-client-0645829429","androidPackage":"com.aistudio.myraa.qxkrmp","reason":"Active"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/providers/status") { ex ->
            val body = """
                {
                  "providers": [
                    {"providerId": "groq", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing GROQ_API_KEY", "models": ["llama-3.3-70b-versatile"]},
                    {"providerId": "openrouter_free", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing OPENROUTER_API_KEY", "models": ["openrouter/free"]},
                    {"providerId": "mistral", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing MISTRAL_API_KEY", "models": ["mistral-small-latest"]},
                    {"providerId": "cohere", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing COHERE_API_KEY", "models": ["command-a-03-2025"]},
                    {"providerId": "cloudflare_ai", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "requiresAccountId": true, "hasAccountId": false, "keySource": "NONE", "maskedKeyHint": "", "message": "Missing Cloudflare credentials", "models": ["@cf/meta/llama-3.3-70b-instruct-fp8-fast"]},
                    {"providerId": "nvidia_nim", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing NVIDIA_NIM_API_KEY", "models": ["meta/llama-3.3-70b-instruct"]},
                    {"providerId": "sambanova", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing SAMBANOVA_API_KEY", "models": ["Meta-Llama-3.3-70B-Instruct"]}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/providers/keys") { ex ->
            val reqBody = ex.requestBody.bufferedReader().readText()
            val isBad = reqBody.contains("bad_invalid")
            val body = if (isBad) {
                """
                {
                  "saved": false,
                  "verified": false,
                  "provider": {
                    "providerId": "groq",
                    "freeTier": true,
                    "connected": false,
                    "statusCode": "INVALID_API_KEY",
                    "keyConfigured": false,
                    "keySource": "NONE",
                    "maskedKeyHint": "",
                    "message": "Groq rejected this API key (401 Unauthorized).",
                    "models": ["llama-3.3-70b-versatile"]
                  }
                }
                """.trimIndent().toByteArray()
            } else {
                """
                {
                  "saved": true,
                  "verified": true,
                  "provider": {
                    "providerId": "groq",
                    "freeTier": true,
                    "connected": true,
                    "statusCode": "CONNECTED_LIVE",
                    "keyConfigured": true,
                    "keySource": "USER_VAULT",
                    "maskedKeyHint": "gsk_••••5678",
                    "message": "Verified live against Groq.",
                    "models": ["llama-3.3-70b-versatile"]
                  }
                }
                """.trimIndent().toByteArray()
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        serverUrl = "http://127.0.0.1:${server.address.port}"

        val testOkHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        val proxyClient = BackendAiProxyClient(
            httpClient = testOkHttp,
            tokenProvider = { "test_firebase_id_token" },
            appCheckTokenProvider = { null }
        )
        val registry = ModelCapabilityRegistry(
            ProviderId.externalFreeProviders.map { pid ->
                BackendProxyAiProvider(pid, proxyClient) { backendUrlRef.get() }
            }
        )
        val multiAi = MultiAiOrchestrator(registry, proxyClient)
        val coding = CodingAgentOrchestrator(registry)
        val builder = AppBuilderOrchestrator(registry, coding)
        val buildClient = BuildServerClient(httpClient = testOkHttp, tokenProvider = { "test_firebase_id_token" })

        assertEquals(
            "https://myraa-super-brain-backend.onrender.com",
            com.example.BuildConfig.MYRAA_DEFAULT_BACKEND_URL
        )
        viewModel = EngineeringStudioViewModel(
            repository = repository,
            registry = registry,
            multiAiOrchestrator = multiAi,
            codingOrchestrator = coding,
            appBuilderOrchestrator = builder,
            buildServerClient = buildClient,
            activeProjectFlow = MutableStateFlow<BrainProject?>(null),
            workspaceStateFlow = MutableStateFlow<StreamUiState<UserWorkspace?>>(StreamUiState.Success(null)),
            onActivateProject = {},
            currentUserId = testUid,
            backendUrlLocalStore = localStore,
            defaultBackendUrl = ""
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun backendUrlSave_testConnection_andKeyVaultSaveButton_workEndToEnd() {
        composeTestRule.setContent {
            MyraaSuperBrainTheme {
                ProjectSettingsScreen(
                    studioViewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Initial state: URL NOT SET; Save Backend URL and Save & Verify Key disabled with clear feedback
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToKey("server_form")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backend_connection_status_chip")
            .assertTextContains("URL NOT SET", substring = true)
        composeTestRule.onNodeWithTag("settings_save_url_button")
            .assertIsNotEnabled()

        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToKey("vault_groq")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_save_key_groq")
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_validation_groq")
            .assertTextContains("Backend URL Missing", substring = true)

        // 2. Typing an invalid URL keeps Save Backend URL disabled
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToKey("server_form")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_backend_url_input")
            .performTextInput("http://insecure-remote.example.com")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_save_url_button")
            .assertIsNotEnabled()

        // 3. Typing a valid backend URL enables Save Backend URL & Test Connection, and clicking Save persists URL
        composeTestRule.onNodeWithTag("settings_backend_url_input")
            .performScrollTo()
            .performTextReplacement(serverUrl)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_save_url_button")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        waitForCondition("CONNECTED state after Save Backend URL") {
            viewModel.uiState.value.backendConnectionStatus == BackendConnectionStatus.CONNECTED
        }
        assertEquals(serverUrl, localStore.readBackendUrl("ui_test_uid"))
        composeTestRule.onNodeWithTag("backend_connection_status_chip")
            .performScrollTo()
            .assertTextContains("CONNECTED", substring = true)
        assertTrue(viewModel.uiState.value.backendAuthDiagStatus.contains("Verified", ignoreCase = true))
        assertTrue(viewModel.uiState.value.backendRegistrarDiagStatus.contains("SERVICE_ACCOUNT_ENV"))
        composeTestRule.onNodeWithTag("backend_diagnostics_panel")
            .performScrollTo()
            .assertExists()

        // 4. Short API key keeps button disabled with clear validation message
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToKey("vault_groq")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_key_input_groq")
            .performTextInput("short")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_save_key_groq").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_validation_groq")
            .assertTextContains("Invalid API Key", substring = true)

        // 5. Valid-format key enables Save & Verify Key; invalid upstream key preserves draft and shows INVALID_API_KEY
        composeTestRule.onNodeWithTag("vault_key_input_groq")
            .performTextReplacement("gsk_bad_invalid_0000")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_save_key_groq")
            .assertIsEnabled()
            .performClick()

        waitForCondition("INVALID_API_KEY vault feedback") {
            viewModel.uiState.value.providerVaultFeedback["groq"]?.status == ProviderKeyVaultStatus.INVALID_API_KEY
        }
        composeTestRule.onNodeWithTag("vault_feedback_groq")
            .assertTextContains("Invalid API Key", substring = true)

        // 6. Valid API key enables Save & Verify Key -> transitions to Verified
        composeTestRule.onNodeWithTag("vault_key_input_groq")
            .performTextReplacement("gsk_valid_live_5678")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_save_key_groq")
            .assertIsEnabled()
            .performClick()

        waitForCondition("VERIFIED vault feedback") {
            viewModel.uiState.value.providerVaultFeedback["groq"]?.status == ProviderKeyVaultStatus.VERIFIED
        }
        composeTestRule.onNodeWithTag("vault_feedback_groq")
            .assertTextContains("Verified", substring = true)
        assertTrue(viewModel.freeTierCatalogState.value.first { it.providerId == ProviderId.GROQ }.status.isOperational)

        // 7. Backend offline state: stopping server and clicking Test Connection shows BACKEND OFFLINE
        server.stop(0)
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToKey("server_form")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_test_connection_button")
            .performClick()
        waitForCondition("OFFLINE state after Test Connection on stopped server") {
            viewModel.uiState.value.backendConnectionStatus == BackendConnectionStatus.OFFLINE
        }
        composeTestRule.onNodeWithTag("backend_connection_status_chip")
            .assertTextContains("BACKEND OFFLINE", substring = true)
    }

    private fun waitForCondition(
        description: String,
        timeoutMs: Long = 8_000L,
        predicate: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeTestRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(25)
        }
        val s = viewModel.uiState.value
        throw AssertionError(
            "Timed out waiting for $description. actualStatus=${s.backendConnectionStatus}, detail='${s.backendConnectionDetail}', urlDraft='${s.backendUrlDraft}', error='${s.errorMessage}', feedback=${s.providerVaultFeedback}"
        )
    }
}
