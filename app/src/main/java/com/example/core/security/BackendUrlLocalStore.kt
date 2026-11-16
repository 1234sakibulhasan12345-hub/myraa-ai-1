package com.example.core.security

import android.content.Context

/**
 * Persists ONLY non-secret server endpoint URLs per user locally in MODE_PRIVATE preferences
 * so Backend Server URL settings survive offline startups or pre-AppCheck bootstrapping.
 * Never store API keys or secrets here — provider keys belong exclusively in the encrypted backend vault.
 */
class BackendUrlLocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readBackendUrl(userId: String): String {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return ""
        return prefs.getString("$KEY_URL_PREFIX$cleanUid", "").orEmpty().trim().trimEnd('/')
    }

    fun writeBackendUrl(userId: String, url: String) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return
        val cleanUrl = url.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            prefs.edit().remove("$KEY_URL_PREFIX$cleanUid").apply()
        } else {
            prefs.edit().putString("$KEY_URL_PREFIX$cleanUid", cleanUrl).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "com.example.myraa.non_secret_backend_prefs"
        private const val KEY_URL_PREFIX = "backend_url_"
    }
}
