package com.example.core.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale

private const val TAG = "AppCheckInit"
private const val AUTO_META_PREFS = "com.example.core.security.appcheck_auto_meta"
private const val KEY_LAST_VERIFIED_SECRET = "last_verified_debug_secret"

enum class AppCheckHealthStatus(val label: String) {
    UNINITIALIZED("NOT INITIALIZED"),
    INITIALIZED("DEBUG TOKEN GENERATED"),
    AUTO_REGISTERING("AUTO-REGISTERING"),
    REFRESHING("VERIFYING TOKEN"),
    TOKEN_VALID("TOKEN VERIFIED"),
    TOKEN_INVALID("TOKEN NOT REGISTERED"),
    RATE_LIMITED("TOO MANY ATTEMPTS")
}

data class AppCheckDiagnosticStatus(
    val status: AppCheckHealthStatus = AppCheckHealthStatus.UNINITIALIZED,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val providerName: String = if (BuildConfig.DEBUG) "DebugAppCheckProviderFactory" else "PlayIntegrityAppCheckProviderFactory",
    val hasIntentDebugSecret: Boolean = false,
    val activeDebugSecret: String? = null,
    val lastMessage: String = "App Check has not been configured yet.",
    val lastCheckedEpochMs: Long = 0L,
    val nextAllowedRefreshEpochMs: Long = 0L,
    val consecutiveFailureCount: Int = 0,
    val backendUrlConfigured: Boolean = false,
    val autoRegisterAttempted: Boolean = false,
    val autoRegisteredWithBackend: Boolean = false,
    val autoRegistrationSummary: String? = null
) {
    val isCooldownActive: Boolean
        get() = System.currentTimeMillis() < nextAllowedRefreshEpochMs

    val cooldownSecondsRemaining: Int
        get() {
            val now = System.currentTimeMillis()
            return if (now < nextAllowedRefreshEpochMs) {
                ((nextAllowedRefreshEpochMs - now + 999) / 1000).toInt().coerceAtLeast(1)
            } else {
                0
            }
        }
}

internal data class AppCheckInstallOutcome(
    val providerName: String,
    val isDebugBuild: Boolean,
    val hasIntentDebugSecret: Boolean,
    val installedSuccessfully: Boolean,
    val detailMessage: String,
    val debugSecret: String? = null
)

