import crypto from "node:crypto";
import fs from "node:fs";

export const DEFAULT_FIREBASE_PROJECT_ID = "gen-lang-client-0645829429";
export const DEFAULT_FIREBASE_PROJECT_NUMBER = "514839656668";
export const DEFAULT_ANDROID_APP_ID = "1:514839656668:android:fe8f5c54f44c419f555c19";
export const DEFAULT_ANDROID_PACKAGE = "com.aistudio.myraa.qxkrmp";
export const MYRAA_AUTO_TOKEN_PREFIX = "[MYRAA-AUTO]";

const GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FIREBASE_APPCHECK_API_BASE = "https://firebaseappcheck.googleapis.com/v1";
const CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class AppCheckRegistrarError extends Error {
  constructor(message, statusCode = 500, code = "APPCHECK_REGISTRAR_ERROR") {
    super(message);
    this.name = "AppCheckRegistrarError";
    this.statusCode = statusCode;
    this.code = code;
  }
}

function parseCommaList(raw) {
  return String(raw || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseServiceAccountFromEnv(env, expectedProjectId) {
  const inlineRaw = (env.FIREBASE_SERVICE_ACCOUNT_JSON || "").trim();
  const filePath = (
    env.FIREBASE_SERVICE_ACCOUNT_PATH ||
    env.GOOGLE_APPLICATION_CREDENTIALS ||
    ""
  ).trim();

  let jsonText = "";
  if (inlineRaw) {
    if (inlineRaw.startsWith("{")) {
      jsonText = inlineRaw;
    } else {
      jsonText = Buffer.from(inlineRaw, "base64").toString("utf8");
    }
  } else if (filePath) {
    if (!fs.existsSync(filePath)) {
      throw new AppCheckRegistrarError(
        `Configured service account file was not found on backend host.`,
        503,
        "SERVICE_ACCOUNT_FILE_MISSING"
      );
    }
    jsonText = fs.readFileSync(filePath, "utf8");
  } else {
    return null;
  }

  let parsed;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    throw new AppCheckRegistrarError(
      "FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON.",
      500,
      "SERVICE_ACCOUNT_INVALID_JSON"
    );
  }

  const clientEmail = String(parsed.client_email || "").trim();
  const privateKey = String(parsed.private_key || "").replace(/\\n/g, "\n").trim();
  const keyProjectId = String(parsed.project_id || "").trim();

  if (!clientEmail || !privateKey) {
    throw new AppCheckRegistrarError(
      "Service account JSON must contain client_email and private_key.",
      500,
      "SERVICE_ACCOUNT_INCOMPLETE"
    );
  }

  if (keyProjectId && keyProjectId !== expectedProjectId) {
    throw new AppCheckRegistrarError(
      `Service account project_id '${keyProjectId}' does not match target Firebase project '${expectedProjectId}'.`,
      403,
      "PROJECT_ISOLATION_MISMATCH"
    );
  }

  return {
    clientEmail,
    privateKey,
    projectId: keyProjectId || expectedProjectId,
    tokenUri: String(parsed.token_uri || GOOGLE_OAUTH_TOKEN_URL).trim()
  };
}

function createServiceAccountJwtAssertion(serviceAccount, nowSec = Math.floor(Date.now() / 1000)) {
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(
    JSON.stringify({
      iss: serviceAccount.clientEmail,
      scope: CLOUD_PLATFORM_SCOPE,
      aud: serviceAccount.tokenUri || GOOGLE_OAUTH_TOKEN_URL,
      iat: nowSec,
      exp: nowSec + 3600
    })
  ).toString("base64url");

  const signingInput = `${header}.${payload}`;
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(signingInput);
  signer.end();
  const signature = signer.sign(serviceAccount.privateKey).toString("base64url");
  return `${signingInput}.${signature}`;
}

function sanitizeDisplayName(deviceModel, uid) {
  const safeDevice = String(deviceModel || "Android Device")
    .replace(/[^A-Za-z0-9 ._\-/]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 48) || "Android Device";
  const shortUid = String(uid || "user").replace(/[^A-Za-z0-9]/g, "").slice(0, 8) || "user";
  return `${MYRAA_AUTO_TOKEN_PREFIX} ${safeDevice} (${shortUid})`;
}

export function maskDebugToken(token) {
  const clean = String(token || "").trim();
  if (clean.length < 8) return "********";
  return `${clean.slice(0, 8)}-****-****-****-************`;
}

export function createAppCheckRegistrar(options = {}) {
  const env = options.env || process.env;
  const fetchImpl = options.fetchImpl || fetch;
  const projectId = options.firebaseProjectId || env.FIREBASE_PROJECT_ID || DEFAULT_FIREBASE_PROJECT_ID;
  const projectNumber = options.firebaseProjectNumber || env.FIREBASE_PROJECT_NUMBER || DEFAULT_FIREBASE_PROJECT_NUMBER;
  const androidAppId = options.androidAppId || env.FIREBASE_ANDROID_APP_ID || DEFAULT_ANDROID_APP_ID;
  const androidPackage = options.androidPackage || env.ANDROID_PACKAGE_NAME || DEFAULT_ANDROID_PACKAGE;
  const allowedUids = parseCommaList(options.allowedUids ?? env.APPCHECK_REGISTRAR_ALLOWED_UIDS);
  const allowedEmails = parseCommaList(options.allowedEmails ?? env.APPCHECK_REGISTRAR_ALLOWED_EMAILS).map((e) =>
    e.toLowerCase()
  );

  const useMetadataAdc =
    Boolean(options.useMetadataAdc) ||
    Boolean(env.K_SERVICE) ||
    env.USE_GCP_METADATA_ADC === "true";

  let cachedAccessToken = null;
  let cachedTokenExpiresAtMs = 0;

  function resolveCredentialsOrNull() {
    if (options.serviceAccount) return options.serviceAccount;
    return parseServiceAccountFromEnv(env, projectId);
  }

  function getStatus() {
    try {
      const creds = resolveCredentialsOrNull();
      if (creds) {
        return {
          configured: true,
          credentialMode: "SERVICE_ACCOUNT_ENV",
          projectId,
          projectNumber,
          androidAppId,
          androidPackage,
          allowlistRestricted: allowedUids.length > 0 || allowedEmails.length > 0,
          reason: "Automatic Firebase App Check Debug Token registrar is active (Service Account JSON)."
        };
      }
      if (useMetadataAdc) {
        return {
          configured: true,
          credentialMode: "CLOUD_RUN_METADATA_ADC",
          projectId,
          projectNumber,
          androidAppId,
          androidPackage,
          allowlistRestricted: allowedUids.length > 0 || allowedEmails.length > 0,
          reason: "Automatic Firebase App Check Debug Token registrar is active via Cloud Run Service Account ADC."
        };
      }
      return {
        configured: false,
        credentialMode: "NONE",
        projectId,
        projectNumber,
        androidAppId,
        androidPackage,
        allowlistRestricted: allowedUids.length > 0 || allowedEmails.length > 0,
        reason: "Missing FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_SERVICE_ACCOUNT_PATH in backend environment."
      };
    } catch (err) {
      return {
        configured: false,
        credentialMode: "ERROR",
        projectId,
        projectNumber,
        androidAppId,
        androidPackage,
        allowlistRestricted: allowedUids.length > 0 || allowedEmails.length > 0,
        reason: err.message || "Invalid backend service account configuration."
      };
    }
  }

  async function fetchCloudRunMetadataAccessToken(now) {
    const metadataUrl =
      "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" +
      `?scopes=${encodeURIComponent(CLOUD_PLATFORM_SCOPE)}`;
    const resp = await fetchImpl(metadataUrl, {
      method: "GET",
      headers: { "Metadata-Flavor": "Google" }
    });
    const raw = await resp.text();
    let parsed = {};
    try {
      parsed = JSON.parse(raw);
    } catch {
      parsed = {};
    }
    if (!resp.ok || !parsed.access_token) {
      throw new AppCheckRegistrarError(
        `Cloud Run attached service account token unavailable (HTTP ${resp.status}). Ensure Cloud Run service account has 'Firebase App Check Admin' role on ${projectId} or configure FIREBASE_SERVICE_ACCOUNT_JSON.`,
        503,
        "METADATA_ADC_UNAVAILABLE"
      );
    }
    const expiresInSec = Number(parsed.expires_in || 3000);
    cachedAccessToken = parsed.access_token;
    cachedTokenExpiresAtMs = now + expiresInSec * 1000;
    return cachedAccessToken;
  }

  async function getAccessToken() {
    const now = Date.now();
    if (cachedAccessToken && now < cachedTokenExpiresAtMs - 60_000) {
      return cachedAccessToken;
    }
    const creds = resolveCredentialsOrNull();
    if (!creds) {
      if (useMetadataAdc) {
        return fetchCloudRunMetadataAccessToken(now);
      }
      throw new AppCheckRegistrarError(
        "Automatic App Check registration requires FIREBASE_SERVICE_ACCOUNT_JSON (with Firebase App Check Admin IAM role) set in the MYRAA backend environment. Never embed admin credentials inside the APK.",
        503,
        "SERVICE_ACCOUNT_NOT_CONFIGURED"
      );
    }

    const assertion = createServiceAccountJwtAssertion(creds, Math.floor(now / 1000));
    const tokenEndpoint = creds.tokenUri || GOOGLE_OAUTH_TOKEN_URL;
    const body = new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    });

    const resp = await fetchImpl(tokenEndpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: body.toString()
    });
    const raw = await resp.text();
    let parsed = {};
    try {
      parsed = JSON.parse(raw);
    } catch {
      parsed = {};
    }
    if (!resp.ok || !parsed.access_token) {
      const detail = parsed.error_description || parsed.error || `HTTP ${resp.status}`;
      throw new AppCheckRegistrarError(
        `Failed to obtain Google OAuth2 access token for Firebase App Check API: ${detail}`,
        502,
        "OAUTH_TOKEN_EXCHANGE_FAILED"
      );
    }

    const expiresInSec = Number(parsed.expires_in || 3600);
    cachedAccessToken = parsed.access_token;
    cachedTokenExpiresAtMs = now + expiresInSec * 1000;
    return cachedAccessToken;
  }

  async function postCreateDebugToken(accessToken, displayName, debugToken) {
    const endpoint = `${FIREBASE_APPCHECK_API_BASE}/projects/${projectNumber}/apps/${androidAppId}/debugTokens`;
    const resp = await fetchImpl(endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json; charset=utf-8"
      },
      body: JSON.stringify({
        displayName,
        token: debugToken
      })
    });
    const text = await resp.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = { rawText: text };
    }
    return { resp, data };
  }

  async function pruneOldestAutoDebugTokens(accessToken, maxPrune = 2) {
    const listUrl = `${FIREBASE_APPCHECK_API_BASE}/projects/${projectNumber}/apps/${androidAppId}/debugTokens?pageSize=50`;
    const listResp = await fetchImpl(listUrl, {
      method: "GET",
      headers: { authorization: `Bearer ${accessToken}` }
    });
    if (!listResp.ok) return 0;

    const listData = await listResp.json().catch(() => ({}));
    const tokens = Array.isArray(listData.debugTokens) ? listData.debugTokens : [];
    const autoCandidates = tokens.filter((t) =>
      String(t.displayName || "").startsWith(MYRAA_AUTO_TOKEN_PREFIX)
    );
    const ordered = autoCandidates.length > 0 ? autoCandidates : tokens;

    let deleted = 0;
    for (const candidate of ordered.slice(0, maxPrune)) {
      if (!candidate?.name) continue;
      const delUrl = `${FIREBASE_APPCHECK_API_BASE}/${String(candidate.name).replace(/^\/+/, "")}`;
      const delResp = await fetchImpl(delUrl, {
        method: "DELETE",
        headers: { authorization: `Bearer ${accessToken}` }
      });
      if (delResp.ok) {
        deleted += 1;
      }
    }
    return deleted;
  }

  async function registerDebugToken({
    authUser,
    debugToken,
    deviceModel = "",
    packageName = "",
    appId = ""
  }) {
    if (!authUser || !authUser.uid) {
      throw new AppCheckRegistrarError("Authenticated Firebase user is required.", 401, "UNAUTHENTICATED");
    }

    if (allowedUids.length > 0 || allowedEmails.length > 0) {
      const uidAllowed = allowedUids.includes(authUser.uid);
      const emailAllowed = authUser.email && allowedEmails.includes(String(authUser.email).toLowerCase());
      if (!uidAllowed && !emailAllowed) {
        throw new AppCheckRegistrarError(
          "Your signed-in account is not in APPCHECK_REGISTRAR_ALLOWED_UIDS / ALLOWED_EMAILS on the backend.",
          403,
          "ACCOUNT_NOT_ALLOWLISTED"
        );
      }
    }

    if (packageName && String(packageName).trim() !== androidPackage) {
      throw new AppCheckRegistrarError(
        `Denied cross-package App Check registration for '${packageName}'. Locked to '${androidPackage}'.`,
        403,
        "PACKAGE_MISMATCH"
      );
    }

    if (appId && String(appId).trim() !== androidAppId) {
      throw new AppCheckRegistrarError(
        `Denied cross-app registration for '${appId}'. Locked to '${androidAppId}'.`,
        403,
        "APP_ID_MISMATCH"
      );
    }

    const normalizedToken = String(debugToken || "").trim().toLowerCase();
    if (!UUID_REGEX.test(normalizedToken)) {
      throw new AppCheckRegistrarError(
        "debugToken must be a valid UUID (e.g. xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx).",
        400,
        "INVALID_DEBUG_TOKEN_FORMAT"
      );
    }

    const displayName = sanitizeDisplayName(deviceModel, authUser.uid);
    const accessToken = await getAccessToken();

    let { resp, data } = await postCreateDebugToken(accessToken, displayName, normalizedToken);

    if (resp.ok) {
      return {
        registered: true,
        alreadyRegistered: false,
        prunedCount: 0,
        projectId,
        projectNumber,
        androidPackage,
        androidAppId,
        tokenName: data.name || "",
        displayName: data.displayName || displayName,
        maskedToken: maskDebugToken(normalizedToken)
      };
    }

    const apiErrorMessage = String(data?.error?.message || data?.rawText || `HTTP ${resp.status}`);
    const apiStatus = String(data?.error?.status || "");

    // Treat duplicate registration as idempotent success
    if (
      resp.status === 409 ||
      apiStatus === "ALREADY_EXISTS" ||
      /already exists/i.test(apiErrorMessage)
    ) {
      return {
        registered: true,
        alreadyRegistered: true,
        prunedCount: 0,
        projectId,
        projectNumber,
        androidPackage,
        androidAppId,
        tokenName: data?.name || "",
        displayName,
        maskedToken: maskDebugToken(normalizedToken)
      };
    }

    // Handle Firebase 20-debug-token cap automatically by pruning oldest [MYRAA-AUTO] tokens
    const isQuotaLimit =
      resp.status === 429 ||
      apiStatus === "RESOURCE_EXHAUSTED" ||
      /maximum|limit|quota|too many debug tokens|20/i.test(apiErrorMessage);

    if (isQuotaLimit) {
      const prunedCount = await pruneOldestAutoDebugTokens(accessToken, 2);
      if (prunedCount > 0) {
        const retry = await postCreateDebugToken(accessToken, displayName, normalizedToken);
        if (retry.resp.ok) {
          return {
            registered: true,
            alreadyRegistered: false,
            prunedCount,
            projectId,
            projectNumber,
            androidPackage,
            androidAppId,
            tokenName: retry.data?.name || "",
            displayName: retry.data?.displayName || displayName,
            maskedToken: maskDebugToken(normalizedToken)
          };
        }
        const retryMsg = String(retry.data?.error?.message || `HTTP ${retry.resp.status}`);
        throw new AppCheckRegistrarError(
          `Firebase App Check debugTokens.create failed after pruning ${prunedCount} old token(s): ${retryMsg}`,
          retry.resp.status >= 400 && retry.resp.status < 500 ? retry.resp.status : 502,
          "FIREBASE_API_REJECTED_AFTER_PRUNE"
        );
      }
    }

    const mappedStatus = resp.status === 401 || resp.status === 403 ? 502 : resp.status >= 400 ? resp.status : 502;
    throw new AppCheckRegistrarError(
      `Firebase App Check API rejected automatic debug token registration (${resp.status}): ${apiErrorMessage}`,
      mappedStatus,
      "FIREBASE_API_REJECTED"
    );
  }

  return {
    getStatus,
    registerDebugToken
  };
}
