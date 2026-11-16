import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import {
  createAppCheckRegistrar,
  MYRAA_AUTO_TOKEN_PREFIX
} from "../src/appCheckRegistrar.mjs";
import { createAiProviderRouter } from "../src/aiProviders.mjs";
import { createFirebaseAuthVerifier } from "../src/auth.mjs";
import { createBuildQueueManager } from "../src/buildWorker.mjs";
import { createMyraaBackendServer } from "../src/server.mjs";

function makeEmulatorJwt(uid, email = "dev@myraa.test") {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(
    JSON.stringify({
      sub: uid,
      user_id: uid,
      email,
      exp: Math.floor(Date.now() / 1000) + 3600
    })
  ).toString("base64url");
  return `${header}.${payload}.`;
}

function buildSampleAndroidFiles({ appName = "MyraaSample", pkg = "com.myraa.sampleapp" } = {}) {
  const pkgPath = pkg.replace(/\./g, "/");
  return [
    {
      path: "gradle.properties",
      content: [
        "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8",
        "android.nonTransitiveRClass=true",
        "kotlin.code.style=official",
        "kotlin.compiler.execution.strategy=in-process"
      ].join("\n")
    },
    {
      path: "settings.gradle.kts",
      content: `pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}
rootProject.name = "${appName}"
include(":app")
`
    },
    {
      path: "build.gradle.kts",
      content: `plugins {
  id("com.android.application") version "9.1.1" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
`
    },
    {
      path: "app/build.gradle.kts",
      content: `plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "${pkg}"
  compileSdk = 36
  defaultConfig {
    applicationId = "${pkg}"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
}
`
    },
    {
      path: "app/src/main/AndroidManifest.xml",
      content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <application android:label="${appName}">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
`
    },
    {
      path: "app/src/main/res/values/strings.xml",
      content: `<resources><string name="app_name">${appName}</string></resources>\n`
    },
    {
      path: `app/src/main/java/${pkgPath}/MainActivity.kt`,
      content: `package ${pkg}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${appName} Built by MYRAA")
                }
            }
        }
    }
}
`
    }
  ];
}

let server;
let baseUrl;
let artifactTmpDir;

before(async () => {
  artifactTmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "myraa-backend-artifacts-"));

  const fakeFetch = async (url, init = {}) => {
    const u = String(url);
    if (u.includes("api.openai.com/v1/chat/completions")) {
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "OpenAI GPT verified proxy response." } }],
          usage: { prompt_tokens: 18, completion_tokens: 9 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    if (u.includes("api.anthropic.com/v1/messages")) {
      return new Response(
        JSON.stringify({
          content: [{ type: "text", text: "Claude verified proxy response." }],
          usage: { input_tokens: 20, output_tokens: 8 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    if (u.includes("api.deepseek.com/chat/completions")) {
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "DeepSeek verified proxy response." } }],
          usage: { prompt_tokens: 15, completion_tokens: 7 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  // Start server with only OPENAI_API_KEY configured (leaving Anthropic/DeepSeek/Llama unconfigured)
  // to prove honest per-provider gating!
  const env = {
    ...process.env,
    OPENAI_API_KEY: "test-openai-key",
    ANTHROPIC_API_KEY: "",
    DEEPSEEK_API_KEY: "",
    META_LLAMA_API_KEY: ""
  };

  const verifyAuth = createFirebaseAuthVerifier({ allowEmulatorTokens: true });
  const aiRouter = createAiProviderRouter({ env, fetchImpl: fakeFetch });
  const buildManager = createBuildQueueManager({
    env,
    artifactDir: artifactTmpDir,
    buildTimeoutMs: 180_000
  });

  server = createMyraaBackendServer({ verifyAuth, aiRouter, buildManager });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  baseUrl = `http://127.0.0.1:${addr.port}`;
});

after(async () => {
  if (server) {
    await new Promise((resolve) => server.close(resolve));
  }
  if (artifactTmpDir) {
    await fs.rm(artifactTmpDir, { recursive: true, force: true }).catch(() => {});
  }
});

test("Health and unauthenticated rejection work accurately", async () => {
  const health = await fetch(`${baseUrl}/health`);
  assert.equal(health.status, 200);
  const hJson = await health.json();
  assert.equal(hJson.status, "ok");

  const unauthStatus = await fetch(`${baseUrl}/v1/providers/status`);
  assert.equal(unauthStatus.status, 401);

  const unauthBuild = await fetch(`${baseUrl}/v1/builds`, { method: "POST" });
  assert.equal(unauthBuild.status, 401);
});

