package com.example.core.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"

internal fun installBuildVariantAppCheckProvider(
    context: Context,
    @Suppress("UNUSED_PARAMETER") intent: Intent?
): AppCheckInstallOutcome {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        PlayIntegrityAppCheckProviderFactory.getInstance(),
        true
    )
    Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed for release build (${context.packageName}).")

    return AppCheckInstallOutcome(
        providerName = "PlayIntegrityAppCheckProviderFactory",
        isDebugBuild = false,
        hasIntentDebugSecret = false,
        installedSuccessfully = true,
        detailMessage = "PlayIntegrityAppCheckProviderFactory active for production attestation (${context.packageName}).",
        debugSecret = null
    )
}

internal fun regenerateDebugSecretVariant(@Suppress("UNUSED_PARAMETER") context: Context): String {
    return ""
}

internal fun prepareDebugProviderForExplicitRefresh(@Suppress("UNUSED_PARAMETER") context: Context) {
    // No-op in release builds; PlayIntegrityAppCheckProviderFactory handles production attestation.
}

