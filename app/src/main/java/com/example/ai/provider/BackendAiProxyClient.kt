package com.example.ai.provider

import com.example.core.security.FirebaseIdTokenFetcher
import com.example.core.security.FirebaseIdTokenProvider
import com.example.core.security.getAppCheckTokenBestEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackendHealthInfo(
    val service: String,
    val status: String,
    val timestampEpochMs: Long,
    val appCheckAutoRegistrarConfigured: Boolean = false,
    val latencyMs: Long = 0L
)

data class BackendRegistrarStatusInfo(
    val configured: Boolean,
    val credentialMode: String,
    val projectId: String,
    val androidPackage: String,
    val reason: String,
    val tokenRefreshedOnRetry: Boolean = false
)

data class ProxyTextResult(
    val providerId: ProviderId,
    val modelId: String,
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val latencyMs: Long,
    val fallbackUsed: Boolean = false,
    val fallbackFromProviderId: ProviderId? = null
)

fun mapStatusCodeToConnectionStatus(
    connected: Boolean,
    statusCode: String
): ProviderConnectionStatus {
    if (connected || statusCode == "CONNECTED_LIVE" || statusCode == "CONFIGURED_READY") {
        return ProviderConnectionStatus.CONNECTED_PROXY
    }
    return when (statusCode.uppercase(Locale.ROOT)) {
        "MISSING_API_KEY", "NOT_CONFIGURED_MISSING_KEY" -> ProviderConnectionStatus.MISSING_API_KEY
        "RATE_LIMITED" -> ProviderConnectionStatus.RATE_LIMITED
        "QUOTA_EXPIRED" -> ProviderConnectionStatus.QUOTA_EXPIRED
        "OFFLINE", "NETWORK_ERROR" -> ProviderConnectionStatus.OFFLINE
        "UNAVAILABLE", "PROVIDER_UNAVAILABLE", "CONFIGURATION_ERROR", "PROBE_FAILED",
        "FIREBASE_AUTH_REQUIRED", "APP_CHECK_REQUIRED" -> ProviderConnectionStatus.UNAVAILABLE
        "INVALID_API_KEY", "INVALID_KEY" -> ProviderConnectionStatus.PROBE_ERROR
        else -> ProviderConnectionStatus.PLANNED_PART_02
    }
}

fun classifyProviderError(throwable: Throwable): ProviderConnectionStatus {
    val rootList = generateSequence(throwable) { it.cause }.toList()
    val httpEx = rootList.filterIsInstance<ProviderHttpException>().firstOrNull()
    if (httpEx != null) {
        return when {
            httpEx.errorCode == "MISSING_API_KEY" -> ProviderConnectionStatus.MISSING_API_KEY
            httpEx.errorCode == "RATE_LIMITED" || httpEx.httpCode == 429 -> ProviderConnectionStatus.RATE_LIMITED
            httpEx.errorCode == "QUOTA_EXPIRED" || httpEx.httpCode == 402 -> ProviderConnectionStatus.QUOTA_EXPIRED
            httpEx.errorCode == "INVALID_API_KEY" || httpEx.errorCode == "INVALID_KEY" ->
                ProviderConnectionStatus.PROBE_ERROR
            httpEx.errorCode == "FIREBASE_AUTH_REQUIRED" || httpEx.errorCode == "APP_CHECK_REQUIRED" ||
                httpEx.errorCode == "CONFIGURATION_ERROR" ->
                ProviderConnectionStatus.UNAVAILABLE
            httpEx.errorCode == "OFFLINE" || httpEx.errorCode == "NETWORK_ERROR" -> ProviderConnectionStatus.OFFLINE
            httpEx.errorCode == "UNAVAILABLE" || httpEx.errorCode == "PROVIDER_UNAVAILABLE" ||
                httpEx.httpCode in setOf(500, 502, 503, 504) -> ProviderConnectionStatus.UNAVAILABLE
            else -> ProviderConnectionStatus.UNAVAILABLE
        }
    }

    if (rootList.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException }) {
        return ProviderConnectionStatus.OFFLINE
    }

    val combinedMsg = rootList.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
    return when {
        "missing" in combinedMsg && ("api key" in combinedMsg || "api_key" in combinedMsg || "token" in combinedMsg || "not configured" in combinedMsg) ->
            ProviderConnectionStatus.MISSING_API_KEY
        "rate limit" in combinedMsg || "too many requests" in combinedMsg || "429" in combinedMsg ->
            ProviderConnectionStatus.RATE_LIMITED
        "quota" in combinedMsg || "insufficient_quota" in combinedMsg || "credit" in combinedMsg || "402" in combinedMsg || "resource_exhausted" in combinedMsg ->
            ProviderConnectionStatus.QUOTA_EXPIRED
        "offline" in combinedMsg || "unreachable" in combinedMsg || "failed to connect" in combinedMsg || "timeout" in combinedMsg || "network" in combinedMsg ->
            ProviderConnectionStatus.OFFLINE
        "invalid api key" in combinedMsg || "unauthorized" in combinedMsg || "401" in combinedMsg ->
            ProviderConnectionStatus.PROBE_ERROR
        "503" in combinedMsg || "502" in combinedMsg || "unavailable" in combinedMsg ->
            ProviderConnectionStatus.UNAVAILABLE
        else -> ProviderConnectionStatus.UNAVAILABLE
    }
}

class BackendAiProxyClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val tokenFetcher: FirebaseIdTokenFetcher = FirebaseIdTokenProvider.defaultFetcher(),
    private val appCheckTokenProvider: suspend () -> String? = {
        getAppCheckTokenBestEffort(forceRefresh = false)
    }
) {
    constructor(
        httpClient: OkHttpClient = defaultHttpClient(),
        tokenProvider: suspend () -> String?,
        appCheckTokenProvider: suspend () -> String? = {
            getAppCheckTokenBestEffort(forceRefresh = false)
        }
    ) : this(
        httpClient = httpClient,
        tokenFetcher = FirebaseIdTokenFetcher { _ -> tokenProvider() },
        appCheckTokenProvider = appCheckTokenProvider
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkHealth(baseUrl: String): Result<BackendHealthInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val req = Request.Builder().url("$cleanBase/health").get().build()
            val startedMs = System.currentTimeMillis()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ProviderHttpException(resp.code, "UNAVAILABLE", "Backend health check failed: HTTP ${resp.code}")
                    }
                    val json = JSONObject(raw)
                    BackendHealthInfo(
                        service = json.optString("service", "myraa-backend"),
                        status = json.optString("status", "unknown"),
                        timestampEpochMs = json.optLong("timestampEpochMs", System.currentTimeMillis()),
                        appCheckAutoRegistrarConfigured = json.optBoolean("appCheckAutoRegistrarConfigured", false),
                        latencyMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
                    )
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Backend server unreachable: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun fetchRegistrarStatus(baseUrl: String): Result<BackendRegistrarStatusInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/appcheck/registrar/status")
                        .header("Authorization", "Bearer $bearer")
                        .get()
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Registrar status check failed: HTTP ${resp.code}")
                    }
                    val json = JSONObject(raw)
                    BackendRegistrarStatusInfo(
                        configured = json.optBoolean("configured", false),
                        credentialMode = json.optString("credentialMode", "NONE"),
                        projectId = json.optString("projectId", ""),
                        androidPackage = json.optString("androidPackage", ""),
                        reason = json.optString("reason", ""),
                        tokenRefreshedOnRetry = outcome.tokenRefreshedOnRetry
                    )
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Backend server unreachable: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun probeProviders(
        baseUrl: String,
        liveProbe: Boolean = true,
        freeOnly: Boolean = false
    ): Result<List<ProviderLiveProbeState>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }
            val query = buildString {
                append("probe=").append(if (liveProbe) "true" else "false")
                if (freeOnly) append("&freeOnly=true")
            }

            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/providers/status?$query")
                        .header("Authorization", "Bearer $bearer")
                        .apply {
                            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                        }
                        .get()
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Provider probe failed with HTTP ${resp.code}")
                    }
                    val root = JSONObject(raw)
                    val arr = root.getJSONArray("providers")
                    val now = System.currentTimeMillis()
                    (0 until arr.length()).mapNotNull { idx ->
                        parseProviderProbeJson(arr.getJSONObject(idx), now)
                    }
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Backend server unreachable: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun fetchProviderKeySummaries(baseUrl: String): Result<List<ProviderKeySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }

            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/providers/keys")
                        .header("Authorization", "Bearer $bearer")
                        .apply {
                            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                        }
                        .get()
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Failed to load key status: HTTP ${resp.code}")
                    }
                    val arr = JSONObject(raw).getJSONArray("providers")
                    (0 until arr.length()).mapNotNull { idx ->
                        val item = arr.getJSONObject(idx)
                        val pid = ProviderId.fromWire(item.optString("providerId")) ?: return@mapNotNull null
                        ProviderKeySummary(
                            providerId = pid,
                            displayName = item.optString("displayName", pid.displayName),
                            keyConfigured = item.optBoolean("keyConfigured", false),
                            keySource = item.optString("keySource", "NONE"),
                            maskedKeyHint = item.optString("maskedKeyHint", ""),
                            requiresAccountId = item.optBoolean("requiresAccountId", pid.requiresAccountId),
                            hasAccountId = item.optBoolean("hasAccountId", false),
                            statusCode = item.optString("statusCode", "MISSING_API_KEY")
                        )
                    }
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Backend server offline: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun saveProviderApiKey(
        baseUrl: String,
        providerId: ProviderId,
        apiKey: String,
        accountId: String = ""
    ): Result<ProviderLiveProbeState> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val cleanKey = apiKey.trim()
            require(cleanKey.length >= 8 && !cleanKey.any { it.isWhitespace() }) {
                "Enter a valid API key for ${providerId.displayName} (at least 8 characters, no spaces)."
            }
            if (providerId.requiresAccountId) {
                require(accountId.trim().isNotBlank()) { "Cloudflare Account ID is required along with API Token." }
            }

            val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }
            val payloadStr = JSONObject().apply {
                put("providerId", providerId.wireId)
                put("apiKey", cleanKey)
                if (accountId.isNotBlank()) {
                    put("accountId", accountId.trim())
                }
            }.toString()

            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/providers/keys")
                        .header("Authorization", "Bearer $bearer")
                        .apply {
                            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                        }
                        .post(payloadStr.toRequestBody(jsonMediaType))
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Failed to store API key: HTTP ${resp.code}")
                    }
                    val providerObj = JSONObject(raw).getJSONObject("provider")
                    parseProviderProbeJson(providerObj, System.currentTimeMillis())
                        ?: throw IllegalStateException("Backend returned unrecognized provider response.")
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Could not reach backend to save key: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun deleteProviderApiKey(
        baseUrl: String,
        providerId: ProviderId
    ): Result<ProviderLiveProbeState> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }

            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/providers/keys/${providerId.wireId}")
                        .header("Authorization", "Bearer $bearer")
                        .apply {
                            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                        }
                        .delete()
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Failed to delete API key: HTTP ${resp.code}")
                    }
                    val providerObj = JSONObject(raw).getJSONObject("provider")
                    parseProviderProbeJson(providerObj, System.currentTimeMillis())
                        ?: throw IllegalStateException("Backend returned unrecognized provider response.")
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Could not reach backend to delete key: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun generateViaProxy(
        baseUrl: String,
        providerId: ProviderId,
        modelId: String,
        systemPrompt: String,
        prompt: String,
        jsonMode: Boolean = false,
        allowFallback: Boolean = false
    ): Result<ProxyTextResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidUrl(baseUrl)
            val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }
            val payloadStr = JSONObject().apply {
                put("providerId", providerId.wireId)
                put("modelId", modelId)
                put("systemPrompt", systemPrompt)
                put("prompt", prompt)
                put("jsonMode", jsonMode)
                if (allowFallback) put("allowFallback", true)
            }.toString()
            val endpoint = if (jsonMode) "$cleanBase/v1/ai/plan" else "$cleanBase/v1/ai/generate"

            try {
                val outcome = executeAuthenticated { bearer ->
                    Request.Builder()
                        .url(endpoint)
                        .header("Authorization", "Bearer $bearer")
                        .apply {
                            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                        }
                        .post(payloadStr.toRequestBody(jsonMediaType))
                        .build()
                }
                outcome.response.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw parseHttpError(resp.code, raw, "Proxy AI call failed: HTTP ${resp.code}")
                    }
                    val json = JSONObject(raw)
                    val text = json.optString("text", "")
                    if (text.isBlank()) throw IllegalStateException("External provider returned empty output.")
                    val actualProvider = ProviderId.fromWire(json.optString("providerId")) ?: providerId
                    val fallbackFrom = json.optString("fallbackFromProviderId")
                        .takeIf { it.isNotBlank() }
                        ?.let { ProviderId.fromWire(it) }
                    ProxyTextResult(
                        providerId = actualProvider,
                        modelId = json.optString("modelId", modelId),
                        text = text,
                        promptTokens = json.optLong("promptTokens", TokenAndCostTracker.estimateTokens(prompt)),
                        completionTokens = json.optLong("completionTokens", TokenAndCostTracker.estimateTokens(text)),
                        latencyMs = json.optLong("latencyMs", 1L).coerceAtLeast(1L),
                        fallbackUsed = json.optBoolean("fallbackUsed", false),
                        fallbackFromProviderId = fallbackFrom
                    )
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Network unreachable for ${providerId.displayName}: ${io.localizedMessage}", io)
            }
        }
    }

    fun streamViaProxy(
        baseUrl: String,
        providerId: ProviderId,
        modelId: String,
        systemPrompt: String,
        prompt: String,
        allowFallback: Boolean = false
    ): Flow<String> = flow {
        val cleanBase = requireValidUrl(baseUrl)
        val appCheckToken = appCheckTokenProvider()?.takeIf { it.isNotBlank() }
        val payloadStr = JSONObject().apply {
            put("providerId", providerId.wireId)
            put("modelId", modelId)
            put("systemPrompt", systemPrompt)
            put("prompt", prompt)
            if (allowFallback) put("allowFallback", true)
        }.toString()

        val accumulated = StringBuilder()
        try {
            val outcome = executeAuthenticated { bearer ->
                Request.Builder()
                    .url("$cleanBase/v1/ai/stream")
                    .header("Authorization", "Bearer $bearer")
                    .apply {
                        if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
                    }
                    .post(payloadStr.toRequestBody(jsonMediaType))
                    .build()
            }
            outcome.response.use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    throw parseHttpError(resp.code, raw, "Proxy SSE stream failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty stream body")
                BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("data:")) {
                            val jsonStr = line.removePrefix("data:").trim()
                            if (jsonStr.isNotBlank()) {
                                val obj = JSONObject(jsonStr)
                                val delta = obj.optString("delta", "")
                                if (delta.isNotEmpty()) {
                                    accumulated.append(delta)
                                    emit(accumulated.toString())
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (io: IOException) {
            throw ProviderHttpException(503, "OFFLINE", "Stream connection dropped for ${providerId.displayName}: ${io.localizedMessage}", io)
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("External SSE stream finished without text output.")
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeAuthenticated(requestFactory: suspend (bearerToken: String) -> Request) =
        FirebaseIdTokenProvider.executeWithBearerRetry(
            httpClient = httpClient,
            tokenFetcher = tokenFetcher,
            onMissingToken = {
                ProviderHttpException(
                    httpCode = 401,
                    errorCode = "FIREBASE_AUTH_REQUIRED",
                    message = "Google Sign-In Firebase ID Token is required to call backend proxy."
                )
            },
            requestFactory = requestFactory
        )

    private fun parseProviderProbeJson(item: JSONObject, nowEpochMs: Long): ProviderLiveProbeState? {
        val pid = ProviderId.fromWire(item.optString("providerId")) ?: return null
        val connected = item.optBoolean("connected", false)
        val code = item.optString("statusCode", "UNKNOWN")
        val msg = item.optString("message", "")
        val keySource = item.optString("keySource", "NONE")
        val maskedKeyHint = item.optString("maskedKeyHint", "")
        val requiresAccountId = item.optBoolean("requiresAccountId", pid.requiresAccountId)
        val hasAccountId = item.optBoolean("hasAccountId", false)
        val freeTier = item.optBoolean("freeTier", pid.isFreeProvider)
        val modelsArr = item.optJSONArray("models")
        val discoveredModels = if (modelsArr != null) {
            (0 until modelsArr.length()).mapNotNull { i -> modelsArr.optString(i).takeIf { it.isNotBlank() } }
        } else {
            emptyList()
        }

        val errorCategory = item.optString("errorCategory", "")

        val resolvedStatus = mapStatusCodeToConnectionStatus(connected, code)
        return ProviderLiveProbeState(
            providerId = pid,
            status = resolvedStatus,
            statusCode = code,
            diagnosticMessage = msg,
            lastCheckedEpochMs = nowEpochMs,
            keySource = keySource,
            maskedKeyHint = maskedKeyHint,
            requiresAccountId = requiresAccountId,
            hasAccountId = hasAccountId,
            discoveredModels = discoveredModels,
            freeTier = freeTier,
            errorCategory = errorCategory
        )
    }

    private fun parseHttpError(httpCode: Int, rawBody: String, fallbackMsg: String): ProviderHttpException {
        val json = runCatching { JSONObject(rawBody) }.getOrNull()
        val msg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: fallbackMsg
        val rawCode = json?.optString("errorCode")?.takeIf { it.isNotBlank() }
        val isAppCheckAuthError = httpCode == 401 && rawCode == null && (
            msg.contains("App Check", ignoreCase = true) ||
                msg.contains("AppCheck", ignoreCase = true) ||
                msg.contains("attestation", ignoreCase = true)
            )
        val isFirebaseAuthError = httpCode == 401 && rawCode == null && !isAppCheckAuthError && (
            msg.contains("Firebase ID token", ignoreCase = true) ||
                msg.contains("Bearer", ignoreCase = true) ||
                msg.contains("JWT", ignoreCase = true) ||
                msg.contains("signing key", ignoreCase = true) ||
                msg.contains("audience", ignoreCase = true) ||
                msg.contains("issuer", ignoreCase = true) ||
                msg.contains("subject claim", ignoreCase = true) ||
                msg.contains("token algorithm", ignoreCase = true)
            )
        val isExplicitApiKeyMsg = msg.contains("api key", ignoreCase = true) ||
            msg.contains("api_key", ignoreCase = true) ||
            msg.contains("invalid key", ignoreCase = true)
        val resolvedCode = rawCode ?: when {
            isAppCheckAuthError -> "APP_CHECK_REQUIRED"
            isFirebaseAuthError -> "FIREBASE_AUTH_REQUIRED"
            httpCode == 401 && isExplicitApiKeyMsg -> "INVALID_API_KEY"
            httpCode == 401 -> "FIREBASE_AUTH_REQUIRED"
            httpCode == 402 -> "QUOTA_EXPIRED"
            httpCode == 429 -> "RATE_LIMITED"
            httpCode in setOf(400, 403, 404, 410, 422) -> "CONFIGURATION_ERROR"
            httpCode in setOf(502, 503, 504) -> "UNAVAILABLE"
            else -> "PROBE_FAILED"
        }
        return ProviderHttpException(httpCode = httpCode, errorCode = resolvedCode, message = msg)
    }

    companion object {
        private val LOCAL_HTTP_HOSTS = setOf("127.0.0.1", "10.0.2.2", "localhost")

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun validateBackendUrlError(rawUrl: String): String? {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) {
                return "Enter your deployed HTTPS Backend Server URL (or localhost emulator URL)."
            }
            if (trimmed.any { it.isWhitespace() }) {
                return "Backend URL cannot contain spaces."
            }
            val parsed = runCatching { URI(trimmed) }.getOrNull()
                ?: return "Invalid URL syntax. Example: https://myraa-super-brain-backend.onrender.com"
            val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
            val host = parsed.host?.lowercase(Locale.ROOT).orEmpty()
            if (host.isBlank()) {
                return "Backend URL must include a valid host name."
            }
            if (!parsed.userInfo.isNullOrBlank()) {
                return "Do not embed credentials inside the Backend URL."
            }
            return when {
                scheme == "https" -> null
                scheme == "http" && host in LOCAL_HTTP_HOSTS -> null
                scheme == "http" -> "Remote backends must use HTTPS (HTTP is only allowed for 127.0.0.1, 10.0.2.2, or localhost)."
                else -> "URL must start with https:// (or http://localhost for local testing)."
            }
        }

        fun requireValidUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('/')
            val error = validateBackendUrlError(trimmed)
            require(error == null) { error ?: "Invalid backend URL." }
            return trimmed
        }

        fun detectProviderKeyPrefixMismatch(providerId: ProviderId, rawKey: String): String? {
            val key = rawKey.trim()
            if (key.isEmpty()) return null
            return when {
                key.startsWith("sk-or-") && providerId != ProviderId.OPENROUTER_FREE ->
                    "Wrong provider card: keys starting with 'sk-or-' are OpenRouter keys, not ${providerId.displayName}. Paste this key under OpenRouter Free Models below${if (providerId == ProviderId.GROQ) ", or enter a Groq key starting with 'gsk_'" else ""}."
                key.startsWith("gsk_") && providerId != ProviderId.GROQ ->
                    "Wrong provider card: keys starting with 'gsk_' are Groq keys, not ${providerId.displayName}. Paste this key under Groq above."
                key.startsWith("nvapi-") && providerId != ProviderId.NVIDIA_NIM ->
                    "Wrong provider card: keys starting with 'nvapi-' are NVIDIA NIM keys, not ${providerId.displayName}. Paste this key under NVIDIA NIM."
                key.startsWith("sk-ant-") && providerId != ProviderId.ANTHROPIC_CLAUDE ->
                    "Wrong provider card: keys starting with 'sk-ant-' are Anthropic keys, not ${providerId.displayName}."
                else -> null
            }
        }
    }
}
