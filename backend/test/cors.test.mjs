import test from "node:test";
import assert from "node:assert/strict";
import { createMyraaBackendServer } from "../src/server.mjs";
import { createFirebaseAuthVerifier } from "../src/auth.mjs";
import { createAiProviderRouter } from "../src/aiProviders.mjs";
import { createBuildQueueManager } from "../src/buildWorker.mjs";

test("CORS strictly allows configured web origins and blocks disallowed browser origins", async () => {
  const server = createMyraaBackendServer({
    verifyAuth: createFirebaseAuthVerifier({ allowEmulatorTokens: true, requireAppCheck: false }),
    aiRouter: createAiProviderRouter({ env: {} }),
    buildManager: createBuildQueueManager({ env: {} }),
    allowedOrigins: ["https://myraa-ai-web.onrender.com"],
    allowLocalDev: false
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    // 1. Allowed browser origin preflight succeeds with exact origin echo (never '*')
    const preflightAllowed = await fetch(`${url}/v1/providers/status`, {
      method: "OPTIONS",
      headers: {
        Origin: "https://myraa-ai-web.onrender.com",
        "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "Authorization, X-Firebase-AppCheck"
      }
    });
    assert.equal(preflightAllowed.status, 204);
    assert.equal(
      preflightAllowed.headers.get("access-control-allow-origin"),
      "https://myraa-ai-web.onrender.com"
    );
    assert.match(
      preflightAllowed.headers.get("access-control-allow-headers") || "",
      /X-Firebase-AppCheck/i
    );

    // 2. Allowed origin GET /health receives CORS header
    const healthAllowed = await fetch(`${url}/health`, {
      headers: { Origin: "https://myraa-ai-web.onrender.com" }
    });
    assert.equal(healthAllowed.status, 200);
    assert.equal(
      healthAllowed.headers.get("access-control-allow-origin"),
      "https://myraa-ai-web.onrender.com"
    );

    // 3. Disallowed origin preflight fails with 403 and no Allow-Origin header
    const preflightBlocked = await fetch(`${url}/v1/providers/status`, {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example.com" }
    });
    assert.equal(preflightBlocked.status, 403);
    assert.equal(preflightBlocked.headers.get("access-control-allow-origin"), null);

    // 4. Native Android / curl requests without Origin continue working normally
    const nativeHealth = await fetch(`${url}/health`);
    assert.equal(nativeHealth.status, 200);
    assert.equal(nativeHealth.headers.get("access-control-allow-origin"), null);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
