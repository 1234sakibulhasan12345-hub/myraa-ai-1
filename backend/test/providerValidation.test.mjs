import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { createAiProviderRouter } from "../src/aiProviders.mjs";
import { createFirebaseAuthVerifier } from "../src/auth.mjs";
import { createBuildQueueManager } from "../src/buildWorker.mjs";
import { createProviderKeyVault, normalizeCredentialInput } from "../src/providerKeyVault.mjs";
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

test("Credential normalization strips Bearer prefix, env var prefix, quotes, and zero-width chars without breaking keys", () => {
  assert.equal(normalizeCredentialInput('  "Bearer gsk_test_12345678"  ', "GROQ_API_KEY"), "gsk_test_12345678");
  assert.equal(normalizeCredentialInput("GROQ_API_KEY='gsk_env_key_9999'", "GROQ_API_KEY"), "gsk_env_key_9999");
  assert.equal(normalizeCredentialInput("\u200B\uFEFFsk-or-v1-abcd1234\r\n", "OPENROUTER_API_KEY"), "sk-or-v1-abcd1234");
});

test("Groq official validation uses /openai/v1/models Bearer auth, handles WAF 403 via chat fallback, and classifies errors accurately", async () => {
  const seenCalls = [];
  let mode = "valid_models"; // valid_models | waf_403_then_chat_ok | invalid_401 | rate_429 | unavailable_503 | network_down

  const mockFetch = async (url, init = {}) => {
    const u = String(url);
    const headers = init.headers || {};
    seenCalls.push({ url: u, method: init.method || "GET", auth: headers.Authorization, ua: headers["User-Agent"] });

    if (mode === "network_down") {
      throw Object.assign(new Error("connect ECONNREFUSED 104.18.0.1:443"), { code: "ECONNREFUSED" });
    }
    if (u === "https://api.groq.com/openai/v1/models") {
      assert.equal(headers.Authorization, "Bearer gsk_valid_key_1234");
      assert.equal(headers.Accept, "application/json");
      assert.ok(headers["User-Agent"]);

      if (mode === "valid_models") {
        return new Response(
          JSON.stringify({ data: [{ id: "llama-3.3-70b-versatile" }, { id: "whisper-large-v3" }] }),
          { status: 200, headers: { "content-type": "application/json" } }
        );
      }
      if (mode === "waf_403_then_chat_ok") {
        return new Response("<html><title>Just a moment...</title>Cloudflare Ray ID: 12345</html>", { status: 403 });
      }
      if (mode === "invalid_401") {
        return new Response(
          JSON.stringify({ error: { message: "Invalid API Key", type: "invalid_request_error", code: "invalid_api_key" } }),
          { status: 401, headers: { "content-type": "application/json" } }
        );
      }
      if (mode === "rate_429") {
        return new Response(JSON.stringify({ error: { message: "Too many requests" } }), { status: 429 });
      }
      if (mode === "unavailable_503") {
        return new Response(JSON.stringify({ error: { message: "Upstream maintenance" } }), { status: 503 });
      }
    }
    if (u === "https://api.groq.com/openai/v1/chat/completions") {
      assert.equal(headers.Authorization, "Bearer gsk_valid_key_1234");
      return new Response(
        JSON.stringify({ choices: [{ message: { content: "ok" } }] }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    }
    return new Response("{}", { status: 404 });
  };

  const router = createAiProviderRouter({
    env: { GROQ_API_KEY: '"Bearer gsk_valid_key_1234"' },
    fetchImpl: mockFetch
  });

  // 1. Valid Groq key (normalized from quoted Bearer header)
  const ok = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(ok.connected, true);
  assert.equal(ok.statusCode, "CONNECTED_LIVE");
  assert.equal(ok.errorCategory, "NONE");
  assert.ok(ok.models.includes("llama-3.3-70b-versatile"));
  assert.equal(ok.models.includes("whisper-large-v3"), false);

  // 2. HTML WAF 403 on /models triggers inference fallback and succeeds without false INVALID_API_KEY
  mode = "waf_403_then_chat_ok";
  const wafRecovered = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(wafRecovered.connected, true);
  assert.equal(wafRecovered.statusCode, "CONNECTED_LIVE");

  // 3. Explicit 401 -> INVALID_API_KEY / INVALID_KEY
  mode = "invalid_401";
  const bad = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(bad.connected, false);
  assert.equal(bad.statusCode, "INVALID_API_KEY");
  assert.equal(bad.errorCategory, "INVALID_KEY");

  // 4. 429 -> RATE_LIMITED
  mode = "rate_429";
  const rate = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(rate.connected, false);
  assert.equal(rate.statusCode, "RATE_LIMITED");
  assert.equal(rate.errorCategory, "RATE_LIMITED");

  // 5. 503 -> UNAVAILABLE / PROVIDER_UNAVAILABLE
  mode = "unavailable_503";
  const unavail = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(unavail.connected, false);
  assert.equal(unavail.statusCode, "UNAVAILABLE");
  assert.equal(unavail.errorCategory, "PROVIDER_UNAVAILABLE");

  // 6. ECONNREFUSED -> OFFLINE / NETWORK_ERROR
  mode = "network_down";
  const netErr = await router.probeSingleProvider("groq", { liveProbe: true });
  assert.equal(netErr.connected, false);
  assert.equal(netErr.statusCode, "OFFLINE");
  assert.equal(netErr.errorCategory, "NETWORK_ERROR");
});

test("All supported free & legacy providers validate using their official endpoints and auth headers", async () => {
  const requests = [];
  let nimKeyValid = true;
  let sambaKeyValid = true;
  let cloudflareScopedEditOnly = false;

  const mockFetch = async (url, init = {}) => {
    const u = String(url);
    const h = init.headers || {};
    requests.push({ u, method: init.method || "GET", auth: h.Authorization || h["x-api-key"] || "" });

    if (u === "https://api.groq.com/openai/v1/models") {
      assert.equal(h.Authorization, "Bearer gsk_test_12345678");
      return new Response(JSON.stringify({ data: [{ id: "llama-3.3-70b-versatile" }] }), { status: 200 });
    }
    if (u === "https://openrouter.ai/api/v1/auth/key") {
      assert.equal(h.Authorization, "Bearer sk-or-v1-test1234");
      assert.ok(h["HTTP-Referer"]);
      assert.ok(h["X-Title"]);
      return new Response(JSON.stringify({ data: { limit_remaining: 5 } }), { status: 200 });
    }
    if (u === "https://openrouter.ai/api/v1/models") {
      return new Response(JSON.stringify({ data: [{ id: "qwen/qwen3-coder:free", pricing: { prompt: "0", completion: "0" } }] }), { status: 200 });
    }
    if (u === "https://api.mistral.ai/v1/models") {
      assert.equal(h.Authorization, "Bearer mis_test_12345678");
      return new Response(JSON.stringify({ data: [{ id: "mistral-small-latest" }] }), { status: 200 });
    }
    if (u === "https://api.cohere.com/v1/models") {
      assert.equal(h.Authorization, "Bearer coh_test_12345678");
      return new Response(JSON.stringify({ models: [{ name: "command-a-03-2025" }] }), { status: 200 });
    }
    if (u === "https://api.cloudflare.com/client/v4/accounts/cf_acct_1234/ai/models/search?per_page=5") {
      assert.equal(h.Authorization, "Bearer cf_tok_12345678");
      if (cloudflareScopedEditOnly) {
        return new Response(JSON.stringify({ success: false, errors: [{ code: 10000, message: "Missing read permission" }] }), { status: 403 });
      }
      return new Response(JSON.stringify({ success: true, result: [] }), { status: 200 });
    }
    if (u === "https://api.cloudflare.com/client/v4/accounts/cf_acct_1234/ai/v1/chat/completions") {
      assert.equal(h.Authorization, "Bearer cf_tok_12345678");
      return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }] }), { status: 200 });
    }
    // NVIDIA NIM & SambaNova: /models is public (returns 200 even for bad keys); /chat/completions enforces auth!
    if (u === "https://integrate.api.nvidia.com/v1/models") {
      return new Response(JSON.stringify({ data: [{ id: "deepseek-ai/deepseek-v4.1-flash" }] }), { status: 200 });
    }
    if (u === "https://integrate.api.nvidia.com/v1/chat/completions") {
      if (!nimKeyValid) {
        return new Response(JSON.stringify({ status: 403, title: "Forbidden", detail: "Authorization failed" }), { status: 403 });
      }
      return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }] }), { status: 200 });
    }
    if (u === "https://api.sambanova.ai/v1/models") {
      return new Response(JSON.stringify({ data: [{ id: "Meta-Llama-3.3-70B-Instruct" }] }), { status: 200 });
    }
    if (u === "https://api.sambanova.ai/v1/chat/completions") {
      if (!sambaKeyValid) {
        return new Response(JSON.stringify({ error: { message: "Incorrect API key provided", code: "invalid_api_key" } }), { status: 401 });
      }
      return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }] }), { status: 200 });
    }
    // Legacy paid providers
    if (u === "https://api.openai.com/v1/models") {
      assert.equal(h.Authorization, "Bearer sk-openai-test-1234");
      return new Response(JSON.stringify({ data: [] }), { status: 200 });
    }
    if (u === "https://api.anthropic.com/v1/models") {
      assert.equal(h["x-api-key"], "sk-ant-test-1234");
      assert.equal(h["anthropic-version"], "2023-06-01");
      return new Response(JSON.stringify({ data: [] }), { status: 200 });
    }
    if (u === "https://api.deepseek.com/models") {
      assert.equal(h.Authorization, "Bearer sk-deepseek-test-1234");
      return new Response(JSON.stringify({ data: [] }), { status: 200 });
    }
    if (u === "https://api.llama.com/compat/v1/models") {
      assert.equal(h.Authorization, "Bearer llm-test-12345678");
      return new Response(JSON.stringify({ data: [] }), { status: 200 });
    }
    return new Response("{}", { status: 404 });
  };

  const router = createAiProviderRouter({
    env: {
      GROQ_API_KEY: "gsk_test_12345678",
      OPENROUTER_API_KEY: "sk-or-v1-test1234",
      MISTRAL_API_KEY: "mis_test_12345678",
      COHERE_API_KEY: "coh_test_12345678",
      CLOUDFLARE_API_TOKEN: "cf_tok_12345678",
      CLOUDFLARE_ACCOUNT_ID: "cf_acct_1234",
      NVIDIA_NIM_API_KEY: "nvapi-valid-test-1234",
      SAMBANOVA_API_KEY: "sn-valid-test-1234",
      OPENAI_API_KEY: "sk-openai-test-1234",
      ANTHROPIC_API_KEY: "sk-ant-test-1234",
      DEEPSEEK_API_KEY: "sk-deepseek-test-1234",
      META_LLAMA_API_KEY: "llm-test-12345678"
    },
    fetchImpl: mockFetch
  });

  const allStatuses = await router.getProviderStatuses({ liveProbe: true });
  assert.equal(allStatuses.length, 11);
  for (const st of allStatuses) {
    assert.equal(st.connected, true, `Expected ${st.providerId} to connect, got ${st.statusCode}: ${st.message}`);
    assert.equal(st.statusCode, "CONNECTED_LIVE");
  }

  // Invalid NVIDIA NIM key MUST be caught despite public 200 on /v1/models
  nimKeyValid = false;
  const badNim = await router.probeSingleProvider("nvidia_nim", { liveProbe: true });
  assert.equal(badNim.connected, false);
  assert.equal(badNim.statusCode, "INVALID_API_KEY");
  assert.equal(badNim.errorCategory, "INVALID_KEY");

  // Invalid SambaNova key MUST be caught despite public 200 on /v1/models
  sambaKeyValid = false;
  const badSamba = await router.probeSingleProvider("sambanova", { liveProbe: true });
  assert.equal(badSamba.connected, false);
  assert.equal(badSamba.statusCode, "INVALID_API_KEY");
  assert.equal(badSamba.errorCategory, "INVALID_KEY");

  // Cloudflare token with Edit-only permission on /ai/v1/chat/completions succeeds via fallback even if /models/search returns 403
  cloudflareScopedEditOnly = true;
  const cfScoped = await router.probeSingleProvider("cloudflare_ai", { liveProbe: true });
  assert.equal(cfScoped.connected, true);
  assert.equal(cfScoped.statusCode, "CONNECTED_LIVE");

  // Bad Cloudflare Account ID syntax -> CONFIGURATION_ERROR (never mislabeled INVALID_API_KEY)
  const badCfAcct = await router.probeCandidateCredentials("cloudflare_ai", {
    apiKey: "cf_tok_12345678",
    accountId: "https://dash.cloudflare.com/acct"
  });
  assert.equal(badCfAcct.connected, false);
  assert.equal(badCfAcct.statusCode, "CONFIGURATION_ERROR");
  assert.equal(badCfAcct.errorCategory, "CONFIGURATION_ERROR");
});

