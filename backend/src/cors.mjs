/**
 * Strict origin-allowlist CORS helper for MYRAA Web -> Render Backend requests.
 * Never returns wildcard '*' for Access-Control-Allow-Origin.
 * Non-browser clients without an Origin header (Android OkHttp, curl, tests)
 * pass through unaffected.
 */

const DEFAULT_DEV_ORIGINS = [
  "http://localhost:5173",
  "http://127.0.0.1:5173",
  "http://localhost:4173",
  "http://127.0.0.1:4173"
];

export function normalizeOrigin(rawOrigin) {
  const raw = String(rawOrigin || "").trim();
  if (!raw) return "";
  try {
    const parsed = new URL(raw);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return "";
    return `${parsed.protocol}//${parsed.host}`.toLowerCase();
  } catch {
    return "";
  }
}

export function resolveAllowedOrigins(options = {}) {
  const env = options.env || process.env;
  const configuredRaw = options.allowedOrigins ?? env.ALLOWED_WEB_ORIGINS ?? "";
  const list = Array.isArray(configuredRaw)
    ? configuredRaw
    : String(configuredRaw)
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);

  const allowed = new Set();
  for (const item of list) {
    const normalized = normalizeOrigin(item);
    if (normalized) allowed.add(normalized);
  }

  const isProd = String(env.NODE_ENV || "").toLowerCase() === "production";
  const allowLocalDev =
    options.allowLocalDev ??
    (env.ALLOW_LOCAL_DEV_CORS === "true" || (!isProd && env.ALLOW_LOCAL_DEV_CORS !== "false"));

  if (allowLocalDev) {
    for (const devOrigin of DEFAULT_DEV_ORIGINS) {
      allowed.add(devOrigin);
    }
  }

  return allowed;
}

export function createCorsHandler(options = {}) {
  const allowedOrigins = resolveAllowedOrigins(options);

  return {
    allowedOrigins,
    isOriginAllowed(originHeader) {
      const normalized = normalizeOrigin(originHeader);
      return Boolean(normalized && allowedOrigins.has(normalized));
    },
    handle(req, res) {
      const originHeader = req.headers?.origin || req.headers?.Origin || "";
      if (!originHeader) {
        return { isPreflight: req.method === "OPTIONS", originPresent: false, allowed: true };
      }

      const normalized = normalizeOrigin(originHeader);
      const allowed = Boolean(normalized && allowedOrigins.has(normalized));

      res.setHeader("Vary", "Origin");
      if (allowed) {
        res.setHeader("Access-Control-Allow-Origin", normalized);
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        res.setHeader(
          "Access-Control-Allow-Headers",
          "Authorization, Content-Type, X-Firebase-AppCheck"
        );
        res.setHeader("Access-Control-Max-Age", "86400");
      }

      if (req.method === "OPTIONS") {
        if (allowed) {
          res.writeHead(204);
          res.end();
        } else {
          const body = JSON.stringify({
            error: `Origin '${originHeader}' is not permitted by backend CORS policy.`,
            errorCode: "CORS_ORIGIN_DENIED"
          });
          res.writeHead(403, {
            "content-type": "application/json; charset=utf-8",
            "content-length": Buffer.byteLength(body)
          });
          res.end(body);
        }
        return { isPreflight: true, originPresent: true, allowed };
      }

      return { isPreflight: false, originPresent: true, allowed };
    }
  };
}
