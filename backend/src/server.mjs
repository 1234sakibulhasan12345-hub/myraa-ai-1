import fs from "node:fs";
import fsPromises from "node:fs/promises";
import http from "node:http";
import {
  AppCheckRegistrarError,
  createAppCheckRegistrar
} from "./appCheckRegistrar.mjs";
import { createAiProviderRouter, ProviderCallError } from "./aiProviders.mjs";
import { AuthError, createFirebaseAuthVerifier } from "./auth.mjs";
import { BuildValidationError } from "./buildSecurity.mjs";
import { createBuildQueueManager } from "./buildWorker.mjs";

const MAX_BODY_BYTES = 8 * 1024 * 1024;

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body)
  });
  res.end(body);
}

async function readJsonBody(req) {
  const chunks = [];
  let received = 0;
  for await (const chunk of req) {
    received += chunk.length;
    if (received > MAX_BODY_BYTES) {
      throw Object.assign(new Error("Request payload exceeds 8 MB limit."), { statusCode: 413 });
    }
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw.trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    throw Object.assign(new Error("Malformed JSON request body."), { statusCode: 400 });
  }
}

export function createMyraaBackendServer(options = {}) {
  const verifyAuth = options.verifyAuth || createFirebaseAuthVerifier(options);
  const aiRouter = options.aiRouter || createAiProviderRouter(options);
  const buildManager = options.buildManager || createBuildQueueManager(options);
  const appCheckRegistrar = options.appCheckRegistrar || createAppCheckRegistrar(options);

  return http.createServer(async (req, res) => {
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname;

    try {
      if (req.method === "GET" && pathname === "/health") {
        return sendJson(res, 200, {
          status: "ok",
          service: "myraa-super-brain-backend",
          appCheckAutoRegistrarConfigured: appCheckRegistrar.getStatus().configured,
          timestampEpochMs: Date.now()
        });
      }

      // All /v1/* endpoints require verified Firebase ID token.
      // App Check bootstrap endpoints skip the App Check header so unregistered devices can auto-register.
      const isAppCheckBootstrapRoute =
        pathname === "/v1/appcheck/debug-tokens/register" ||
        pathname === "/v1/appcheck/registrar/status";
      const authUser = await verifyAuth(req.headers, { skipAppCheck: isAppCheckBootstrapRoute });

      if (req.method === "GET" && pathname === "/v1/appcheck/registrar/status") {
        return sendJson(res, 200, appCheckRegistrar.getStatus());
      }

      if (req.method === "POST" && pathname === "/v1/appcheck/debug-tokens/register") {
        const body = await readJsonBody(req);
        const result = await appCheckRegistrar.registerDebugToken({
          authUser,
          debugToken: body.debugToken,
          deviceModel: body.deviceModel,
          packageName: body.packageName,
          appId: body.appId
        });
        return sendJson(res, 200, result);
      }

      if (req.method === "GET" && pathname === "/v1/providers/status") {
        const probeLive = url.searchParams.get("probe") === "true";
        const freeOnly = url.searchParams.get("freeOnly") === "true";
        const providers = await aiRouter.getProviderStatuses({
          liveProbe: probeLive,
          userId: authUser.uid,
          freeOnly
        });
        return sendJson(res, 200, { providers });
      }

      if (req.method === "GET" && pathname === "/v1/providers/keys") {
        const statuses = await aiRouter.getProviderStatuses({
          liveProbe: false,
          userId: authUser.uid,
          freeOnly: true
        });
        return sendJson(res, 200, {
          providers: statuses.map((p) => ({
            providerId: p.providerId,
            displayName: p.displayName,
            keyConfigured: p.keyConfigured,
            keySource: p.keySource,
            maskedKeyHint: p.maskedKeyHint,
            requiresAccountId: p.requiresAccountId,
            hasAccountId: p.hasAccountId,
            statusCode: p.statusCode
          }))
        });
      }

      if (req.method === "POST" && pathname === "/v1/providers/keys") {
        const body = await readJsonBody(req);
        const providerId = String(body.providerId || "").trim();
        const accountId = String(body.accountId || "").trim();
        if (!providerId) {
          return sendJson(res, 400, { error: "providerId is required.", errorCode: "INVALID_PROVIDER" });
        }
        if (providerId === "cloudflare_ai" && !accountId) {
          return sendJson(res, 400, {
            error: "Cloudflare Account ID is required along with CLOUDFLARE_API_TOKEN.",
            errorCode: "ACCOUNT_ID_REQUIRED"
          });
        }
        aiRouter.keyVault.setProviderCredentials(authUser.uid, providerId, {
          apiKey: body.apiKey,
          accountId
        });
        const verifiedStatus = await aiRouter.probeSingleProvider(providerId, {
          liveProbe: true,
          userId: authUser.uid
        });
        if (verifiedStatus.statusCode === "INVALID_API_KEY") {
          aiRouter.keyVault.deleteProviderCredentials(authUser.uid, providerId);
          return sendJson(res, 200, {
            saved: false,
            verified: false,
            provider: {
              ...verifiedStatus,
              keyConfigured: false,
              keySource: "NONE",
              maskedKeyHint: ""
            }
          });
        }
        return sendJson(res, 200, {
          saved: true,
          verified: Boolean(verifiedStatus.connected),
          provider: verifiedStatus
        });
      }

      const keyDeleteMatch = pathname.match(/^\/v1\/providers\/keys\/([a-z0-9_]{2,48})$/);
      if (req.method === "DELETE" && keyDeleteMatch) {
        const providerId = keyDeleteMatch[1];
        aiRouter.keyVault.deleteProviderCredentials(authUser.uid, providerId);
        const updatedStatus = await aiRouter.probeSingleProvider(providerId, {
          liveProbe: false,
          userId: authUser.uid
        });
        return sendJson(res, 200, { deleted: true, provider: updatedStatus });
      }

      if (req.method === "POST" && (pathname === "/v1/ai/plan" || pathname === "/v1/ai/generate")) {
        const body = await readJsonBody(req);
        const result = await aiRouter.generateText({
          providerId: body.providerId,
          modelId: body.modelId,
          systemPrompt: body.systemPrompt,
          prompt: String(body.prompt || ""),
          jsonMode: pathname === "/v1/ai/plan" || Boolean(body.jsonMode),
          userId: authUser.uid,
          allowFallback: Boolean(body.allowFallback)
        });
        return sendJson(res, 200, result);
      }

      if (req.method === "POST" && pathname === "/v1/ai/stream") {
        const body = await readJsonBody(req);
        const result = await aiRouter.generateText({
          providerId: body.providerId,
          modelId: body.modelId,
          systemPrompt: body.systemPrompt,
          prompt: String(body.prompt || ""),
          jsonMode: false,
          userId: authUser.uid,
          allowFallback: Boolean(body.allowFallback)
        });
        res.writeHead(200, {
          "content-type": "text/event-stream; charset=utf-8",
          "cache-control": "no-cache",
          connection: "keep-alive"
        });
        const chunks = result.text.match(/.{1,120}/gs) || [result.text];
        for (const piece of chunks) {
          res.write(`data: ${JSON.stringify({ delta: piece })}\n\n`);
        }
        res.write(
          `data: ${JSON.stringify({
            done: true,
            providerId: result.providerId,
            modelId: result.modelId,
            promptTokens: result.promptTokens,
            completionTokens: result.completionTokens,
            latencyMs: result.latencyMs,
            fallbackUsed: Boolean(result.fallbackUsed),
            fallbackFromProviderId: result.fallbackFromProviderId || null
          })}\n\n`
        );
        return res.end();
      }

      if (req.method === "POST" && pathname === "/v1/builds") {
        const body = await readJsonBody(req);
        const job = await buildManager.enqueueBuild({
          userId: authUser.uid,
          projectId: String(body.projectId || "default"),
          buildType: body.buildType || "DEBUG",
          appName: body.appName,
          packageName: body.packageName,
          versionName: body.versionName || "1.0.0",
          versionCode: body.versionCode || 1,
          files: body.files
        });
        return sendJson(res, 202, job);
      }

      const buildMatch = pathname.match(/^\/v1\/builds\/([A-Za-z0-9_\-]+)$/);
      if (req.method === "GET" && buildMatch) {
        const job = buildManager.getJob(buildMatch[1]);
        if (!job) return sendJson(res, 404, { error: "Build job not found." });
        if (job.userId !== authUser.uid) {
          return sendJson(res, 403, { error: "Forbidden: build job belongs to another user." });
        }
        return sendJson(res, 200, job);
      }

      const artifactMatch = pathname.match(/^\/v1\/artifacts\/([A-Za-z0-9_\-]+)\/apk$/);
      if (req.method === "GET" && artifactMatch) {
        const artifact = buildManager.getArtifactForDownload(artifactMatch[1]);
        if (!artifact) {
          return sendJson(res, 404, { error: "Verified APK artifact not found or build incomplete." });
        }
        if (artifact.userId !== authUser.uid) {
          return sendJson(res, 403, { error: "Forbidden: APK artifact belongs to another user." });
        }
        if (Date.now() > artifact.expiresAtEpochMs) {
          return sendJson(res, 410, { error: "APK artifact download window expired." });
        }
        const stat = await fsPromises.stat(artifact.storedPath).catch(() => null);
        if (!stat || stat.size <= 0) {
          return sendJson(res, 410, { error: "APK binary no longer exists on server." });
        }

        res.writeHead(200, {
          "content-type": "application/vnd.android.package-archive",
          "content-length": stat.size,
          "content-disposition": `attachment; filename="${artifact.fileName}"`,
          "x-artifact-sha256": artifact.sha256
        });
        fs.createReadStream(artifact.storedPath).pipe(res);
        return;
      }

      return sendJson(res, 404, { error: "Endpoint not found." });
    } catch (err) {
      if (err instanceof AuthError) {
        return sendJson(res, err.statusCode, { error: err.message });
      }
      if (err instanceof AppCheckRegistrarError) {
        return sendJson(res, err.statusCode, { error: err.message, code: err.code });
      }
      if (err instanceof BuildValidationError) {
        return sendJson(res, 400, { error: err.message });
      }
      if (err instanceof ProviderCallError) {
        return sendJson(res, err.statusCode, { error: err.message, errorCode: err.errorCode });
      }
      const status = err?.statusCode || 500;
      return sendJson(res, status, { error: err?.message || "Internal server error." });
    }
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT || 8088);
  const server = createMyraaBackendServer();
  server.listen(port, "0.0.0.0", () => {
    console.log(`MYRAA Backend & APK Build Server listening on :${port}`);
  });
}
