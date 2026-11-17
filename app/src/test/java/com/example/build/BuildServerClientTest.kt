package com.example.build

import com.example.core.security.FirebaseIdTokenFetcher
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.data.model.ProjectFile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuildServerClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val authRequests = AtomicInteger(0)
    private lateinit var validApkBytes: ByteArray
    private lateinit var validApkSha: String

    @Before
    fun setUp() {
        validApkBytes = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 10, 20, 30, 40)
        validApkSha = MessageDigest.getInstance("SHA-256")
            .digest(validApkBytes)
            .joinToString("") { "%02x".format(it) }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/builds") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val count = authRequests.incrementAndGet()
            if (auth != "Bearer fresh_token") {
                val err = """{"error":"Expired token","errorCode":"FIREBASE_AUTH_REQUIRED"}""".toByteArray()
                ex.sendResponseHeaders(401, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val path = ex.requestURI.path
            val body = if (ex.requestMethod == "POST") {
                """{"jobId":"job_101","status":"QUEUED","logs":"Queued ($count)"}""".toByteArray()
            } else if (path.endsWith("/artifact")) {
                ex.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
                ex.sendResponseHeaders(200, validApkBytes.size.toLong())
                ex.responseBody.use { it.write(validApkBytes) }
                return@createContext
            } else {
                """
                {
                  "jobId": "job_101",
                  "status": "SUCCEEDED",
                  "logs": "BUILD SUCCESSFUL",
                  "durationMs": 420,
                  "artifact": {
                    "fileName": "app-debug.apk",
                    "downloadUrl": "/v1/builds/job_101/artifact",
                    "sha256": "$validApkSha",
                    "sizeBytes": ${validApkBytes.size},
                    "buildType": "DEBUG",
                    "apkVerified": true,
                    "expiresAtEpochMs": 1999999999000
                  }
                }
                """.trimIndent().toByteArray()
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/bad-binary") { ex ->
            val html = "<html>not an apk</html>".toByteArray()
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun testClient(fetcher: FirebaseIdTokenFetcher) = BuildServerClient(
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build(),
        tokenFetcher = fetcher
    )

    @Test
    fun submitBuild_refreshesExpiredIdTokenAndRetriesOn401() = runBlocking {
        val client = testClient(FirebaseIdTokenFetcher { force ->
            if (force) "fresh_token" else "expired_token"
        })
        val res = client.submitBuild(
            baseUrl = baseUrl,
            projectId = "proj_1",
            buildType = BuildType.DEBUG,
            appName = "Demo",
            packageName = "com.example.demo",
            versionName = "1.0",
            versionCode = 1,
            files = listOf(ProjectFile(path = "build.gradle.kts", content = "// gradle"))
        ).getOrThrow()
        assertEquals("job_101", res.jobId)
        assertEquals(BuildJobStatus.QUEUED, res.status)
    }

    @Test
    fun submitPollAndDownloadVerifiedApkSucceeds() = runBlocking {
        val client = testClient(FirebaseIdTokenFetcher { "fresh_token" })
        val status = client.getBuildStatus(baseUrl, "job_101").getOrThrow()
        assertEquals(BuildJobStatus.SUCCEEDED, status.status)
        val art = status.artifact!!
        val out = ByteArrayOutputStream()
        val dl = client.downloadVerifiedApk(art.downloadUrl, art.sha256, out).getOrThrow()
        assertEquals(validApkBytes.size.toLong(), dl.bytesWritten)
        assertEquals(validApkSha, dl.sha256Hex)
    }

    @Test
    fun downloadVerifiedApkRejectsChecksumMismatchAndNonZipBinary() = runBlocking {
        val client = testClient(FirebaseIdTokenFetcher { "fresh_token" })
        val wrongChecksum = client.downloadVerifiedApk(
            "$baseUrl/v1/builds/job_101/artifact",
            "0".repeat(64),
            ByteArrayOutputStream()
        )
        assertTrue(wrongChecksum.isFailure)

        val nonZip = client.downloadVerifiedApk(
            "$baseUrl/bad-binary",
            validApkSha,
            ByteArrayOutputStream()
        )
        assertTrue(nonZip.isFailure)
    }
}
