package com.example.build

import com.example.ai.provider.BackendAiProxyClient
import com.example.core.security.getAppCheckTokenBestEffort
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class RemoteArtifactDescriptor(
    val jobId: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val buildType: BuildType,
    val apkVerified: Boolean,
    val signatureVerified: Boolean = false,
    val expiresAtEpochMs: Long
)

data class RemoteBuildJobResponse(
    val jobId: String,
    val projectId: String,
    val buildType: BuildType,
    val status: BuildJobStatus,
    val logs: String,
    val errorSummary: String,
    val durationMs: Long,
    val artifact: RemoteArtifactDescriptor? = null
)

data class VerifiedApkDownloadResult(
    val bytesWritten: Long,
    val sha256Hex: String
)

class BuildServerClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val tokenProvider: suspend () -> String? = {
        Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
    },
    private val appCheckTokenProvider: suspend () -> String? = {
        getAppCheckTokenBestEffort(forceRefresh = false)
    }
) {
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
            val cleanBase = requireValidBaseUrl(baseUrl)
            val idToken = requireIdToken()
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
            }
            val reqBuilder = Request.Builder()
                .url("$cleanBase/v1/builds")
                .addHeader("Authorization", "Bearer $idToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
            attachAppCheckHeader(reqBuilder)
            executeBuildJobRequest(reqBuilder.build(), cleanBase)
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun getBuildStatus(
        baseUrl: String,
        jobId: String
    ): Result<RemoteBuildJobResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = requireValidBaseUrl(baseUrl)
            val idToken = requireIdToken()
            val reqBuilder = Request.Builder()
                .url("$cleanBase/v1/builds/$jobId")
                .addHeader("Authorization", "Bearer $idToken")
                .get()
            attachAppCheckHeader(reqBuilder)
            executeBuildJobRequest(reqBuilder.build(), cleanBase)
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun downloadVerifiedApk(
        downloadUrl: String,
        expectedSha256: String,
        targetOut: OutputStream
    ): Result<VerifiedApkDownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanExpected = expectedSha256.trim().lowercase()
            require(cleanExpected.length == 64 && cleanExpected.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid expected APK SHA-256 checksum."
            }
            val validationError = BackendAiProxyClient.validateBackendUrlError(downloadUrl)
            require(validationError == null) { validationError ?: "Invalid APK download URL." }

            val idToken = requireIdToken()
            val reqBuilder = Request.Builder()
                .url(downloadUrl.trim())
                .addHeader("Authorization", "Bearer $idToken")
                .get()
            attachAppCheckHeader(reqBuilder)

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    throw IOException(extractError(errBody, "APK download failed (HTTP ${resp.code})."))
                }
                val body = resp.body ?: throw IOException("Empty APK response body.")
                val buffer = ByteArrayOutputStream()
                val digest = MessageDigest.getInstance("SHA-256")
                val chunk = ByteArray(16 * 1024)
                var totalRead = 0L

                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        if (totalRead == 0L) {
                            check(read >= 4 &&
                                chunk[0] == 0x50.toByte() &&
                                chunk[1] == 0x4B.toByte() &&
                                chunk[2] == 0x03.toByte() &&
                                chunk[3] == 0x04.toByte()
                            ) {
                                throw IOException("Downloaded binary failed ZIP/APK magic header check.")
                            }
                        }
                        digest.update(chunk, 0, read)
                        buffer.write(chunk, 0, read)
                        totalRead += read
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(cleanExpected, ignoreCase = true)) {
                    throw IOException("Downloaded APK failed SHA-256 integrity verification.")
                }

                buffer.writeTo(targetOut)
                targetOut.flush()
                VerifiedApkDownloadResult(bytesWritten = totalRead, sha256Hex = actualSha256)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun executeBuildJobRequest(request: Request, cleanBase: String): RemoteBuildJobResponse {
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(extractError(raw, "Build server returned HTTP ${resp.code}."))
            }
            val obj = JSONObject(raw)
            val artifactObj = obj.optJSONObject("artifact")
            val artifact = if (artifactObj != null) {
                val downloadPath = artifactObj.optString("downloadPath")
                val fullDownloadUrl = when {
                    downloadPath.startsWith("http://") || downloadPath.startsWith("https://") -> downloadPath
                    downloadPath.startsWith("/") -> "$cleanBase$downloadPath"
                    else -> "$cleanBase/$downloadPath"
                }
                RemoteArtifactDescriptor(
                    jobId = artifactObj.optString("jobId", obj.optString("jobId")),
                    fileName = artifactObj.optString("fileName", "app-debug.apk"),
                    downloadUrl = fullDownloadUrl,
                    sha256 = artifactObj.optString("sha256"),
                    sizeBytes = artifactObj.optLong("sizeBytes"),
                    buildType = BuildType.fromWire(artifactObj.optString("buildType", "DEBUG")),
                    apkVerified = artifactObj.optBoolean("apkVerified", false),
                    signatureVerified = artifactObj.optBoolean("signatureVerified", false),
                    expiresAtEpochMs = artifactObj.optLong("expiresAtEpochMs")
                )
            } else {
                null
            }
            return RemoteBuildJobResponse(
                jobId = obj.optString("jobId"),
                projectId = obj.optString("projectId"),
                buildType = BuildType.fromWire(obj.optString("buildType", "DEBUG")),
                status = BuildJobStatus.fromWire(obj.optString("status", "QUEUED")),
                logs = obj.optString("logs"),
                errorSummary = obj.optString("errorSummary"),
                durationMs = obj.optLong("durationMs"),
                artifact = artifact
            )
        }
    }

    private suspend fun attachAppCheckHeader(builder: Request.Builder) {
        val token = appCheckTokenProvider()?.trim().orEmpty()
        if (token.isNotBlank()) {
            builder.addHeader("X-Firebase-AppCheck", token)
        }
    }

    private fun requireValidBaseUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val err = BackendAiProxyClient.validateBackendUrlError(clean)
        require(err == null) { err ?: "Invalid Backend URL." }
        return clean
    }

    private suspend fun requireIdToken(): String =
        tokenProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Please sign in with Google before dispatching remote builds.")

    private fun extractError(rawBody: String, fallback: String): String =
        runCatching {
            JSONObject(rawBody).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback
}
