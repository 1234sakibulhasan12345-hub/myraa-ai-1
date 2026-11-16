package com.example.build

import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuildServerClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var client: BuildServerClient

    private val fakeApkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "AndroidManifest.xml_classes.dex".toByteArray()
    private val fakeApkSha256 = MessageDigest.getInstance("SHA-256")
        .digest(fakeApkBytes)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/builds") { ex ->
            val resp = if (ex.requestMethod == "POST") {
                """
                {
                  "jobId": "job_123",
                  "projectId": "proj_1",
                  "buildType": "DEBUG",
                  "status": "QUEUED",
                  "logs": "Queued",
                  "errorSummary": "",
                  "durationMs": 0,
                  "artifact": null
                }
                """.trimIndent()
            } else {
                """
                {
                  "jobId": "job_123",
                  "projectId": "proj_1",
                  "buildType": "DEBUG",
                  "status": "SUCCEEDED",
                  "logs": "Done",
                  "errorSummary": "",
                  "durationMs": 1200,
                  "artifact": {
                    "jobId": "job_123",
                    "fileName": "myraa-debug-1.0.0.apk",
                    "downloadPath": "/v1/artifacts/job_123/apk",
                    "sha256": "$fakeApkSha256",
                    "sizeBytes": ${fakeApkBytes.size},
                    "buildType": "DEBUG",
                    "apkVerified": true,
                    "signatureVerified": true,
                    "expiresAtEpochMs": 9999999999999
                  }
                }
                """.trimIndent()
            }
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(if (ex.requestMethod == "POST") 202 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/artifacts/job_123/apk") { ex ->
            ex.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
            ex.sendResponseHeaders(200, fakeApkBytes.size.toLong())
            ex.responseBody.use { it.write(fakeApkBytes) }
        }
        server.createContext("/v1/artifacts/bad_bin/apk") { ex ->
            val badBytes = "NOT_A_ZIP_BINARY".toByteArray()
            ex.sendResponseHeaders(200, badBytes.size.toLong())
            ex.responseBody.use { it.write(badBytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        client = BuildServerClient(
            httpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build(),
            tokenProvider = { "test_id_token" },
            appCheckTokenProvider = { null }
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun submitPollAndDownloadVerifiedApkSucceeds() = runBlocking {
        val queued = client.submitBuild(
            baseUrl = baseUrl,
            projectId = "proj_1",
            buildType = BuildType.DEBUG,
            appName = "Myraa Test",
            packageName = "com.myraa.test",
            versionName = "1.0.0",
            versionCode = 1,
            files = listOf(ProjectFile(path = "build.gradle.kts", content = "// ok"))
        ).getOrThrow()
        assertEquals(BuildJobStatus.QUEUED, queued.status)

        val finished = client.getBuildStatus(baseUrl, "job_123").getOrThrow()
        assertEquals(BuildJobStatus.SUCCEEDED, finished.status)
        val artifact = finished.artifact
        assertNotNull(artifact)
        assertTrue(artifact!!.apkVerified)

        val out = ByteArrayOutputStream()
        val downloaded = client.downloadVerifiedApk(artifact.downloadUrl, artifact.sha256, out).getOrThrow()
        assertEquals(fakeApkBytes.size.toLong(), downloaded.bytesWritten)
        assertEquals(fakeApkSha256, downloaded.sha256Hex)
        assertEquals(fakeApkBytes.size, out.size())
    }

    @Test
    fun downloadVerifiedApkRejectsChecksumMismatchAndNonZipBinary() = runBlocking {
        val mismatch = client.downloadVerifiedApk(
            downloadUrl = "$baseUrl/v1/artifacts/job_123/apk",
            expectedSha256 = "0".repeat(64),
            targetOut = ByteArrayOutputStream()
        )
        assertTrue(mismatch.isFailure)

        val badZip = client.downloadVerifiedApk(
            downloadUrl = "$baseUrl/v1/artifacts/bad_bin/apk",
            expectedSha256 = fakeApkSha256,
            targetOut = ByteArrayOutputStream()
        )
        assertTrue(badZip.isFailure)
    }
}
