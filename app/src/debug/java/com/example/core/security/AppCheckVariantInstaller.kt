package com.example.core.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.InternalDebugSecretProvider
import com.google.firebase.appcheck.debug.internal.DebugAppCheckProvider
import com.google.firebase.inject.Provider
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

private val debugProviderExecutor: ExecutorService by lazy {
    Executors.newCachedThreadPool()
}

private fun maskSecretForLog(secret: String): String {
    val clean = secret.trim()
    return if (clean.length >= 8) "${clean.take(8)}-****" else "****"
}

private fun readOrCreateDebugSecret(context: Context, intentExtra: String?): Pair<String, Boolean> {
    val persistenceKey = FirebaseApp.getInstance().persistenceKey
    val prefs = context.getSharedPreferences(
        "$DEBUG_STORE_PREFIX$persistenceKey",
        Context.MODE_PRIVATE
    )
    val cleanExtra = intentExtra?.trim()
    if (!cleanExtra.isNullOrEmpty()) {
        prefs.edit().putString(DEBUG_SECRET_KEY, cleanExtra).commit()
        Log.d(TAG, "Configured App Check debug secret from launch Intent extra (${maskSecretForLog(cleanExtra)}).")
        return cleanExtra to true
    }

    val existing = prefs.getString(DEBUG_SECRET_KEY, null)?.trim()
    if (!existing.isNullOrBlank()) {
        Log.d(TAG, "Reusing existing stored App Check debug secret (${maskSecretForLog(existing)}).")
        return existing to false
    }

    val generated = UUID.randomUUID().toString()
    prefs.edit().putString(DEBUG_SECRET_KEY, generated).commit()
    Log.d(TAG, "Generated and stored initial App Check debug secret (${maskSecretForLog(generated)}).")
    return generated to false
}

/**
 * Installs a fresh DebugAppCheckProvider backed by the exact active debug secret.
 * Unlike DebugAppCheckProviderFactory.getInstance() (which caches a single RetryManager & debug
 * secret for the whole process lifetime, locking out HTTP 400/404 for 24h), creating a fresh
 * DebugAppCheckProvider when resetting or explicitly verifying lets in-app regeneration and
 * post-registration verification succeed immediately without restarting the app process.
 */
private fun installFreshDebugProvider(secret: String, clearCachedToken: Boolean) {
    val appCheck = FirebaseAppCheck.getInstance()
    if (clearCachedToken) {
        runCatching {
            appCheck.javaClass.getMethod("resetAppCheckState").invoke(appCheck)
        }
    }
    val factory = AppCheckProviderFactory { firebaseApp ->
        DebugAppCheckProvider(
            firebaseApp,
            Provider { InternalDebugSecretProvider { secret } },
            debugProviderExecutor,
            debugProviderExecutor,
            debugProviderExecutor
        )
    }
    appCheck.installAppCheckProviderFactory(factory, true)
}

internal fun installBuildVariantAppCheckProvider(
    context: Context,
    intent: Intent?
): AppCheckInstallOutcome {
    val (activeSecret, hasIntentSecret) = readOrCreateDebugSecret(
        context = context,
        intentExtra = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
    )

    installFreshDebugProvider(secret = activeSecret, clearCachedToken = hasIntentSecret)
    Log.d(TAG, "DebugAppCheckProviderFactory installed with auto-refresh enabled.")

    val detail = if (hasIntentSecret) {
        "DebugAppCheckProviderFactory active with launch Intent token (${maskSecretForLog(activeSecret)})."
    } else {
        "DebugAppCheckProviderFactory active with in-app debug token (${maskSecretForLog(activeSecret)}). Automatic registration will sync with Firebase once signed in."
    }

    return AppCheckInstallOutcome(
        providerName = "DebugAppCheckProviderFactory",
        isDebugBuild = true,
        hasIntentDebugSecret = hasIntentSecret,
        installedSuccessfully = true,
        detailMessage = detail,
        debugSecret = activeSecret
    )
}

internal fun regenerateDebugSecretVariant(context: Context): String {
    if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(context)
    }
    val persistenceKey = FirebaseApp.getInstance().persistenceKey
    val prefs = context.getSharedPreferences(
        "$DEBUG_STORE_PREFIX$persistenceKey",
        Context.MODE_PRIVATE
    )
    val freshSecret = UUID.randomUUID().toString()
    prefs.edit().putString(DEBUG_SECRET_KEY, freshSecret).commit()

    installFreshDebugProvider(secret = freshSecret, clearCachedToken = true)
    Log.d(TAG, "Regenerated fresh App Check debug secret (${maskSecretForLog(freshSecret)}); rate-limit backoff cleared.")
    return freshSecret
}

internal fun prepareDebugProviderForExplicitRefresh(context: Context) {
    if (FirebaseApp.getApps(context).isEmpty()) return
    val (activeSecret, _) = readOrCreateDebugSecret(context = context, intentExtra = null)
    installFreshDebugProvider(secret = activeSecret, clearCachedToken = true)
}
