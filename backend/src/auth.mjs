import crypto from "node:crypto";

const GOOGLE_CERTS_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

let cachedCerts = null;
let certsExpiresAt = 0;

function decodeBase64UrlJson(segment) {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const pad = normalized.length % 4 === 0 ? "" : "=".repeat(4 - (normalized.length % 4));
  const raw = Buffer.from(normalized + pad, "base64").toString("utf8");
  return JSON.parse(raw);
}

async function getGooglePublicCerts(fetchImpl = fetch) {
  const now = Date.now();
  if (cachedCerts && now < certsExpiresAt) {
    return cachedCerts;
  }
  const resp = await fetchImpl(GOOGLE_CERTS_URL);
  if (!resp.ok) {
    throw new Error(`Failed to fetch Firebase public certs: HTTP ${resp.status}`);
  }
  const cacheHeader = resp.headers.get("cache-control") || "";
  const maxAgeMatch = cacheHeader.match(/max-age=(\d+)/i);
  const maxAgeSec = maxAgeMatch ? parseInt(maxAgeMatch[1], 10) : 3600;
  cachedCerts = await resp.json();
  certsExpiresAt = now + maxAgeSec * 1000;
  return cachedCerts;
}

export class AuthError extends Error {
  constructor(message, statusCode = 401, errorCode = "FIREBASE_AUTH_REQUIRED") {
    super(message);
    this.name = "AuthError";
    this.statusCode = statusCode;
    this.errorCode = errorCode;
  }
}

export function createFirebaseAuthVerifier(options = {}) {
  const projectId = options.firebaseProjectId || process.env.FIREBASE_PROJECT_ID || "gen-lang-client-0645829429";
  const allowEmulatorTokens = options.allowEmulatorTokens ?? (process.env.ALLOW_EMULATOR_TOKENS === "true");
  const requireAppCheck = options.requireAppCheck ?? (process.env.REQUIRE_APP_CHECK === "true");
  const fetchImpl = options.fetchImpl || fetch;

  return async function verifyRequestAuth(headers, requestOptions = {}) {
    const authHeader = headers?.authorization || headers?.Authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      throw new AuthError("Missing Bearer Firebase ID token.", 401, "FIREBASE_AUTH_REQUIRED");
    }

    const skipAppCheck = Boolean(requestOptions.skipAppCheck);
    if (requireAppCheck && !skipAppCheck) {
      const appCheckToken = String(
        headers?.["x-firebase-appcheck"] || headers?.["X-Firebase-AppCheck"] || ""
      ).trim();
      if (appCheckToken.length < 10) {
        throw new AuthError("Missing required Firebase App Check attestation header.", 401, "APP_CHECK_REQUIRED");
      }
      if (appCheckToken.startsWith("{") || appCheckToken.split(".").length !== 3) {
        throw new AuthError("Firebase App Check token is invalid.", 401, "APP_CHECK_REQUIRED");
      }
    }

    const token = authHeader.slice("Bearer ".length).trim();
    const parts = token.split(".");
    if (parts.length !== 3) {
      throw new AuthError("Malformed JWT token.", 401, "FIREBASE_AUTH_REQUIRED");
    }

    let header;
    let payload;
    try {
      header = decodeBase64UrlJson(parts[0]);
      payload = decodeBase64UrlJson(parts[1]);
    } catch {
      throw new AuthError("Invalid JWT encoding.", 401, "FIREBASE_AUTH_REQUIRED");
    }

    const nowSec = Math.floor(Date.now() / 1000);
    if (typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > 128) {
      throw new AuthError("Invalid subject claim.", 401, "FIREBASE_AUTH_REQUIRED");
    }
    if (typeof payload.exp !== "number" || payload.exp <= nowSec) {
      throw new AuthError("Firebase ID token has expired.", 401, "FIREBASE_AUTH_REQUIRED");
    }

    // Emulator mode (only enabled explicitly in local test harnesses)
    if (allowEmulatorTokens && header.alg === "none") {
      return {
        uid: payload.user_id || payload.sub,
        email: payload.email || "",
        isEmulator: true
      };
    }

    if (header.alg !== "RS256" || typeof header.kid !== "string") {
      throw new AuthError("Unsupported token algorithm or missing key ID.", 401, "FIREBASE_AUTH_REQUIRED");
    }
    if (payload.aud !== projectId) {
      throw new AuthError(`Token audience '${payload.aud}' does not match project '${projectId}'.`, 401, "FIREBASE_AUTH_REQUIRED");
    }
    if (payload.iss !== `https://securetoken.google.com/${projectId}`) {
      throw new AuthError("Token issuer mismatch.", 401, "FIREBASE_AUTH_REQUIRED");
    }

    const certs = await getGooglePublicCerts(fetchImpl);
    const certPem = certs[header.kid];
    if (!certPem) {
      throw new AuthError("Unknown Firebase signing key ID.", 401);
    }

    const signedInput = `${parts[0]}.${parts[1]}`;
    const signature = Buffer.from(
      parts[2].replace(/-/g, "+").replace(/_/g, "/"),
      "base64"
    );
    const verifier = crypto.createVerify("RSA-SHA256");
    verifier.update(signedInput);
    verifier.end();

    if (!verifier.verify(certPem, signature)) {
      throw new AuthError("Firebase ID token signature verification failed.", 401);
    }

    return {
      uid: payload.user_id || payload.sub,
      email: payload.email || "",
      isEmulator: false
    };
  };
}