test("Provider status reports ONLY configured keys connected and rejects unconfigured providers", async () => {
  const token = makeEmulatorJwt("user_alice");
  const resp = await fetch(`${baseUrl}/v1/providers/status`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  assert.equal(resp.status, 200);
  const { providers } = await resp.json();

  const openai = providers.find((p) => p.providerId === "openai_gpt");
  const claude = providers.find((p) => p.providerId === "anthropic_claude");
  const deepseek = providers.find((p) => p.providerId === "deepseek");
  const llama = providers.find((p) => p.providerId === "meta_llama");

  assert.equal(openai.connected, true);
  assert.equal(claude.connected, false);
  assert.equal(deepseek.connected, false);
  assert.equal(llama.connected, false);

  const gptCall = await fetch(`${baseUrl}/v1/ai/generate`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ providerId: "openai_gpt", prompt: "Generate summary" })
  });
  assert.equal(gptCall.status, 200);
  const gptJson = await gptCall.json();
  assert.equal(gptJson.text, "OpenAI GPT verified proxy response.");
  assert.equal(gptJson.promptTokens, 18);

  const claudeCall = await fetch(`${baseUrl}/v1/ai/generate`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ providerId: "anthropic_claude", prompt: "Hello" })
  });
  assert.equal(claudeCall.status, 503);
});

test("Build security blocks path traversal, malicious Gradle RCE scripts, and restricted permissions", async () => {
  const token = makeEmulatorJwt("user_alice");

  const traversalFiles = buildSampleAndroidFiles();
  traversalFiles.push({ path: "../../etc/passwd", content: "bad" });
  const r1 = await fetch(`${baseUrl}/v1/builds`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      projectId: "proj_sec_1",
      appName: "SecTest",
      packageName: "com.myraa.sectest",
      files: traversalFiles
    })
  });
  assert.equal(r1.status, 400);

  const rceFiles = buildSampleAndroidFiles();
  rceFiles[2].content += '\nRuntime.getRuntime().exec("id")\n';
  const r2 = await fetch(`${baseUrl}/v1/builds`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      projectId: "proj_sec_2",
      appName: "SecTest",
      packageName: "com.myraa.sectest",
      files: rceFiles
    })
  });
  assert.equal(r2.status, 400);

  const playBadFiles = buildSampleAndroidFiles();
  const manifestIdx = playBadFiles.findIndex((f) => f.path.endsWith("AndroidManifest.xml"));
  playBadFiles[manifestIdx].content = playBadFiles[manifestIdx].content.replace(
    "INTERNET",
    "REQUEST_INSTALL_PACKAGES"
  );
  const r3 = await fetch(`${baseUrl}/v1/builds`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      projectId: "proj_sec_3",
      appName: "SecTest",
      packageName: "com.myraa.sectest",
      files: playBadFiles
    })
  });
  assert.equal(r3.status, 400);
});