test("Encrypted vault preserves existing valid keys on failed candidate save and surfaces CONFIGURATION_ERROR if vault key mismatches", async () => {
  const vaultTmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "myraa-vault-preserve-"));
  try {
    const mockFetch = async (url, init = {}) => {
      const u = String(url);
      const auth = String(init?.headers?.Authorization || "");
      if (u === "https://api.groq.com/openai/v1/models") {
        if (auth === "Bearer gsk_existing_good_1111") {
          return new Response(JSON.stringify({ data: [{ id: "llama-3.3-70b-versatile" }] }), { status: 200 });
        }
        return new Response(JSON.stringify({ error: { message: "Invalid API Key" } }), { status: 401 });
      }
      return new Response("{}", { status: 404 });
    };

    const vault = createProviderKeyVault({
      vaultDir: vaultTmpDir,
      vaultSecret: "secret-master-key-version-1-abcdef"
    });
    const aiRouter = createAiProviderRouter({ keyVault: vault, fetchImpl: mockFetch });
    const server = createMyraaBackendServer({
      verifyAuth: createFirebaseAuthVerifier({ allowEmulatorTokens: true }),
      aiRouter,
      buildManager: createBuildQueueManager({ env: {} })
    });

    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${server.address().port}`;
    const jwt = makeEmulatorJwt("vault_owner");

    try {
      // 1. Save valid Groq key -> encrypted on disk
      const rGood = await fetch(`${url}/v1/providers/keys`, {
        method: "POST",
        headers: { Authorization: `Bearer ${jwt}`, "content-type": "application/json" },
        body: JSON.stringify({ providerId: "groq", apiKey: "gsk_existing_good_1111" })
      });
      const jGood = await rGood.json();
      assert.equal(jGood.saved, true);
      assert.equal(jGood.verified, true);

      // Verify encrypted file on disk contains ZERO plaintext key
      const rawDisk = await fs.readFile(path.join(vaultTmpDir, "vault_owner.enc.json"), "utf8");
      assert.equal(rawDisk.includes("gsk_existing_good_1111"), false);

      // 2. Attempt saving an invalid key -> rejected WITHOUT replacing or deleting the existing valid key!
      const rBad = await fetch(`${url}/v1/providers/keys`, {
        method: "POST",
        headers: { Authorization: `Bearer ${jwt}`, "content-type": "application/json" },
        body: JSON.stringify({ providerId: "groq", apiKey: "gsk_wrong_candidate_2222" })
      });
      const jBad = await rBad.json();
      assert.equal(jBad.saved, false);
      assert.equal(jBad.verified, false);
      assert.equal(jBad.preservedExistingKey, true);
      assert.equal(jBad.provider.statusCode, "INVALID_API_KEY");
      assert.equal(jBad.provider.keyConfigured, true);
      assert.equal(jBad.provider.maskedKeyHint, "gsk_••••1111");

      // Existing key in vault still works cleanly!
      const statusAfter = await (
        await fetch(`${url}/v1/providers/status?probe=true&freeOnly=true`, {
          headers: { Authorization: `Bearer ${jwt}` }
        })
      ).json();
      const groqNow = statusAfter.providers.find((p) => p.providerId === "groq");
      assert.equal(groqNow.connected, true);
      assert.equal(groqNow.statusCode, "CONNECTED_LIVE");
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }

    // 3. Simulated MYRAA_KEY_VAULT_SECRET rotation surfaces CONFIGURATION_ERROR instead of false MISSING/INVALID key
    const rotatedVault = createProviderKeyVault({
      vaultDir: vaultTmpDir,
      vaultSecret: "different-rotated-secret-abcdef-999"
    });
    const rotatedRouter = createAiProviderRouter({ keyVault: rotatedVault, fetchImpl: mockFetch });
    const rotatedGroq = await rotatedRouter.probeSingleProvider("groq", { liveProbe: true, userId: "vault_owner" });
    assert.equal(rotatedGroq.connected, false);
    assert.equal(rotatedGroq.statusCode, "CONFIGURATION_ERROR");
    assert.equal(rotatedGroq.errorCategory, "CONFIGURATION_ERROR");
  } finally {
    await fs.rm(vaultTmpDir, { recursive: true, force: true }).catch(() => {});
  }
});

test("Auth middleware 401 returns explicit errorCode FIREBASE_AUTH_REQUIRED or APP_CHECK_REQUIRED", async () => {
  const server = createMyraaBackendServer({
    verifyAuth: createFirebaseAuthVerifier({ allowEmulatorTokens: true, requireAppCheck: true }),
    aiRouter: createAiProviderRouter({ env: {} }),
    buildManager: createBuildQueueManager({ env: {} })
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    const rNoToken = await fetch(`${url}/v1/providers/status`);
    assert.equal(rNoToken.status, 401);
    assert.equal((await rNoToken.json()).errorCode, "FIREBASE_AUTH_REQUIRED");

    const rNoAppCheck = await fetch(`${url}/v1/providers/status`, {
      headers: { Authorization: `Bearer ${makeEmulatorJwt("u1")}` }
    });
    assert.equal(rNoAppCheck.status, 401);
    assert.equal((await rNoAppCheck.json()).errorCode, "APP_CHECK_REQUIRED");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("Cross-provider key prefix mismatch (e.g. OpenRouter sk-or- pasted into Groq) returns CONFIGURATION_ERROR", async () => {
  let upstreamCalled = false;
  const router = createAiProviderRouter({
    env: {},
    fetchImpl: async () => {
      upstreamCalled = true;
      return new Response("{}", { status: 200 });
    }
  });
  const res = await router.probeCandidateCredentials("groq", {
    apiKey: "sk-or-v1-test_openrouter_prefix_0000"
  });
  assert.equal(upstreamCalled, false);
  assert.equal(res.connected, false);
  assert.equal(res.statusCode, "CONFIGURATION_ERROR");
  assert.equal(res.errorCategory, "CONFIGURATION_ERROR");
  assert.match(res.message, /OpenRouter Free Models/i);
  assert.match(res.message, /gsk_/i);
});

