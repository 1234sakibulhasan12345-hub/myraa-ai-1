package com.example.core.security

import android.os.Build
import android.util.Log
import com.example.ai.provider.BackendAiProxyClient
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AppCheckAutoReg"

const val MYRAA_FIREBASE_PROJECT_ID = "gen-lang-client-0645829429"
const val MYRAA_ANDROID_PACKAGE = "com.aistudio.myraa.qxkrmp"
const val MYRAA_ANDROID_APP_ID = "1:514839656668:android:fe8f5c54f44c419f555c19"

data class AppCheckAutoRegisterResponse(
    val registered: Boolean,
    val alreadyRegistered: Boolean,
    val prunedCount: Int,
    val tokenName: String,
    val displayName: String,
    val maskedToken: String
)

class AppCheckBackendRegistrarException(
    val httpCode: Int,
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

fun maskAppCheckSecret(secret: String?): String {
    val clean = secret?.trim().orEmpty()
    return if (clean.length >= 8) "${clean.take(8)}-****" else "****"
}

class BackendAppCheckRegistrarClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val idTokenProvider: suspend () -> String? = {
        runCatching {
            Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
        }.getOrNull()
    },
    private val deviceModelProvider: () -> String = {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        when {
            manufacturer.isBlank() && model.isBlank() -> "Android Phone"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model
            else -> "$manufacturer $model"
        }
    }
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun registerDebugToken(
        backendBaseUrl: String,
        debugToken: String
    ): Result<AppCheckAutoRegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = BackendAiProxyClient.requireValidUrl(backendBaseUrl)
            val cleanToken = debugToken.trim().lowercase()
            require(cleanToken.isNotBlank()) { "Debug token cannot be blank." }

            val bearer = idTokenProvider()?.takeIf { it.isNotBlank() }
                ?: throw AppCheckBackendRegistrarException(
                    httpCode = 401,
                    errorCode = "NO_FIREBASE_USER",
                    message = "Sign in with Google first so MYRAA can authenticate automatic App Check registration."
                )

            val payload = JSONObject().apply {
                put("debugToken", cleanToken)
                put("deviceModel", deviceModelProvider())
                put("packageName", MYRAA_ANDROID_PACKAGE)
                put("appId", MYRAA_ANDROID_APP_ID)
            }

            val req = Request.Builder()
                .url("$cleanBase/v1/appcheck/debug-tokens/register")
                .header("Authorization", "Bearer $bearer")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            Log.d(TAG, "Submitting automatic App Check registration (${maskAppCheckSecret(cleanToken)})")

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful) {
                    val errMsg = json?.optString("error").orEmpty()
                        .ifBlank { "Backend App Check registration failed with HTTP ${resp.code}" }
                    val errCode = json?.optString("code").orEmpty().ifBlank { "HTTP_${resp.code}" }
                    throw AppCheckBackendRegistrarException(
                        httpCode = resp.code,
                        errorCode = errCode,
                        message = errMsg
                    )
                }

                val registered = json?.optBoolean("registered", false) == true
                if (!registered) {
                    throw AppCheckBackendRegistrarException(
                        httpCode = 502,
                        errorCode = "INVALID_BACKEND_RESPONSE",
                        message = "Backend did not confirm App Check debug token registration."
                    )
                }

                Result.success(
                    AppCheckAutoRegisterResponse(
                        registered = true,
                        alreadyRegistered = json.optBoolean("alreadyRegistered", false),
                        prunedCount = json.optInt("prunedCount", 0),
                        tokenName = json.optString("tokenName", ""),
                        displayName = json.optString("displayName", ""),
                        maskedToken = json.optString("maskedToken", maskAppCheckSecret(cleanToken))
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic backend App Check registration failed: ${e.message}")
            Result.failure(e)
        }
    }
}