test("End-to-End Real Gradle Build compiles an actual verified APK and enforces cross-user 403 isolation", async () => {
  const aliceToken = makeEmulatorJwt("user_alice");
  const bobToken = makeEmulatorJwt("user_bob");

  const startResp = await fetch(`${baseUrl}/v1/builds`, {
    method: "POST",
    headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
    body: JSON.stringify({
      projectId: "proj_real_apk",
      buildType: "DEBUG",
      appName: "MyraaSample",
      packageName: "com.myraa.sampleapp",
      versionName: "1.0.0",
      versionCode: 1,
      files: buildSampleAndroidFiles()
    })
  });
  assert.equal(startResp.status, 202);
  const initialJob = await startResp.json();
  assert.ok(initialJob.jobId);

  // Bob must receive 403 on Alice's build job
  const bobCheck = await fetch(`${baseUrl}/v1/builds/${initialJob.jobId}`, {
    headers: { Authorization: `Bearer ${bobToken}` }
  });
  assert.equal(bobCheck.status, 403);

  // Poll until Gradle build finishes
  let finalJob = initialJob;
  const deadline = Date.now() + 180_000;
  while ((finalJob.status !== "SUCCEEDED" && finalJob.status !== "FAILED") && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 1500));
    const poll = await fetch(`${baseUrl}/v1/builds/${initialJob.jobId}`, {
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    assert.equal(poll.status, 200);
    finalJob = await poll.json();
  }

  assert.equal(
    finalJob.status,
    "SUCCEEDED",
    `Expected real Gradle build to succeed; logs:\n${finalJob.logs}\nError: ${finalJob.errorSummary}`
  );
  assert.ok(finalJob.artifact, "Expected verified APK artifact metadata");
  assert.equal(finalJob.artifact.apkVerified, true);
  assert.ok(finalJob.artifact.sizeBytes > 1024);
  assert.equal(finalJob.artifact.sha256.length, 64);

  // Cross-user artifact download blocked with 403
  const bobDownload = await fetch(`${baseUrl}${finalJob.artifact.downloadPath}`, {
    headers: { Authorization: `Bearer ${bobToken}` }
  });
  assert.equal(bobDownload.status, 403);

  // Owner artifact download serves authentic signed ZIP/APK binary matching SHA-256
  const apkResp = await fetch(`${baseUrl}${finalJob.artifact.downloadPath}`, {
    headers: { Authorization: `Bearer ${aliceToken}` }
  });
  assert.equal(apkResp.status, 200);
  const apkBuf = Buffer.from(await apkResp.arrayBuffer());
  assert.equal(apkBuf.length, finalJob.artifact.sizeBytes);
  assert.equal(apkBuf[0], 0x50);
  assert.equal(apkBuf[1], 0x4b);
  const downloadedSha = crypto.createHash("sha256").update(apkBuf).digest("hex");
  assert.equal(downloadedSha, finalJob.artifact.sha256);
});

test("Backend App Check verifier rejects missing, dummy JSON, and malformed tokens", async () => {
  const verifier = createFirebaseAuthVerifier({
    allowEmulatorTokens: true,
    requireAppCheck: true
  });
  const bearer = `Bearer ${makeEmulatorJwt("alice_uid")}`;

  await assert.rejects(
    () => verifier({ authorization: bearer }),
    /Missing required Firebase App Check/
  );
  await assert.rejects(
    () => verifier({ authorization: bearer, "x-firebase-appcheck": '{"error":"UNKNOWN_ERROR"}' }),
    /Firebase App Check token is invalid/
  );
  await assert.rejects(
    () => verifier({ authorization: bearer, "x-firebase-appcheck": "short-invalid-token" }),
    /Firebase App Check token is invalid/
  );

  const verified = await verifier({
    authorization: bearer,
    "x-firebase-appcheck": makeEmulatorJwt("app_check_subject")
  });
  assert.equal(verified.uid, "alice_uid");

  // Bootstrap endpoint explicitly skips X-Firebase-AppCheck while still verifying Firebase ID token
  const bootstrapVerified = await verifier(
    { authorization: bearer },
    { skipAppCheck: true }
  );
  assert.equal(bootstrapVerified.uid, "alice_uid");
});

