package com.example.build

import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.ProviderHttpException
import com.example.core.security.FirebaseIdTokenFetcher
import com.example.core.security.FirebaseIdTokenProvider
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class RemoteBuildArtifactResponse(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val buildType: BuildType,
    val apkVerified: Boolean,
    val expiresAtEpochMs: Long
)

data class RemoteBuildJobResponse(
    val jobId: String,
    val status: BuildJobStatus,
    val logs: String = "",
    val errorSummary: String = "",
    val durationMs: Long = 0L,
    val artifact: RemoteBuildArtifactResponse? = null
)

data class VerifiedApkDownloadResult(
    val bytesWritten: Long,
    val sha256Hex: String
)

class BuildServerClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val tokenFetcher: FirebaseIdTokenFetcher = defaultFirebaseIdTokenFetcher()
) {
    constructor(
        httpClient: OkHttpClient = defaultHttpClient(),
        tokenProvider: suspend () -> String?
    ) : this(
        httpClient = httpClient,
        tokenFetcher = FirebaseIdTokenFetcher { _ -> tokenProvider() }
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun submitBuild(
        baseUrl: String,
        projectId: String,
        buildType: BuildType,
        appName: String,
        packageName: String,
        versionName: String,
        versionCode: Int,
        files: List<ProjectFile>
    ): Result<RemoteBuildJobResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = BackendAiProxyClient.requireValidUrl(baseUrl)
            val filesArray = JSONArray().apply {
                files.forEach { file ->
                    put(
                        JSONObject().apply {
                            put("path", file.path)
                            put("content", file.content)
                        }
                    )
                }
            }
            val payload = JSONObject().apply {
                put("projectId", projectId)
                put("buildType", buildType.wireValue)
                put("appName", appName)
                put("packageName", packageName)
                put("versionName", versionName)
                put("versionCode", versionCode)
                put("files", filesArray)
            }.toString()

            try {
                executeAuthenticated { bearer ->
                    Request.Builder()
                        .url("$cleanBase/v1/builds")
                        .header("Authorization", "Bearer $bearer")
                        .post(payload.toRequestBody(jsonMediaType))
                        .build()
                }.use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ProviderHttpException(resp.code, "BUILD_SUBMIT_FAILED", extractErrorMessage(raw, resp.code))
                    }
                    parseJobResponse(JSONObject(raw), cleanBase)
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "Build server unreachable: ${io.localizedMessage}", io)
            }
        }
    }

    suspend fun getBuildStatus(baseUrl: String, jobId: String): Result<RemoteBuildJobResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanBase = BackendAiProxyClient.requireValidUrl(baseUrl)
                try {
                    executeAuthenticated { bearer ->
                        Request.Builder()
                            .url("$cleanBase/v1/builds/$jobId")
                            .header("Authorization", "Bearer $bearer")
                            .get()
                            .build()
                    }.use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw ProviderHttpException(resp.code, "BUILD_STATUS_FAILED", extractErrorMessage(raw, resp.code))
                        }
                        parseJobResponse(JSONObject(raw), cleanBase)
                    }
                } catch (io: IOException) {
                    throw ProviderHttpException(503, "OFFLINE", "Build status check failed: ${io.localizedMessage}", io)
                }
            }
        }

    suspend fun downloadVerifiedApk(
        downloadUrl: String,
        expectedSha256: String,
        outputStream: OutputStream
    ): Result<VerifiedApkDownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = BackendAiProxyClient.requireValidUrl(downloadUrl)
            val normalizedExpected = expectedSha256.trim().lowercase()
            require(normalizedExpected.length == 64 && normalizedExpected.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid SHA-256 artifact checksum."
            }

            try {
                executeAuthenticated { bearer ->
                    Request.Builder()
                        .url(cleanUrl)
                        .header("Authorization", "Bearer $bearer")
                        .get()
                        .build()
                }.use { resp ->
                    if (!resp.isSuccessful) {
                        val raw = resp.body?.string().orEmpty()
                        throw ProviderHttpException(resp.code, "DOWNLOAD_FAILED", extractErrorMessage(raw, resp.code))
                    }
                    val bodyStream = resp.body?.byteStream()
                        ?: throw IllegalStateException("Empty APK download response body.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    var totalBytes = 0L
                    val magicHeader = ByteArray(2)
                    var headerRead = 0

                    while (true) {
                        val read = bodyStream.read(buffer)
                        if (read <= 0) break
                        if (headerRead < 2) {
                            val needed = (2 - headerRead).coerceAtMost(read)
                            System.arraycopy(buffer, 0, magicHeader, headerRead, needed)
                            headerRead += needed
                        }
                        digest.update(buffer, 0, read)
                        outputStream.write(buffer, 0, read)
                        totalBytes += read
                    }
                    outputStream.flush()

                    if (headerRead < 2 || magicHeader[0] != 'P'.code.toByte() || magicHeader[1] != 'K'.code.toByte()) {
                        throw IllegalStateException("Downloaded artifact is not a valid ZIP/APK binary archive.")
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(normalizedExpected, ignoreCase = true)) {
                        throw IllegalStateException("APK SHA-256 verification failed: expected $normalizedExpected but got $actualSha.")
                    }
                    VerifiedApkDownloadResult(bytesWritten = totalBytes, sha256Hex = actualSha)
                }
            } catch (io: IOException) {
                throw ProviderHttpException(503, "OFFLINE", "APK download failed: ${io.localizedMessage}", io)
            }
        }
    }

    private suspend fun executeAuthenticated(buildRequest: (String) -> Request): okhttp3.Response {
        return FirebaseIdTokenProvider.executeWithBearerRetry(
            httpClient = httpClient,
            tokenFetcher = tokenFetcher,
            onMissingToken = {
                ProviderHttpException(
                    httpCode = 401,
                    errorCode = "FIREBASE_AUTH_REQUIRED",
                    message = "Sign in with Google before calling remote build server."
                )
            },
            requestFactory = { bearer -> buildRequest(bearer) }
        ).response
    }

    private fun parseJobResponse(json: JSONObject, cleanBase: String): RemoteBuildJobResponse {
        val status = BuildJobStatus.fromWire(json.optString("status", "QUEUED"))
        val artifactObj = json.optJSONObject("artifact")
        val artifact = artifactObj?.let { art ->
            val rawUrl = art.optString("downloadUrl", "")
            val resolvedUrl = if (rawUrl.startsWith("/")) "$cleanBase$rawUrl" else rawUrl
            RemoteBuildArtifactResponse(
                fileName = art.optString("fileName", "app-debug.apk"),
                downloadUrl = resolvedUrl,
                sha256 = art.optString("sha256", ""),
                sizeBytes = art.optLong("sizeBytes", 0L),
                buildType = BuildType.fromWire(art.optString("buildType", "DEBUG")),
                apkVerified = art.optBoolean("apkVerified", false),
                expiresAtEpochMs = art.optLong("expiresAtEpochMs", 0L)
            )
        }
        return RemoteBuildJobResponse(
            jobId = json.optString("jobId", ""),
            status = status,
            logs = json.optString("logs", ""),
            errorSummary = json.optString("errorSummary", ""),
            durationMs = json.optLong("durationMs", 0L),
            artifact = artifact
        )
    }

    private fun extractErrorMessage(raw: String, httpCode: Int): String {
        return runCatching { JSONObject(raw).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Remote build server returned HTTP $httpCode"
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun defaultFirebaseIdTokenFetcher(): FirebaseIdTokenFetcher = FirebaseIdTokenProvider.defaultFetcher()
    }
}