class AppCheckDiagnosticException(
    message: String,
    val isDebugBuild: Boolean,
    val providerName: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private val backoffLock = Any()
@Volatile private var nextAllowedRefreshEpochMs: Long = 0L
@Volatile private var consecutiveFailureCount: Int = 0
@Volatile private var initializedAppContext: Context? = null
@Volatile private var configuredBackendUrl: String = ""

private val defaultRegistrarClient by lazy { BackendAppCheckRegistrarClient() }
private val _appCheckDiagnosticFlow = MutableStateFlow(AppCheckDiagnosticStatus())
val appCheckDiagnosticFlow: StateFlow<AppCheckDiagnosticStatus> = _appCheckDiagnosticFlow.asStateFlow()

internal fun updateAppCheckStatus(
    status: AppCheckHealthStatus,
    message: String
) {
    val prev = _appCheckDiagnosticFlow.value
    _appCheckDiagnosticFlow.value = prev.copy(
        status = status,
        lastMessage = message,
        lastCheckedEpochMs = System.currentTimeMillis()
    )
}

fun setAppCheckBackendUrl(url: String) {
    val clean = url.trim().trimEnd('/')
    configuredBackendUrl = clean
    val prev = _appCheckDiagnosticFlow.value
    if (prev.backendUrlConfigured != clean.isNotBlank()) {
        _appCheckDiagnosticFlow.value = prev.copy(backendUrlConfigured = clean.isNotBlank())
    }
}

fun getAppCheckBackendUrl(): String = configuredBackendUrl

fun resetAppCheckBackoff() {
    synchronized(backoffLock) {
        nextAllowedRefreshEpochMs = 0L
        consecutiveFailureCount = 0
    }
    val current = _appCheckDiagnosticFlow.value
    _appCheckDiagnosticFlow.value = current.copy(
        nextAllowedRefreshEpochMs = 0L,
        consecutiveFailureCount = 0
    )
}

private fun computeBackoffMillis(failureCount: Int, isRateLimitError: Boolean): Long {
    if (isRateLimitError) {
        return (30_000L * failureCount.coerceAtMost(3)).coerceIn(30_000L, 90_000L)
    }
    val factor = (1L shl minOf(failureCount - 1, 4)).coerceAtLeast(1L)
    return (5_000L * factor).coerceAtMost(60_000L)
}

private fun readLastVerifiedDebugSecret(context: Context?): String? {
    val ctx = context ?: return null
    return ctx.getSharedPreferences(AUTO_META_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_VERIFIED_SECRET, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun writeLastVerifiedDebugSecret(context: Context?, secret: String?) {
    val ctx = context ?: return
    val prefs = ctx.getSharedPreferences(AUTO_META_PREFS, Context.MODE_PRIVATE)
    if (secret.isNullOrBlank()) {
        prefs.edit().remove(KEY_LAST_VERIFIED_SECRET).commit()
    } else {
        prefs.edit().putString(KEY_LAST_VERIFIED_SECRET, secret.trim()).commit()
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        initializedAppContext = context.applicationContext ?: context
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val outcome = installBuildVariantAppCheckProvider(context, intent)
        val existing = _appCheckDiagnosticFlow.value
        _appCheckDiagnosticFlow.value = AppCheckDiagnosticStatus(
            status = if (outcome.installedSuccessfully) AppCheckHealthStatus.INITIALIZED else AppCheckHealthStatus.TOKEN_INVALID,
            isDebugBuild = outcome.isDebugBuild,
            providerName = outcome.providerName,
            hasIntentDebugSecret = outcome.hasIntentDebugSecret,
            activeDebugSecret = outcome.debugSecret ?: existing.activeDebugSecret,
            lastMessage = outcome.detailMessage,
            lastCheckedEpochMs = System.currentTimeMillis(),
            nextAllowedRefreshEpochMs = existing.nextAllowedRefreshEpochMs,
            consecutiveFailureCount = existing.consecutiveFailureCount,
            backendUrlConfigured = configuredBackendUrl.isNotBlank()
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
        _appCheckDiagnosticFlow.value = _appCheckDiagnosticFlow.value.copy(
            status = AppCheckHealthStatus.TOKEN_INVALID,
            lastMessage = "App Check initialization failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
            lastCheckedEpochMs = System.currentTimeMillis()
        )
    }
}

fun regenerateAndStoreDebugToken(context: Context): String {
    val appCtx = context.applicationContext ?: context
    initializedAppContext = appCtx
    writeLastVerifiedDebugSecret(appCtx, null)
    val newSecret = regenerateDebugSecretVariant(appCtx)
    resetAppCheckBackoff()
    val current = _appCheckDiagnosticFlow.value
    val msg = if (newSecret.isNotBlank()) {
        if (configuredBackendUrl.isNotBlank()) {
            "Generated fresh debug token (${maskAppCheckSecret(newSecret)}). Tap 'Auto-Register & Verify' to sync it with Firebase automatically."
        } else {
            "Generated fresh debug token: $newSecret\nConfigure MYRAA Backend URL for automatic registration, or add it in Firebase Console."
        }
    } else {
        "Token regeneration only applicable to debug builds."
    }
    _appCheckDiagnosticFlow.value = current.copy(
        status = AppCheckHealthStatus.INITIALIZED,
        activeDebugSecret = newSecret.ifBlank { current.activeDebugSecret },
        lastMessage = msg,
        lastCheckedEpochMs = System.currentTimeMillis(),
        nextAllowedRefreshEpochMs = 0L,
        consecutiveFailureCount = 0,
        autoRegisteredWithBackend = false,
        autoRegistrationSummary = null
    )
    return newSecret
}

fun isAppCheckTokenError(throwable: Throwable): Boolean {
    return generateSequence(throwable) { it.cause }.any { err ->
        val className = err.javaClass.name.lowercase(Locale.ROOT)
        val msg = err.message.orEmpty().lowercase(Locale.ROOT)
        className.contains("appcheck") ||
            msg.contains("app check") ||
            msg.contains("appcheck") ||
            msg.contains("app_check") ||
            msg.contains("app attestation") ||
            msg.contains("too many attempts") ||
            msg.contains("resource_exhausted") ||
            msg.contains("returned dummy token")
    }
}

fun buildAppCheckRemediationMessage(
    isDebugBuild: Boolean,
    providerName: String,
    causeMessage: String? = null,
    debugSecret: String? = null,
    cooldownSeconds: Int = 0,
    backendUrlConfigured: Boolean = configuredBackendUrl.isNotBlank()
): String {
    val causeSummary = causeMessage?.trim()?.takeIf { it.isNotBlank() }?.take(220)
        ?: "Firebase App Check token rejected."
    val isThrottled = causeSummary.contains("too many attempts", ignoreCase = true) ||
        causeSummary.contains("resource_exhausted", ignoreCase = true) ||
        causeSummary.contains("429")

    return if (isDebugBuild) {
        buildString {
            if (isThrottled) {
                appendLine("Firebase App Check rate limit reached (Too many attempts) using $providerName.")
                if (cooldownSeconds > 0) {
                    appendLine("Backoff Cooldown: Waiting ${cooldownSeconds}s to avoid attempt limits, or tap 'Reset / New' for a fresh debug UUID.")
                }
            } else {
                appendLine("App Check not yet accepted ($causeSummary) using $providerName.")
            }
            if (!debugSecret.isNullOrBlank()) {
                appendLine("Device Debug Token: $debugSecret")
            }
            if (backendUrlConfigured) {
                append("Automatic Registration: Backend URL is set. Tap 'Auto-Register & Verify' once backend FIREBASE_SERVICE_ACCOUNT_JSON is active, or add token in Firebase Console > App Check > Apps > com.aistudio.myraa.qxkrmp > Manage debug tokens.")
            } else {
                append("Zero-Copy Option: Deploy /backend/ with FIREBASE_SERVICE_ACCOUNT_JSON and save its URL in Settings for 100% automatic debug token registration — OR install from a Google Play track (Play Integrity). Fallback: paste token into Firebase Console > App Check > Apps > com.aistudio.myraa.qxkrmp > Manage debug tokens.")
            }
        }
    } else {
        buildString {
            appendLine("Play Integrity App Check attestation failed ($causeSummary) using $providerName.")
            appendLine("Production Requirements:")
            appendLine("1. Play Integrity rejects sideloaded APKs unless distributed via Google Play (Internal/Closed/Open/Production track).")
            append("2. Ensure Play Integrity API is linked in Google Cloud and your Play App Signing SHA-256 is registered in Firebase Project gen-lang-client-0645829429.")
        }
    }
}

suspend fun getAppCheckTokenBestEffort(forceRefresh: Boolean = false): String? {
    return try {
        val token = FirebaseAppCheck.getInstance().getAppCheckToken(forceRefresh).await().token
        token.takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Best-effort App Check token fetch unavailable: ${e.message}")
        null
    }
}

suspend fun forceRefreshAppCheckToken(): Result<String> {
    val current = _appCheckDiagnosticFlow.value
    val now = System.currentTimeMillis()
    val cooldownRemaining = synchronized(backoffLock) {
        if (now < nextAllowedRefreshEpochMs) {
            ((nextAllowedRefreshEpochMs - now + 999) / 1000).toInt().coerceAtLeast(1)
        } else 0
    }
    if (cooldownRemaining > 0) {
        return Result.failure(
            AppCheckDiagnosticException(
                message = "App Check refresh is on cooldown (${cooldownRemaining}s remaining) to prevent Firebase attempt limits.",
                isDebugBuild = current.isDebugBuild,
                providerName = current.providerName
            )
        )
    }

    updateAppCheckStatus(
        status = AppCheckHealthStatus.REFRESHING,
        message = "Verifying App Check token with Firebase servers..."
    )

    return try {
        initializedAppContext?.let { prepareDebugProviderForExplicitRefresh(it) }
        val token = FirebaseAppCheck.getInstance().getAppCheckToken(true).await().token
        if (token.isBlank() || token.trimStart().startsWith("{")) {
            recordAppCheckFailure(
                causeMessage = "Firebase rejected the debug token (not registered in project $MYRAA_FIREBASE_PROJECT_ID).",
                isRateLimit = false
            )
        } else {
            resetAppCheckBackoff()
            current.activeDebugSecret?.let { writeLastVerifiedDebugSecret(initializedAppContext, it) }
            updateAppCheckStatus(
                status = AppCheckHealthStatus.TOKEN_VALID,
                message = "Firebase App Check token verified by server! Attestation active."
            )
            Result.success(token)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "App Check token forced refresh failed", e)
        val errMsg = e.localizedMessage ?: e.javaClass.simpleName
        val isRateLimit = errMsg.contains("too many attempts", ignoreCase = true) ||
            errMsg.contains("resource_exhausted", ignoreCase = true) ||
            errMsg.contains("429")
        recordAppCheckFailure(causeMessage = errMsg, isRateLimit = isRateLimit, causeException = e)
    }
}

private fun recordAppCheckFailure(
    causeMessage: String,
    isRateLimit: Boolean,
    causeException: Throwable? = null
): Result<String> {
    val current = _appCheckDiagnosticFlow.value
    val (failures, backoffMs) = synchronized(backoffLock) {
        consecutiveFailureCount++
        val ms = computeBackoffMillis(consecutiveFailureCount, isRateLimit)
        nextAllowedRefreshEpochMs = System.currentTimeMillis() + ms
        consecutiveFailureCount to ms
    }
    val status = if (isRateLimit) AppCheckHealthStatus.RATE_LIMITED else AppCheckHealthStatus.TOKEN_INVALID
    val remediation = buildAppCheckRemediationMessage(
        isDebugBuild = current.isDebugBuild,
        providerName = current.providerName,
        causeMessage = causeMessage,
        debugSecret = current.activeDebugSecret,
        cooldownSeconds = (backoffMs / 1000).toInt(),
        backendUrlConfigured = configuredBackendUrl.isNotBlank()
    )
    _appCheckDiagnosticFlow.value = current.copy(
        status = status,
        lastMessage = remediation,
        lastCheckedEpochMs = System.currentTimeMillis(),
        nextAllowedRefreshEpochMs = nextAllowedRefreshEpochMs,
        consecutiveFailureCount = failures
    )
    return Result.failure(
        AppCheckDiagnosticException(
            message = remediation,
            isDebugBuild = current.isDebugBuild,
            providerName = current.providerName,
            cause = causeException
        )
    )
}

class AppCheckAutoCoordinator(
    private val statusProvider: () -> AppCheckDiagnosticStatus,
    private val updateStatus: ((AppCheckDiagnosticStatus) -> AppCheckDiagnosticStatus) -> Unit,
    private val backendUrlProvider: () -> String,
    private val readVerifiedSecret: () -> String?,
    private val writeVerifiedSecret: (String?) -> Unit,
    private val registerWithBackend: suspend (backendUrl: String, debugSecret: String) -> Result<AppCheckAutoRegisterResponse>,
    private val prepareDebugRefresh: () -> Unit,
    private val forceFirebaseRefresh: suspend () -> Result<String>,
    private val resetBackoff: () -> Unit,
    private val recordFailure: (causeMessage: String, isRateLimit: Boolean, cause: Throwable?) -> Result<String>,
    private val propagationDelayMs: Long = 350L
) {
    private val mutex = Mutex()

    suspend fun ensureRegisteredAndVerified(
        forceAutoRegister: Boolean = false
    ): Result<String> = mutex.withLock {
        val current = statusProvider()
        if (current.status == AppCheckHealthStatus.TOKEN_VALID && !forceAutoRegister) {
            return Result.success("already-verified")
        }
        if (current.isCooldownActive) {
            return Result.failure(
                AppCheckDiagnosticException(
                    message = "App Check is on backoff cooldown (${current.cooldownSecondsRemaining}s remaining) to protect attempt limits.",
                    isDebugBuild = current.isDebugBuild,
                    providerName = current.providerName
                )
            )
        }

        val backendUrl = backendUrlProvider().trim().trimEnd('/')
        if (!current.isDebugBuild) {
            return forceFirebaseRefresh()
        }

        val debugSecret = current.activeDebugSecret?.trim().orEmpty()
        if (debugSecret.isBlank()) {
            return recordFailure("Debug App Check token has not been generated yet.", false, null)
        }

        val previouslyVerified = readVerifiedSecret() == debugSecret
        if (backendUrl.isNotBlank() && (!previouslyVerified || forceAutoRegister)) {
            return performBackendAutoRegistrationAndVerify(backendUrl, debugSecret)
        }

        val directVerify = forceFirebaseRefresh()
        if (directVerify.isSuccess) {
            writeVerifiedSecret(debugSecret)
            return directVerify
        }

        val latest = statusProvider()
        val shouldFallbackAutoReg =
            latest.status != AppCheckHealthStatus.RATE_LIMITED &&
                backendUrl.isNotBlank() &&
                previouslyVerified

        if (shouldFallbackAutoReg) {
            writeVerifiedSecret(null)
            resetBackoff()
            return performBackendAutoRegistrationAndVerify(backendUrl, debugSecret)
        }

        return directVerify
    }

    private suspend fun performBackendAutoRegistrationAndVerify(
        backendUrl: String,
        debugSecret: String
    ): Result<String> {
        updateStatus {
            it.copy(
                status = AppCheckHealthStatus.AUTO_REGISTERING,
                backendUrlConfigured = true,
                autoRegisterAttempted = true,
                lastMessage = "Automatically registering device debug token with MYRAA backend...",
                lastCheckedEpochMs = System.currentTimeMillis()
            )
        }

        val regResult = registerWithBackend(backendUrl, debugSecret)
        if (regResult.isFailure) {
            writeVerifiedSecret(null)
            val err = regResult.exceptionOrNull()
            val errMsg = err?.localizedMessage ?: "Automatic backend registration failed."
            val isRateLimit = errMsg.contains("too many attempts", ignoreCase = true) ||
                errMsg.contains("429")
            updateStatus {
                it.copy(
                    autoRegisteredWithBackend = false,
                    autoRegistrationSummary = errMsg
                )
            }
            return recordFailure(errMsg, isRateLimit, err)
        }

        val info = regResult.getOrThrow()
        resetBackoff()
        prepareDebugRefresh()
        if (propagationDelayMs > 0L) {
            delay(propagationDelayMs)
        }

        updateStatus {
            it.copy(
                status = AppCheckHealthStatus.REFRESHING,
                autoRegisteredWithBackend = true,
                autoRegistrationSummary = "Registered on Firebase via backend as '${info.displayName}'.",
                lastMessage = "Token registered automatically (${info.displayName}). Verifying acceptance with Firebase...",
                lastCheckedEpochMs = System.currentTimeMillis()
            )
        }

        val verifyResult = forceFirebaseRefresh()
        if (verifyResult.isSuccess) {
            writeVerifiedSecret(debugSecret)
            updateStatus {
                it.copy(
                    status = AppCheckHealthStatus.TOKEN_VALID,
                    autoRegisteredWithBackend = true,
                    lastMessage = "App Check auto-registered (${info.displayName}) & verified by Firebase!",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
            }
        } else {
            writeVerifiedSecret(null)
        }
        return verifyResult
    }
}

private val defaultAutoCoordinator by lazy {
    AppCheckAutoCoordinator(
        statusProvider = { _appCheckDiagnosticFlow.value },
        updateStatus = { transform -> _appCheckDiagnosticFlow.value = transform(_appCheckDiagnosticFlow.value) },
        backendUrlProvider = { configuredBackendUrl },
        readVerifiedSecret = { readLastVerifiedDebugSecret(initializedAppContext) },
        writeVerifiedSecret = { secret -> writeLastVerifiedDebugSecret(initializedAppContext, secret) },
        registerWithBackend = { url, secret -> defaultRegistrarClient.registerDebugToken(url, secret) },
        prepareDebugRefresh = { initializedAppContext?.let { prepareDebugProviderForExplicitRefresh(it) } },
        forceFirebaseRefresh = { forceRefreshAppCheckToken() },
        resetBackoff = { resetAppCheckBackoff() },
        recordFailure = { msg, rl, err -> recordAppCheckFailure(msg, rl, err) }
    )
}

suspend fun verifyOrAutoRegisterAppCheckToken(
    backendUrlOverride: String? = null,
    forceAutoRegister: Boolean = false
): Result<String> {
    if (!backendUrlOverride.isNullOrBlank()) {
        setAppCheckBackendUrl(backendUrlOverride)
    }
    return defaultAutoCoordinator.ensureRegisteredAndVerified(forceAutoRegister = forceAutoRegister)
}

class AppCheckRecoveryHelper(
    private val forceRefreshToken: suspend () -> Result<String> = { verifyOrAutoRegisterAppCheckToken(forceAutoRegister = false) },
    private val onStatusChanged: (AppCheckHealthStatus, String) -> Unit = ::updateAppCheckStatus,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val providerNameProvider: () -> String = { _appCheckDiagnosticFlow.value.providerName },
    private val isRateLimitedCooldownActive: () -> Boolean = {
        val diag = _appCheckDiagnosticFlow.value
        diag.status == AppCheckHealthStatus.RATE_LIMITED && diag.isCooldownActive
    },
    private val cooldownSecondsRemainingProvider: () -> Int = { _appCheckDiagnosticFlow.value.cooldownSecondsRemaining },
    private val preFlightBootstrap: (suspend () -> Result<String>)? = null
) {
    /** Default runtime constructor used by GeminiFirebaseProvider, CodingAgentOrchestrator & AppBuilderOrchestrator. */
    constructor() : this(
        preFlightBootstrap = {
            if (_appCheckDiagnosticFlow.value.status != AppCheckHealthStatus.TOKEN_VALID) {
                verifyOrAutoRegisterAppCheckToken(forceAutoRegister = false)
            } else {
                Result.success("already-verified")
            }
        }
    )

    fun ensureNotRateLimited(operationName: String) {
        if (isRateLimitedCooldownActive()) {
            val remaining = cooldownSecondsRemainingProvider().coerceAtLeast(1)
            throw AppCheckDiagnosticException(
                message = "Firebase App Check is on rate-limit cooldown (${remaining}s remaining) after 'Too many attempts'. Wait ${remaining}s or tap 'Reset / New' before retrying $operationName.",
                isDebugBuild = isDebugBuild,
                providerName = providerNameProvider()
            )
        }
    }

    suspend fun <T> runWithSingleRetry(
        operationName: String,
        block: suspend () -> T
    ): T {
        ensureNotRateLimited(operationName)
        preFlightBootstrap?.let { preflight ->
            val preResult = preflight()
            if (preResult.isFailure) {
                throw (preResult.exceptionOrNull() as? AppCheckDiagnosticException
                    ?: AppCheckDiagnosticException(
                        message = preResult.exceptionOrNull()?.localizedMessage ?: "App Check verification failed.",
                        isDebugBuild = isDebugBuild,
                        providerName = providerNameProvider(),
                        cause = preResult.exceptionOrNull()
                    ))
            }
        }
        return try {
            val value = block()
            onStatusChanged(
                AppCheckHealthStatus.TOKEN_VALID,
                "App Check attestation verified during $operationName."
            )
            value
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            if (!isAppCheckTokenError(err)) throw err
            recoverAfterAppCheckError(operationName, err, block)
        }
    }

    suspend fun <T> recoverAfterAppCheckError(
        operationName: String,
        originalError: Throwable,
        retryBlock: suspend () -> T
    ): T {
        val providerName = providerNameProvider()
        val currentStatus = _appCheckDiagnosticFlow.value

        if (currentStatus.isCooldownActive || isRateLimitedCooldownActive()) {
            val remaining = cooldownSecondsRemainingProvider().coerceAtLeast(1)
            throw AppCheckDiagnosticException(
                message = "App Check is on cooldown (${remaining}s remaining). Register your debug token or tap 'Reset / New'.",
                isDebugBuild = isDebugBuild,
                providerName = providerName,
                cause = originalError
            )
        }

        Log.w(TAG, "App Check token error during $operationName; forcing single token recovery.", originalError)
        onStatusChanged(
            AppCheckHealthStatus.REFRESHING,
            "Refreshing App Check token during $operationName..."
        )

        val refreshResult = forceRefreshToken()
        if (refreshResult.isFailure) {
            val refreshCause = refreshResult.exceptionOrNull()
            if (refreshCause is AppCheckDiagnosticException) {
                val msg = refreshCause.message.orEmpty()
                val rateLimited = msg.contains("too many attempts", ignoreCase = true) ||
                    msg.contains("rate limit", ignoreCase = true) ||
                    msg.contains("cooldown", ignoreCase = true) ||
                    _appCheckDiagnosticFlow.value.status == AppCheckHealthStatus.RATE_LIMITED
                onStatusChanged(
                    if (rateLimited) AppCheckHealthStatus.RATE_LIMITED else AppCheckHealthStatus.TOKEN_INVALID,
                    msg
                )
                throw refreshCause
            }
            val causeErr = refreshCause ?: originalError
            val remediation = buildAppCheckRemediationMessage(
                isDebugBuild = isDebugBuild,
                providerName = providerName,
                causeMessage = causeErr.localizedMessage ?: originalError.localizedMessage,
                debugSecret = _appCheckDiagnosticFlow.value.activeDebugSecret
            )
            onStatusChanged(AppCheckHealthStatus.TOKEN_INVALID, remediation)
            throw AppCheckDiagnosticException(
                message = remediation,
                isDebugBuild = isDebugBuild,
                providerName = providerName,
                cause = causeErr
            )
        }

        return try {
            val retried = retryBlock()
            onStatusChanged(
                AppCheckHealthStatus.TOKEN_VALID,
                "App Check token verified and $operationName succeeded on single retry."
            )
            retried
        } catch (e: CancellationException) {
            throw e
        } catch (retryErr: Throwable) {
            if (isAppCheckTokenError(retryErr)) {
                val retryMsg = retryErr.localizedMessage.orEmpty()
                val rateLimited = retryMsg.contains("too many attempts", ignoreCase = true) ||
                    retryMsg.contains("resource_exhausted", ignoreCase = true) ||
                    retryMsg.contains("429")
                val remediation = buildAppCheckRemediationMessage(
                    isDebugBuild = isDebugBuild,
                    providerName = providerName,
                    causeMessage = retryErr.localizedMessage,
                    debugSecret = _appCheckDiagnosticFlow.value.activeDebugSecret
                )
                onStatusChanged(
                    if (rateLimited) AppCheckHealthStatus.RATE_LIMITED else AppCheckHealthStatus.TOKEN_INVALID,
                    remediation
                )
                throw AppCheckDiagnosticException(
                    message = remediation,
                    isDebugBuild = isDebugBuild,
                    providerName = providerName,
                    cause = retryErr
                )
            }
            throw retryErr
        }
    }
}