test("App Check Debug Token Registrar handles missing config, validation, OAuth JWT exchange, and 20-token quota pruning", async () => {
  // 1. Missing service account returns honest 503
  const unconfiguredRegistrar = createAppCheckRegistrar({ env: {} });
  assert.equal(unconfiguredRegistrar.getStatus().configured, false);
  await assert.rejects(
    () =>
      unconfiguredRegistrar.registerDebugToken({
        authUser: { uid: "u1", email: "user@test.com" },
        debugToken: "11111111-2222-4333-8444-555555555555",
        deviceModel: "TECNO BG6"
      }),
    (err) => err.statusCode === 503 && err.code === "SERVICE_ACCOUNT_NOT_CONFIGURED"
  );

  // Generate ephemeral RSA-2048 keypair in test only to verify OAuth JWT assertion signing
  const { privateKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" }
  });

  const serviceAccountJson = JSON.stringify({
    project_id: "gen-lang-client-0645829429",
    client_email: "myraa-registrar@gen-lang-client-0645829429.iam.gserviceaccount.com",
    private_key: privateKey
  });

  let createCalls = 0;
  const deletedNames = [];
  let simulateQuotaExhaustedOnce = false;

  const mockFirebaseFetch = async (url, init = {}) => {
    const u = String(url);
    if (u === "https://oauth2.googleapis.com/token") {
      const form = new URLSearchParams(String(init.body || ""));
      assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
      const assertion = form.get("assertion");
      assert.equal(assertion.split(".").length, 3);
      return new Response(
        JSON.stringify({ access_token: "ya29.mock_access_token", expires_in: 3600 }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    if (
      u ===
      "https://firebaseappcheck.googleapis.com/v1/projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens"
    ) {
      createCalls += 1;
      const body = JSON.parse(String(init.body || "{}"));
      assert.ok(body.displayName.startsWith(MYRAA_AUTO_TOKEN_PREFIX));
      assert.equal(init.headers.authorization, "Bearer ya29.mock_access_token");

      if (simulateQuotaExhaustedOnce && createCalls === 2) {
        return new Response(
          JSON.stringify({ error: { status: "RESOURCE_EXHAUSTED", message: "Maximum 20 debug tokens limit reached" } }),
          { status: 429, headers: { "content-type": "application/json" } }
        );
      }

      return new Response(
        JSON.stringify({
          name: `projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/tok_${createCalls}`,
          displayName: body.displayName
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    if (u.includes("/debugTokens?pageSize=50")) {
      return new Response(
        JSON.stringify({
          debugTokens: [
            {
              name: "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/old_auto_1",
              displayName: "[MYRAA-AUTO] Old Phone (user1)"
            },
            {
              name: "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/manual_keep",
              displayName: "Manual Console Token"
            }
          ]
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    if (init.method === "DELETE" && u.includes("/debugTokens/old_auto_1")) {
      deletedNames.push("old_auto_1");
      return new Response("{}", { status: 200 });
    }

    return new Response("{}", { status: 404 });
  };

  const registrar = createAppCheckRegistrar({
    env: {
      FIREBASE_PROJECT_ID: "gen-lang-client-0645829429",
      FIREBASE_SERVICE_ACCOUNT_JSON: serviceAccountJson,
      APPCHECK_REGISTRAR_ALLOWED_EMAILS: "owner@myraa.test"
    },
    fetchImpl: mockFirebaseFetch
  });

  // Start an HTTP test server wired with requireAppCheck: true AND allowlist registrar
  const regServer = createMyraaBackendServer({
    verifyAuth: createFirebaseAuthVerifier({ allowEmulatorTokens: true, requireAppCheck: true }),
    appCheckRegistrar: registrar,
    aiRouter: createAiProviderRouter({ env: {}, fetchImpl: mockFirebaseFetch }),
    buildManager: createBuildQueueManager({ env: {} })
  });
  await new Promise((resolve) => regServer.listen(0, "127.0.0.1", resolve));
  const regBaseUrl = `http://127.0.0.1:${regServer.address().port}`;

  try {
    const ownerJwt = makeEmulatorJwt("owner_uid", "owner@myraa.test");
    const strangerJwt = makeEmulatorJwt("stranger_uid", "stranger@other.test");

    // Unauthenticated call -> 401
    const rUnauth = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, { method: "POST" });
    assert.equal(rUnauth.status, 401);

    // Non-allowlisted user -> 403
    const rForbidden = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, {
      method: "POST",
      headers: { Authorization: `Bearer ${strangerJwt}`, "content-type": "application/json" },
      body: JSON.stringify({ debugToken: "11111111-2222-4333-8444-555555555555" })
    });
    assert.equal(rForbidden.status, 403);

    // Cross-package attack targeting another app -> 403
    const rCrossPkg = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, {
      method: "POST",
      headers: { Authorization: `Bearer ${ownerJwt}`, "content-type": "application/json" },
      body: JSON.stringify({
        debugToken: "11111111-2222-4333-8444-555555555555",
        packageName: "com.aistudio.sakibchat.wkqz"
      })
    });
    assert.equal(rCrossPkg.status, 403);

    // Non-UUID token -> 400
    const rBadUuid = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, {
      method: "POST",
      headers: { Authorization: `Bearer ${ownerJwt}`, "content-type": "application/json" },
      body: JSON.stringify({ debugToken: "not-a-valid-uuid" })
    });
    assert.equal(rBadUuid.status, 400);

    // Status route -> 200 even before device has App Check header (skipAppCheck: true)
    const rStatus = await fetch(`${regBaseUrl}/v1/appcheck/registrar/status`, {
      headers: { Authorization: `Bearer ${ownerJwt}` }
    });
    assert.equal(rStatus.status, 200);
    const statusJson = await rStatus.json();
    assert.equal(statusJson.configured, true);
    assert.equal(statusJson.androidPackage, "com.aistudio.myraa.qxkrmp");

    // Normal registration succeeds with 200 without X-Firebase-AppCheck header
    const rOk = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, {
      method: "POST",
      headers: { Authorization: `Bearer ${ownerJwt}`, "content-type": "application/json" },
      body: JSON.stringify({
        debugToken: "85DB9E7C-1B4C-4296-BD09-2D106F22B2B4",
        deviceModel: "TECNO BG6"
      })
    });
    assert.equal(rOk.status, 200);
    const okJson = await rOk.json();
    assert.equal(okJson.registered, true);
    assert.ok(okJson.displayName.includes("TECNO BG6"));
    assert.equal(okJson.prunedCount, 0);

    // Quota exhaustion -> prunes oldest [MYRAA-AUTO] token and retries -> 200
    simulateQuotaExhaustedOnce = true;
    const rQuotaOk = await fetch(`${regBaseUrl}/v1/appcheck/debug-tokens/register`, {
      method: "POST",
      headers: { Authorization: `Bearer ${ownerJwt}`, "content-type": "application/json" },
      body: JSON.stringify({
        debugToken: "22222222-3333-4444-8555-666666666666",
        deviceModel: "TECNO BG6"
      })
    });
    assert.equal(rQuotaOk.status, 200);
    const quotaJson = await rQuotaOk.json();
    assert.equal(quotaJson.registered, true);
    assert.equal(quotaJson.prunedCount, 1);
    assert.deepEqual(deletedNames, ["old_auto_1"]);

    // Meanwhile non-bootstrap /v1/providers/status with requireAppCheck=true still blocks missing AppCheck
    const rBlocked = await fetch(`${regBaseUrl}/v1/providers/status`, {
      headers: { Authorization: `Bearer ${ownerJwt}` }
    });
    assert.equal(rBlocked.status, 401);
  } finally {
    await new Promise((resolve) => regServer.close(resolve));
  }
});

test("Free-tier AI providers support honest status classification, Cohere v2, OpenRouter discovery, Cloudflare Account ID, encrypted key vault, and auto-fallback", async () => {
  const vaultTmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "myraa-key-vault-"));
  let groqMode = "ok"; // ok | rate_limited | invalid_key | quota_expired | offline | unavailable
  let openRouterBalance = 5;

  const freeFetch = async (url, init = {}) => {
    const u = String(url);
    const authHeader = String(init?.headers?.Authorization || init?.headers?.authorization || "");

    // OpenRouter auth/key + models discovery
    if (u === "https://openrouter.ai/api/v1/auth/key") {
      if (authHeader.includes("bad-key")) {
        return new Response(JSON.stringify({ error: { message: "Invalid API key" } }), { status: 401 });
      }
      return new Response(
        JSON.stringify({ data: { label: "free-key", limit_remaining: openRouterBalance } }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    if (u === "https://openrouter.ai/api/v1/models") {
      return new Response(
        JSON.stringify({
          data: [
            { id: "qwen/qwen3-coder:free", pricing: { prompt: "0", completion: "0" } },
            { id: "openai/gpt-4.1", pricing: { prompt: "2", completion: "8" } },
            { id: "deepseek/deepseek-r1-0528:free", pricing: { prompt: "0", completion: "0" } }
          ]
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    // Groq
    if (u.startsWith("https://api.groq.com/openai/v1/")) {
      if (groqMode === "offline") throw new Error("fetch failed: ECONNREFUSED");
      if (groqMode === "rate_limited") {
        return new Response(JSON.stringify({ error: { message: "Rate limit reached" } }), { status: 429 });
      }
      if (groqMode === "invalid_key" || authHeader.includes("bad-groq")) {
        return new Response(JSON.stringify({ error: { message: "Invalid API key" } }), { status: 401 });
      }
      if (groqMode === "quota_expired") {
        return new Response(JSON.stringify({ error: { message: "Insufficient_quota exceeded" } }), { status: 402 });
      }
      if (groqMode === "unavailable") {
        return new Response(JSON.stringify({ error: { message: "Service unavailable" } }), { status: 503 });
      }
      if (u.endsWith("/models")) {
        return new Response(JSON.stringify({ data: [{ id: "llama-3.3-70b-versatile" }] }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          model: "llama-3.3-70b-versatile",
          choices: [{ message: { content: "Groq live free response" } }],
          usage: { prompt_tokens: 12, completion_tokens: 6 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    // Cohere v2 format
    if (u === "https://api.cohere.com/v1/models") {
      return new Response(JSON.stringify({ models: [{ name: "command-a-03-2025" }] }), { status: 200 });
    }
    if (u === "https://api.cohere.com/v2/chat") {
      const body = JSON.parse(String(init.body || "{}"));
      assert.ok(Array.isArray(body.messages));
      return new Response(
        JSON.stringify({
          message: { content: [{ type: "text", text: "Cohere v2 verified free response." }] },
          usage: { tokens: { input_tokens: 14, output_tokens: 9 } }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    // Cloudflare Workers AI
    if (u.includes("api.cloudflare.com/client/v4/accounts/cf_acct_999/ai/models/search")) {
      return new Response(JSON.stringify({ success: true, result: [] }), { status: 200 });
    }
    if (u.includes("api.cloudflare.com/client/v4/accounts/cf_acct_999/ai/v1/chat/completions")) {
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "Cloudflare Workers AI free response." } }],
          usage: { prompt_tokens: 11, completion_tokens: 7 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    // Mistral, NVIDIA NIM, SambaNova
    if (u.startsWith("https://api.mistral.ai/v1/") || u.startsWith("https://integrate.api.nvidia.com/v1/") || u.startsWith("https://api.sambanova.ai/v1/")) {
      if (u.endsWith("/models")) {
        return new Response(JSON.stringify({ data: [] }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: `Verified free response from ${new URL(u).hostname}` } }],
          usage: { prompt_tokens: 10, completion_tokens: 8 }
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }

    return new Response(JSON.stringify({ error: { message: `Unhandled test URL: ${u}` } }), { status: 404 });
  };

  const freeEnv = {
    MYRAA_KEY_VAULT_DIR: vaultTmpDir,
    MYRAA_KEY_VAULT_SECRET: "test-master-vault-secret-32-chars!!",
    CLOUDFLARE_API_TOKEN: "cf-env-token-without-account-id"
  };

  const freeRouter = createAiProviderRouter({ env: freeEnv, fetchImpl: freeFetch });
  const freeServer = createMyraaBackendServer({
    verifyAuth: createFirebaseAuthVerifier({ allowEmulatorTokens: true }),
    aiRouter: freeRouter,
    buildManager: createBuildQueueManager({ env: freeEnv })
  });

  await new Promise((resolve) => freeServer.listen(0, "127.0.0.1", resolve));
  const freeBaseUrl = `http://127.0.0.1:${freeServer.address().port}`;

  try {
    const aliceToken = makeEmulatorJwt("alice_free");
    const bobToken = makeEmulatorJwt("bob_free");

    // 1. freeOnly=true returns ONLY verified free-tier providers and zero paid ones; no keys -> MISSING_API_KEY
    const initialResp = await fetch(`${freeBaseUrl}/v1/providers/status?probe=true&freeOnly=true`, {
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    assert.equal(initialResp.status, 200);
    const { providers: initialProviders } = await initialResp.json();
    assert.equal(initialProviders.length, 7);
    assert.ok(initialProviders.every((p) => p.freeTier === true));
    assert.ok(initialProviders.every((p) => p.connected === false && p.statusCode === "MISSING_API_KEY"));

    // 2. Save Groq key in encrypted vault -> returns masked hint, verifies live, never returns plaintext key
    const rawGroqKey = "gsk_super_secret_test_key_9876";
    const saveGroq = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "groq", apiKey: rawGroqKey })
    });
    assert.equal(saveGroq.status, 200);
    const saveGroqText = await saveGroq.text();
    assert.equal(saveGroqText.includes(rawGroqKey), false, "Plaintext API key must never appear in HTTP responses");
    const saveGroqJson = JSON.parse(saveGroqText);
    assert.equal(saveGroqJson.provider.connected, true);
    assert.equal(saveGroqJson.provider.statusCode, "CONNECTED_LIVE");
    assert.equal(saveGroqJson.provider.keySource, "USER_VAULT");
    assert.ok(saveGroqJson.provider.maskedKeyHint.endsWith("9876"));

    // 3. Per-user vault isolation: Bob still sees Groq MISSING_API_KEY
    const bobStatus = await fetch(`${freeBaseUrl}/v1/providers/status?probe=true&freeOnly=true`, {
      headers: { Authorization: `Bearer ${bobToken}` }
    });
    const bobProviders = (await bobStatus.json()).providers;
    const bobGroq = bobProviders.find((p) => p.providerId === "groq");
    assert.equal(bobGroq.connected, false);
    assert.equal(bobGroq.statusCode, "MISSING_API_KEY");

    // 4. OpenRouter free model discovery + negative balance -> QUOTA_EXPIRED
    await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "openrouter_free", apiKey: "sk-or-v1-good-key-1234" })
    });
    const orOkStatus = await fetch(`${freeBaseUrl}/v1/providers/status?probe=true&freeOnly=true`, {
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    const orProvider = (await orOkStatus.json()).providers.find((p) => p.providerId === "openrouter_free");
    assert.equal(orProvider.connected, true);
    assert.ok(orProvider.models.includes("qwen/qwen3-coder:free"));
    assert.equal(orProvider.models.includes("openai/gpt-4.1"), false, "Paid OpenRouter models must be excluded");

    openRouterBalance = -1;
    const orQuotaStatus = await fetch(`${freeBaseUrl}/v1/providers/status?probe=true&freeOnly=true`, {
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    const orExpired = (await orQuotaStatus.json()).providers.find((p) => p.providerId === "openrouter_free");
    assert.equal(orExpired.connected, false);
    assert.equal(orExpired.statusCode, "QUOTA_EXPIRED");
    openRouterBalance = 5;

    // 5. Cohere v2 + Cloudflare Workers AI (token + Account ID)
    const saveCohere = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "cohere", apiKey: "cohere-free-trial-1234" })
    });
    assert.equal((await saveCohere.json()).provider.connected, true);

    const cohereGen = await fetch(`${freeBaseUrl}/v1/ai/generate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "cohere", modelId: "command-a-03-2025", prompt: "Hi Cohere" })
    });
    assert.equal(cohereGen.status, 200);
    const cohereJson = await cohereGen.json();
    assert.equal(cohereJson.text, "Cohere v2 verified free response.");
    assert.equal(cohereJson.promptTokens, 14);

    const saveCf = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({
        providerId: "cloudflare_ai",
        apiKey: "cf-user-token-5555",
        accountId: "cf_acct_999"
      })
    });
    assert.equal((await saveCf.json()).provider.connected, true);
    const cfGen = await fetch(`${freeBaseUrl}/v1/ai/generate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({
        providerId: "cloudflare_ai",
        modelId: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        prompt: "Hi Cloudflare"
      })
    });
    assert.equal(cfGen.status, 200);
    assert.equal((await cfGen.json()).text, "Cloudflare Workers AI free response.");

    // 6. Status classification checks on Groq (RATE_LIMITED, OFFLINE, UNAVAILABLE, INVALID_API_KEY)
    for (const [mode, expectedCode] of [
      ["rate_limited", "RATE_LIMITED"],
      ["quota_expired", "QUOTA_EXPIRED"],
      ["offline", "OFFLINE"],
      ["unavailable", "UNAVAILABLE"],
      ["invalid_key", "INVALID_API_KEY"]
    ]) {
      groqMode = mode;
      const st = await fetch(`${freeBaseUrl}/v1/providers/status?probe=true&freeOnly=true`, {
        headers: { Authorization: `Bearer ${aliceToken}` }
      });
      const g = (await st.json()).providers.find((p) => p.providerId === "groq");
      assert.equal(g.connected, false);
      assert.equal(g.statusCode, expectedCode);
    }

    // 7. Auto-fallback when Groq hits 429 RATE_LIMITED -> falls back automatically to another working free provider!
    groqMode = "rate_limited";
    const fallbackGen = await fetch(`${freeBaseUrl}/v1/ai/generate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({
        providerId: "groq",
        modelId: "llama-3.3-70b-versatile",
        prompt: "Test fallback",
        allowFallback: true
      })
    });
    assert.equal(fallbackGen.status, 200);
    const fallbackJson = await fallbackGen.json();
    assert.equal(fallbackJson.fallbackUsed, true);
    assert.equal(fallbackJson.fallbackFromProviderId, "groq");
    assert.equal(fallbackJson.fallbackReason, "RATE_LIMITED");
    assert.notEqual(fallbackJson.providerId, "groq");

    // 8. Delete key clears Groq back to MISSING_API_KEY
    groqMode = "ok";
    const delResp = await fetch(`${freeBaseUrl}/v1/providers/keys/groq`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    assert.equal(delResp.status, 200);
    const delJson = await delResp.json();
    assert.equal(delJson.deleted, true);
    assert.equal(delJson.provider.connected, false);
    assert.equal(delJson.provider.statusCode, "MISSING_API_KEY");
    assert.equal(delJson.provider.keySource, "NONE");

    // 9. Missing Cloudflare Account ID returns 400 ACCOUNT_ID_REQUIRED
    const cfMissingAcct = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "cloudflare_ai", apiKey: "cf-user-token-5555" })
    });
    assert.equal(cfMissingAcct.status, 400);
    const cfMissingJson = await cfMissingAcct.json();
    assert.equal(cfMissingJson.errorCode, "ACCOUNT_ID_REQUIRED");

    // 10. Invalid API key fails upstream verification and is NOT stored in vault
    const badGroqSave = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      method: "POST",
      headers: { Authorization: `Bearer ${aliceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ providerId: "groq", apiKey: "bad-groq-key-0000" })
    });
    assert.equal(badGroqSave.status, 200);
    const badGroqJson = await badGroqSave.json();
    assert.equal(badGroqJson.saved, false);
    assert.equal(badGroqJson.verified, false);
    assert.equal(badGroqJson.provider.connected, false);
    assert.equal(badGroqJson.provider.statusCode, "INVALID_API_KEY");
    assert.equal(badGroqJson.provider.keyConfigured, false);
    assert.equal(badGroqJson.provider.keySource, "NONE");
    assert.equal(badGroqJson.provider.maskedKeyHint, "");

    const keysAfterBad = await fetch(`${freeBaseUrl}/v1/providers/keys`, {
      headers: { Authorization: `Bearer ${aliceToken}` }
    });
    const groqAfterBad = (await keysAfterBad.json()).providers.find((p) => p.providerId === "groq");
    assert.equal(groqAfterBad.keyConfigured, false);
    assert.equal(groqAfterBad.keySource, "NONE");
  } finally {
    await new Promise((resolve) => freeServer.close(resolve));
    await fs.rm(vaultTmpDir, { recursive: true, force: true }).catch(() => {});
  }
});

test("App Check Registrar supports Cloud Run attached Service Account Metadata ADC when K_SERVICE is set", async () => {
  let metadataCalls = 0;
  let firebaseCreateAuth = "";

  const adcFetch = async (url, init = {}) => {
    const u = String(url);
    if (u.startsWith("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")) {
      metadataCalls += 1;
      assert.equal(init?.headers?.["Metadata-Flavor"], "Google");
      return new Response(JSON.stringify({ access_token: "ya29.cloud-run-adc-token", expires_in: 3000 }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (u.includes("/projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens")) {
      firebaseCreateAuth = String(init?.headers?.authorization || "");
      return new Response(
        JSON.stringify({
          name: "projects/514839656668/apps/1:514839656668:android:fe8f5c54f44c419f555c19/debugTokens/adc_tok_1",
          displayName: "[MYRAA-AUTO] TECNO BG6 (owner_uid)"
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    return new Response("{}", { status: 404 });
  };

  const registrar = createAppCheckRegistrar({
    env: {
      K_SERVICE: "myraa-backend",
      FIREBASE_PROJECT_ID: "gen-lang-client-0645829429"
    },
    fetchImpl: adcFetch
  });

  const status = registrar.getStatus();
  assert.equal(status.configured, true);
  assert.equal(status.credentialMode, "CLOUD_RUN_METADATA_ADC");

  const reg = await registrar.registerDebugToken({
    authUser: { uid: "owner_uid", email: "owner@myraa.test" },
    debugToken: "85db9e7c-1b4c-4296-bd09-2d106f22b2b4",
    deviceModel: "TECNO BG6"
  });
  assert.equal(reg.registered, true);
  assert.equal(metadataCalls, 1);
  assert.equal(firebaseCreateAuth, "Bearer ya29.cloud-run-adc-token");
});


