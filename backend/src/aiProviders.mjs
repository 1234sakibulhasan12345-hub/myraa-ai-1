import {
  createProviderKeyVault,
  maskSecretHint,
  normalizeCredentialInput,
  VaultError
} from "./providerKeyVault.mjs";

export class ProviderCallError extends Error {
  constructor(message, statusCode = 502, errorCode = "PROVIDER_ERROR", errorCategory = "PROVIDER_UNAVAILABLE") {
    super(message);
    this.name = "ProviderCallError";
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.errorCategory = errorCategory;
  }
}

export const PROVIDER_CONFIGS = {
  // === VERIFIED FREE-TIER / FREE-ACCESS PROVIDERS ===
  groq: {
    providerId: "groq",
    displayName: "Groq (Free Tier)",
    freeTier: true,
    apiStyle: "openai",
    envKey: "GROQ_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.groq.com/openai/v1",
    defaultModel: "llama-3.3-70b-versatile",
    availableModels: [
      "llama-3.3-70b-versatile",
      "llama-3.1-8b-instant",
      "openai/gpt-oss-120b",
      "openai/gpt-oss-20b"
    ]
  },
  openrouter_free: {
    providerId: "openrouter_free",
    displayName: "OpenRouter Free Models",
    freeTier: true,
    apiStyle: "openrouter",
    envKey: "OPENROUTER_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://openrouter.ai/api/v1",
    defaultModel: "openrouter/free",
    availableModels: [
      "openrouter/free",
      "meta-llama/llama-3.3-70b-instruct:free",
      "qwen/qwen3-coder:free",
      "deepseek/deepseek-r1-0528:free"
    ]
  },
  mistral: {
    providerId: "mistral",
    displayName: "Mistral AI (Free Experiment Tier)",
    freeTier: true,
    apiStyle: "openai",
    envKey: "MISTRAL_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.mistral.ai/v1",
    defaultModel: "mistral-small-latest",
    availableModels: [
      "mistral-small-latest",
      "codestral-latest",
      "ministral-8b-latest",
      "open-mistral-nemo"
    ]
  },
  cohere: {
    providerId: "cohere",
    displayName: "Cohere (Free Trial Tier)",
    freeTier: true,
    apiStyle: "cohere_v2",
    envKey: "COHERE_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.cohere.com",
    defaultModel: "command-a-03-2025",
    availableModels: [
      "command-a-03-2025",
      "command-r-plus-08-2024",
      "command-r7b-12-2024"
    ]
  },
  cloudflare_ai: {
    providerId: "cloudflare_ai",
    displayName: "Cloudflare Workers AI (10K Neurons/Day Free)",
    freeTier: true,
    apiStyle: "cloudflare_openai",
    envKey: "CLOUDFLARE_API_TOKEN",
    requiresAccountId: true,
    accountIdEnvKey: "CLOUDFLARE_ACCOUNT_ID",
    baseUrl: "https://api.cloudflare.com/client/v4/accounts",
    defaultModel: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    availableModels: [
      "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
      "@cf/meta/llama-3.1-8b-instruct",
      "@cf/meta/llama-4-scout-17b-16e-instruct"
    ]
  },
  nvidia_nim: {
    providerId: "nvidia_nim",
    displayName: "NVIDIA NIM (Free Developer Credits)",
    freeTier: true,
    apiStyle: "openai",
    envKey: "NVIDIA_NIM_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://integrate.api.nvidia.com/v1",
    defaultModel: "deepseek-ai/deepseek-v4.1-flash",
    availableModels: [
      "deepseek-ai/deepseek-v4.1-flash",
      "google/gemma-3-12b-it",
      "google/gemma-4-31b-it",
      "mistralai/mistral-large-4-1t-a52b"
    ]
  },
  sambanova: {
    providerId: "sambanova",
    displayName: "SambaNova Cloud (Free Developer Tier)",
    freeTier: true,
    apiStyle: "openai",
    envKey: "SAMBANOVA_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.sambanova.ai/v1",
    defaultModel: "Meta-Llama-3.3-70B-Instruct",
    availableModels: [
      "Meta-Llama-3.3-70B-Instruct",
      "GLM-5.3",
      "gpt-oss-120b",
      "gemma-4-31B-it"
    ]
  },

  // === LEGACY PART 02 PAID PROXY PROVIDERS (Preserved for backwards compatibility; NOT shown as Free) ===
  openai_gpt: {
    providerId: "openai_gpt",
    displayName: "OpenAI GPT",
    freeTier: false,
    apiStyle: "openai",
    envKey: "OPENAI_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.openai.com/v1",
    defaultModel: "gpt-4.1-mini",
    availableModels: ["gpt-4.1", "gpt-4.1-mini", "o4-mini"]
  },
  anthropic_claude: {
    providerId: "anthropic_claude",
    displayName: "Anthropic Claude",
    freeTier: false,
    apiStyle: "anthropic",
    envKey: "ANTHROPIC_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.anthropic.com",
    defaultModel: "claude-sonnet-4-20250514",
    availableModels: ["claude-sonnet-4-20250514", "claude-3-5-haiku-latest"]
  },
  deepseek: {
    providerId: "deepseek",
    displayName: "DeepSeek",
    freeTier: false,
    apiStyle: "openai",
    envKey: "DEEPSEEK_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.deepseek.com",
    defaultModel: "deepseek-chat",
    availableModels: ["deepseek-chat", "deepseek-reasoner"]
  },
  meta_llama: {
    providerId: "meta_llama",
    displayName: "Meta Llama",
    freeTier: false,
    apiStyle: "openai",
    envKey: "META_LLAMA_API_KEY",
    requiresAccountId: false,
    accountIdEnvKey: "",
    baseUrl: "https://api.llama.com/compat/v1",
    defaultModel: "Llama-3.3-70B-Instruct",
    availableModels: ["Llama-3.3-70B-Instruct", "Llama-4-Scout-17B-16E-Instruct"]
  }
};

function isExplicitInvalidKeyText(lowerMsg) {
  return (
    lowerMsg.includes("invalid api key") ||
    lowerMsg.includes("invalid_api_key") ||
    lowerMsg.includes("incorrect api key") ||
    lowerMsg.includes("api key not valid") ||
    lowerMsg.includes("api_key_invalid") ||
    lowerMsg.includes("authorization failed") ||
    lowerMsg.includes("authentication failed") ||
    lowerMsg.includes("invalid authentication credentials") ||
    lowerMsg.includes("missing authentication header") ||
    lowerMsg.includes("user not found") ||
    lowerMsg.includes("invalid token") ||
    lowerMsg.includes("expired api key") ||
    lowerMsg.includes("revoked")
  );
}

function isCloudflareWafOrHtmlBlock(rawBody, lowerMsg) {
  const rawLower = String(rawBody || "").toLowerCase();
  return (
    rawLower.includes("<!doctype html") ||
    rawLower.includes("<html") ||
    lowerMsg.includes("just a moment") ||
    lowerMsg.includes("attention required") ||
    lowerMsg.includes("error code: 1010") ||
    lowerMsg.includes("cf-ray") ||
    (lowerMsg.includes("cloudflare") && !lowerMsg.includes("workers ai"))
  );
}

export function classifyUpstreamFailure(httpStatus, errorBodyMessage, err = null, context = {}) {
  if (err && !httpStatus) {
    const msg = String(err.message || err || "");
    const lower = msg.toLowerCase();
    const errCode = String(err.code || err?.cause?.code || "").toUpperCase();

    if (
      errCode === "ERR_INVALID_URL" ||
      errCode === "ERR_INVALID_CHAR" ||
      lower.includes("invalid header value") ||
      lower.includes("invalid url")
    ) {
      return {
        statusCode: "CONFIGURATION_ERROR",
        errorCategory: "CONFIGURATION_ERROR",
        httpStatus: 400,
        message: `Configuration error constructing provider request: ${msg}`
      };
    }

    if (
      err.name === "AbortError" ||
      errCode.startsWith("UND_ERR_") ||
      ["ECONNREFUSED", "ENOTFOUND", "EAI_AGAIN", "ECONNRESET", "ETIMEDOUT", "EHOSTUNREACH", "EPIPE"].includes(errCode) ||
      lower.includes("timeout") ||
      lower.includes("aborted") ||
      lower.includes("econnrefused") ||
      lower.includes("enotfound") ||
      lower.includes("econnreset") ||
      lower.includes("etimedout") ||
      lower.includes("fetch failed") ||
      lower.includes("network") ||
      lower.includes("socket")
    ) {
      return {
        statusCode: "OFFLINE",
        errorCategory: "NETWORK_ERROR",
        httpStatus: 503,
        message: `Network error reaching provider endpoint: ${msg}`
      };
    }

    return {
      statusCode: "UNAVAILABLE",
      errorCategory: "PROVIDER_UNAVAILABLE",
      httpStatus: 502,
      message: msg || "Provider request failed."
    };
  }

  const msg = String(errorBodyMessage || "").trim();
  const lower = msg.toLowerCase();
  const rawBody = String(context.rawBody || "");
  const providerId = String(context.providerId || "");
  const cfCode = Number(context.cloudflareErrorCode || 0);

  if (
    httpStatus === 429 ||
    lower.includes("rate limit") ||
    lower.includes("rate_limit") ||
    lower.includes("too many requests")
  ) {
    return {
      statusCode: "RATE_LIMITED",
      errorCategory: "RATE_LIMITED",
      httpStatus: 429,
      message: msg || "Provider rate limit reached (HTTP 429). Ready for auto-fallback."
    };
  }

  if (
    httpStatus === 402 ||
    lower.includes("insufficient_quota") ||
    lower.includes("quota exceeded") ||
    lower.includes("payment required") ||
    (lower.includes("credit") && (lower.includes("exhaust") || lower.includes("insufficient") || lower.includes("balance"))) ||
    (lower.includes("neuron") && lower.includes("limit"))
  ) {
    return {
      statusCode: "QUOTA_EXPIRED",
      errorCategory: "RATE_LIMITED",
      httpStatus: httpStatus || 402,
      message: msg || `Free tier quota or credits exhausted (HTTP ${httpStatus}).`
    };
  }

  if (httpStatus === 401) {
    return {
      statusCode: "INVALID_API_KEY",
      errorCategory: "INVALID_KEY",
      httpStatus: 401,
      message: msg || "API key rejected by provider (HTTP 401). Check key in Settings."
    };
  }

  if (httpStatus === 403) {
    if (isCloudflareWafOrHtmlBlock(rawBody, lower)) {
      return {
        statusCode: "UNAVAILABLE",
        errorCategory: "PROVIDER_UNAVAILABLE",
        httpStatus: 503,
        message: "Upstream CDN/WAF blocked verification request (HTTP 403). Your key may still be valid; retry shortly."
      };
    }
    if (isExplicitInvalidKeyText(lower) || lower === "forbidden") {
      return {
        statusCode: "INVALID_API_KEY",
        errorCategory: "INVALID_KEY",
        httpStatus: 403,
        message: msg || "API key rejected by provider (HTTP 403). Check key permissions or value."
      };
    }
    return {
      statusCode: "CONFIGURATION_ERROR",
      errorCategory: "CONFIGURATION_ERROR",
      httpStatus: 403,
      message: msg || "Provider denied access (HTTP 403). Verify API token scopes, model access, or account permissions."
    };
  }

  if (httpStatus === 400 || httpStatus === 404 || httpStatus === 410 || httpStatus === 422) {
    if (
      (providerId === "cloudflare_ai" && (cfCode === 9106 || cfCode === 10000 || cfCode === 9103)) ||
      isExplicitInvalidKeyText(lower)
    ) {
      const cfHint = providerId === "cloudflare_ai"
        ? " Verify both CLOUDFLARE_API_TOKEN and your 32-character CLOUDFLARE_ACCOUNT_ID."
        : "";
      return {
        statusCode: "INVALID_API_KEY",
        errorCategory: "INVALID_KEY",
        httpStatus,
        message: (msg || `Authentication rejected (HTTP ${httpStatus}).`) + cfHint
      };
    }
    return {
      statusCode: "CONFIGURATION_ERROR",
      errorCategory: "CONFIGURATION_ERROR",
      httpStatus,
      message: msg || `Provider endpoint or model configuration error (HTTP ${httpStatus}).`
    };
  }

  if (httpStatus >= 500) {
    return {
      statusCode: "UNAVAILABLE",
      errorCategory: "PROVIDER_UNAVAILABLE",
      httpStatus,
      message: msg || `Provider service temporarily unavailable (HTTP ${httpStatus}).`
    };
  }

  return {
    statusCode: "PROBE_FAILED",
    errorCategory: "PROVIDER_UNAVAILABLE",
    httpStatus: httpStatus || 502,
    message: msg || `Upstream API returned HTTP ${httpStatus}.`
  };
}

function extractErrorDetails(rawBody) {
  const raw = String(rawBody || "");
  let parsed = {};
  try {
    parsed = raw ? JSON.parse(raw) : {};
  } catch {
    parsed = {};
  }
  const message =
    parsed?.error?.message ||
    parsed?.detail ||
    parsed?.message ||
    parsed?.errors?.[0]?.message ||
    (raw && !raw.trimStart().startsWith("<") ? raw.slice(0, 220).trim() : "");
  const cloudflareErrorCode = Number(parsed?.errors?.[0]?.code || 0) || 0;
  return { parsed, message, cloudflareErrorCode, rawBody: raw };
}

export function createAiProviderRouter(options = {}) {
  const env = options.env || process.env;
  const fetchImpl = options.fetchImpl || fetch;
  const keyVault = options.keyVault || createProviderKeyVault({ env });
  const probeTimeoutMs = Number(options.probeTimeoutMs || 12_000);

  function resolveCredentials(providerId, userId = "") {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) return { apiKey: "", accountId: "", source: "NONE", maskedHint: "", vaultError: null };

    let vaultCreds = null;
    try {
      vaultCreds = userId ? keyVault.getProviderCredentials(userId, providerId) : null;
    } catch (err) {
      if (err instanceof VaultError) {
        return {
          apiKey: "",
          accountId: "",
          source: "VAULT_ERROR",
          maskedHint: "",
          vaultError: err.message
        };
      }
      throw err;
    }

    const envApiKey = normalizeCredentialInput(env[cfg.envKey] || "", cfg.envKey);
    const envAccountId = cfg.accountIdEnvKey
      ? normalizeCredentialInput(env[cfg.accountIdEnvKey] || "", cfg.accountIdEnvKey)
      : "";

    const apiKey = normalizeCredentialInput(vaultCreds?.apiKey || envApiKey || "", cfg.envKey);
    const accountId = normalizeCredentialInput(vaultCreds?.accountId || envAccountId || "", cfg.accountIdEnvKey);
    const source = vaultCreds?.apiKey ? "USER_VAULT" : envApiKey ? "SERVER_ENV" : "NONE";
    const maskedHint = apiKey ? (vaultCreds?.maskedHint || maskSecretHint(apiKey)) : "";

    return { apiKey, accountId, source, maskedHint, vaultError: null };
  }

  function resolveChatEndpoint(cfg, accountId) {
    if (cfg.providerId === "meta_llama") {
      const base = (env.META_LLAMA_BASE_URL || cfg.baseUrl).replace(/\/+$/, "");
      return `${base}/chat/completions`;
    }
    if (cfg.apiStyle === "cloudflare_openai") {
      return `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/ai/v1/chat/completions`;
    }
    if (cfg.apiStyle === "cohere_v2") {
      return `${cfg.baseUrl}/v2/chat`;
    }
    if (cfg.apiStyle === "anthropic") {
      return `${cfg.baseUrl}/v1/messages`;
    }
    return `${cfg.baseUrl.replace(/\/+$/, "")}/chat/completions`;
  }

  function buildAuthHeaders(cfg, apiKey) {
    const cleanKey = normalizeCredentialInput(apiKey, cfg.envKey);
    const common = {
      Accept: "application/json",
      "User-Agent": "MYRAA-SuperBrain-Backend/1.0"
    };
    if (cfg.apiStyle === "anthropic") {
      return {
        ...common,
        "x-api-key": cleanKey,
        "anthropic-version": "2023-06-01"
      };
    }
    const headers = {
      ...common,
      Authorization: `Bearer ${cleanKey}`
    };
    if (cfg.apiStyle === "openrouter") {
      headers["HTTP-Referer"] = "https://myraa-super-brain.app";
      headers["X-Title"] = "MYRAA AI SUPER BRAIN";
    }
    return headers;
  }

  async function probeViaMinimalInference(cfg, creds, candidateModel, controller) {
    const endpoint = resolveChatEndpoint(cfg, creds.accountId);
    let bodyObj;
    if (cfg.apiStyle === "anthropic") {
      bodyObj = {
        model: candidateModel,
        max_tokens: 1,
        messages: [{ role: "user", content: "ping" }]
      };
    } else {
      bodyObj = {
        model: candidateModel,
        max_tokens: 1,
        messages: [{ role: "user", content: "ping" }]
      };
    }

    const resp = await fetchImpl(endpoint, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...buildAuthHeaders(cfg, creds.apiKey)
      },
      body: JSON.stringify(bodyObj),
      signal: controller.signal
    });
    const raw = await resp.text().catch(() => "");
    if (!resp.ok) {
      const details = extractErrorDetails(raw);
      const classified = classifyUpstreamFailure(resp.status, details.message, null, {
        rawBody: details.rawBody,
        providerId: cfg.providerId,
        cloudflareErrorCode: details.cloudflareErrorCode
      });
      return {
        ok: false,
        httpStatus: resp.status,
        statusCode: classified.statusCode,
        errorCategory: classified.errorCategory,
        message: classified.message
      };
    }
    return {
      ok: true,
      httpStatus: resp.status,
      statusCode: "CONNECTED_LIVE",
      errorCategory: "NONE",
      message: "Live upstream inference authentication verified."
    };
  }

  function detectKeyPrefixMismatch(cfg, apiKey) {
    const key = String(apiKey || "").trim();
    if (!key) return null;
    if (key.startsWith("sk-or-") && cfg.providerId !== "openrouter_free") {
      return `Wrong provider field: keys starting with 'sk-or-' belong to OpenRouter Free Models, not ${cfg.displayName}. Save this key under OpenRouter Free Models, or enter a ${cfg.envKey} (for Groq, keys start with 'gsk_').`;
    }
    if (key.startsWith("gsk_") && cfg.providerId !== "groq") {
      return `Wrong provider field: keys starting with 'gsk_' belong to Groq (Free Tier), not ${cfg.displayName}. Save this key under Groq.`;
    }
    if (key.startsWith("nvapi-") && cfg.providerId !== "nvidia_nim") {
      return `Wrong provider field: keys starting with 'nvapi-' belong to NVIDIA NIM, not ${cfg.displayName}. Save this key under NVIDIA NIM.`;
    }
    if (key.startsWith("sk-ant-") && cfg.providerId !== "anthropic_claude") {
      return `Wrong provider field: keys starting with 'sk-ant-' belong to Anthropic Claude, not ${cfg.displayName}.`;
    }
    return null;
  }

  function validateCredentialsConfig(cfg, creds) {
    if (creds.accountId && (/[\s/\\?#]/.test(creds.accountId) || creds.accountId.includes("://"))) {
      return {
        statusCode: "CONFIGURATION_ERROR",
        errorCategory: "CONFIGURATION_ERROR",
        message: `Invalid ${cfg.accountIdEnvKey || "Account ID"} format: paste only the ID without URLs or spaces.`
      };
    }
    if (creds.apiKey && /\s/.test(creds.apiKey)) {
      return {
        statusCode: "CONFIGURATION_ERROR",
        errorCategory: "CONFIGURATION_ERROR",
        message: `${cfg.envKey} contains unexpected spaces or line breaks.`
      };
    }
    const prefixMismatch = detectKeyPrefixMismatch(cfg, creds.apiKey);
    if (prefixMismatch) {
      return {
        statusCode: "CONFIGURATION_ERROR",
        errorCategory: "CONFIGURATION_ERROR",
        message: prefixMismatch
      };
    }
    return null;
  }

  async function probeProviderEndpoint(cfg, creds) {
    const configProblem = validateCredentialsConfig(cfg, creds);
    if (configProblem) {
      return {
        ok: false,
        statusCode: configProblem.statusCode,
        errorCategory: configProblem.errorCategory,
        message: configProblem.message,
        discoveredModels: cfg.availableModels
      };
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), probeTimeoutMs);
    try {
      const { apiKey, accountId } = creds;
      let probeUrl;
      if (cfg.apiStyle === "openrouter") {
        // Validate the API key itself via /auth/key (public /models does not validate auth)
        probeUrl = "https://openrouter.ai/api/v1/auth/key";
      } else if (cfg.apiStyle === "cloudflare_openai") {
        probeUrl = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/ai/models/search?per_page=5`;
      } else if (cfg.apiStyle === "cohere_v2") {
        probeUrl = "https://api.cohere.com/v1/models";
      } else if (cfg.apiStyle === "anthropic") {
        probeUrl = "https://api.anthropic.com/v1/models";
      } else if (cfg.providerId === "meta_llama") {
        const base = (env.META_LLAMA_BASE_URL || cfg.baseUrl).replace(/\/+$/, "");
        probeUrl = `${base}/models`;
      } else {
        probeUrl = `${cfg.baseUrl.replace(/\/+$/, "")}/models`;
      }

      const resp = await fetchImpl(probeUrl, {
        method: "GET",
        headers: buildAuthHeaders(cfg, apiKey),
        signal: controller.signal
      });
      const raw = await resp.text().catch(() => "");
      const details = extractErrorDetails(raw);
      const parsed = details.parsed;

      if (!resp.ok) {
        const classified = classifyUpstreamFailure(resp.status, details.message, null, {
          rawBody: details.rawBody,
          providerId: cfg.providerId,
          cloudflareErrorCode: details.cloudflareErrorCode
        });

        // Fallback inference probe ONLY when /models returned non-invalid-key 403/404/405
        // (e.g. Cloudflare token with Workers AI: Edit without Read, or scoped org token).
        const shouldTryInferenceFallback =
          cfg.apiStyle !== "openrouter" &&
          (resp.status === 403 || resp.status === 404 || resp.status === 405) &&
          classified.statusCode !== "INVALID_API_KEY";

        if (shouldTryInferenceFallback) {
          const fallbackProbe = await probeViaMinimalInference(cfg, creds, cfg.defaultModel, controller);
          if (fallbackProbe.ok) {
            return {
              ok: true,
              statusCode: "CONNECTED_LIVE",
              errorCategory: "NONE",
              message: "Live upstream inference authentication verified.",
              discoveredModels: cfg.availableModels
            };
          }
          if (fallbackProbe.statusCode !== "CONFIGURATION_ERROR") {
            return {
              ok: false,
              statusCode: fallbackProbe.statusCode,
              errorCategory: fallbackProbe.errorCategory,
              message: fallbackProbe.message,
              discoveredModels: cfg.availableModels
            };
          }
        }

        return {
          ok: false,
          statusCode: classified.statusCode,
          errorCategory: classified.errorCategory,
          message: classified.message,
          discoveredModels: cfg.availableModels
        };
      }

      let discoveredModels = cfg.availableModels;

      // NVIDIA NIM & SambaNova /models endpoints are public (return HTTP 200 even with an invalid Bearer token).
      // Verify actual authentication via minimal /chat/completions request.
      if (cfg.providerId === "nvidia_nim" || cfg.providerId === "sambanova") {
        const remoteIds = (Array.isArray(parsed?.data) ? parsed.data : [])
          .map((m) => String(m?.id || "").trim())
          .filter(Boolean);
        const preferred = remoteIds.find((id) => cfg.availableModels.includes(id)) || cfg.defaultModel;
        const authCheck = await probeViaMinimalInference(cfg, creds, preferred, controller);
        if (!authCheck.ok) {
          return {
            ok: false,
            statusCode: authCheck.statusCode,
            errorCategory: authCheck.errorCategory,
            message: authCheck.message,
            discoveredModels: cfg.availableModels
          };
        }
        return {
          ok: true,
          statusCode: "CONNECTED_LIVE",
          errorCategory: "NONE",
          message: "Live upstream authentication verified.",
          discoveredModels: cfg.availableModels
        };
      }

      if (cfg.providerId === "groq" && Array.isArray(parsed?.data) && parsed.data.length > 0) {
        const groqIds = parsed.data
          .map((m) => String(m?.id || "").trim())
          .filter((id) => id && !id.includes("whisper") && !id.includes("guard") && !id.includes("tts"));
        if (groqIds.length > 0) {
          const prioritized = cfg.availableModels.filter((m) => groqIds.includes(m));
          const extras = groqIds.filter((m) => !prioritized.includes(m)).slice(0, 4);
          discoveredModels = [...prioritized, ...extras];
          if (discoveredModels.length === 0) discoveredModels = cfg.availableModels;
        }
      }

      if (cfg.apiStyle === "openrouter") {
        // Check if OpenRouter account hit negative balance or zero free quota
        const limitRemaining = parsed?.data?.limit_remaining;
        if (typeof limitRemaining === "number" && limitRemaining < 0) {
          return {
            ok: false,
            statusCode: "QUOTA_EXPIRED",
            errorCategory: "RATE_LIMITED",
            message: "OpenRouter account balance is negative; free routing is blocked until resolved.",
            discoveredModels
          };
        }
        // Optionally discover live :free models on OpenRouter
        try {
          const modelsResp = await fetchImpl("https://openrouter.ai/api/v1/models", {
            headers: {
              Accept: "application/json",
              "User-Agent": "MYRAA-SuperBrain-Backend/1.0"
            },
            signal: controller.signal
          });
          if (modelsResp.ok) {
            const modelsJson = await modelsResp.json();
            const freeIds = (Array.isArray(modelsJson?.data) ? modelsJson.data : [])
              .filter((m) => {
                const id = String(m?.id || "");
                const pPrompt = String(m?.pricing?.prompt ?? "");
                const pComp = String(m?.pricing?.completion ?? "");
                return id.endsWith(":free") || (pPrompt === "0" && pComp === "0");
              })
              .map((m) => String(m.id))
              .slice(0, 6);
            if (freeIds.length > 0) {
              discoveredModels = Array.from(new Set(["openrouter/free", ...freeIds]));
            }
          }
        } catch {
          // Keep default verified :free roster if catalog discovery fails
        }
      }

      return {
        ok: true,
        statusCode: "CONNECTED_LIVE",
        errorCategory: "NONE",
        message: "Live upstream free-tier handshake verified.",
        discoveredModels
      };
    } catch (err) {
      const classified = classifyUpstreamFailure(0, "", err, { providerId: cfg.providerId });
      return {
        ok: false,
        statusCode: classified.statusCode,
        errorCategory: classified.errorCategory,
        message: classified.message,
        discoveredModels: cfg.availableModels
      };
    } finally {
      clearTimeout(timeout);
    }
  }

  async function probeCandidateCredentials(providerId, { apiKey, accountId = "" } = {}) {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) {
      throw new ProviderCallError(`Unknown provider '${providerId}'.`, 400, "UNKNOWN_PROVIDER", "CONFIGURATION_ERROR");
    }
    const cleanKey = normalizeCredentialInput(apiKey, cfg.envKey);
    const cleanAccountId = normalizeCredentialInput(accountId, cfg.accountIdEnvKey);
    if (cleanKey.length < 8 || cleanKey.length > 512 || /\s/.test(cleanKey)) {
      throw new ProviderCallError(
        `API key format for ${cfg.displayName} is invalid (must be 8-512 non-whitespace characters).`,
        400,
        "INVALID_KEY_FORMAT",
        "CONFIGURATION_ERROR"
      );
    }
    if (cfg.requiresAccountId && !cleanAccountId) {
      throw new ProviderCallError(
        "Cloudflare Account ID is required along with CLOUDFLARE_API_TOKEN.",
        400,
        "ACCOUNT_ID_REQUIRED",
        "CONFIGURATION_ERROR"
      );
    }
    const maskedHint = maskSecretHint(cleanKey);
    const startedMs = Date.now();
    const probeResult = await probeProviderEndpoint(cfg, {
      apiKey: cleanKey,
      accountId: cleanAccountId,
      source: "USER_VAULT",
      maskedHint
    });
    const checkedAt = Date.now();
    return {
      providerId: cfg.providerId,
      displayName: cfg.displayName,
      freeTier: Boolean(cfg.freeTier),
      connected: probeResult.ok,
      statusCode: probeResult.statusCode,
      errorCategory: probeResult.errorCategory || (probeResult.ok ? "NONE" : "PROVIDER_UNAVAILABLE"),
      keyConfigured: probeResult.ok || !["INVALID_API_KEY", "CONFIGURATION_ERROR"].includes(probeResult.statusCode),
      keySource: "USER_VAULT",
      maskedKeyHint: maskedHint,
      requiresAccountId: Boolean(cfg.requiresAccountId),
      hasAccountId: Boolean(cleanAccountId),
      latencyMs: Math.max(1, checkedAt - startedMs),
      lastCheckedEpochMs: checkedAt,
      message: probeResult.message,
      models: probeResult.discoveredModels
    };
  }

  async function probeSingleProvider(providerId, { liveProbe = true, userId = "" } = {}) {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) {
      throw new ProviderCallError(`Unknown provider '${providerId}'.`, 400, "UNKNOWN_PROVIDER", "CONFIGURATION_ERROR");
    }

    const creds = resolveCredentials(cfg.providerId, userId);
    if (creds.vaultError) {
      return {
        providerId: cfg.providerId,
        displayName: cfg.displayName,
        freeTier: Boolean(cfg.freeTier),
        connected: false,
        statusCode: "CONFIGURATION_ERROR",
        errorCategory: "CONFIGURATION_ERROR",
        keyConfigured: false,
        keySource: "VAULT_ERROR",
        maskedKeyHint: "",
        requiresAccountId: Boolean(cfg.requiresAccountId),
        hasAccountId: false,
        lastCheckedEpochMs: Date.now(),
        message: creds.vaultError,
        models: cfg.availableModels
      };
    }

    const missingAccount = cfg.requiresAccountId && !creds.accountId;

    if (!creds.apiKey || missingAccount) {
      const missingField = !creds.apiKey ? cfg.envKey : cfg.accountIdEnvKey;
      return {
        providerId: cfg.providerId,
        displayName: cfg.displayName,
        freeTier: Boolean(cfg.freeTier),
        connected: false,
        statusCode: "MISSING_API_KEY",
        errorCategory: "MISSING_API_KEY",
        keyConfigured: false,
        keySource: creds.source,
        maskedKeyHint: creds.maskedHint,
        requiresAccountId: Boolean(cfg.requiresAccountId),
        hasAccountId: Boolean(creds.accountId),
        lastCheckedEpochMs: Date.now(),
        message: `Missing ${missingField}. Save your key in Backend & API Settings.`,
        models: cfg.availableModels
      };
    }

    if (!liveProbe) {
      return {
        providerId: cfg.providerId,
        displayName: cfg.displayName,
        freeTier: Boolean(cfg.freeTier),
        connected: true,
        statusCode: "CONFIGURED_READY",
        errorCategory: "NONE",
        keyConfigured: true,
        keySource: creds.source,
        maskedKeyHint: creds.maskedHint,
        requiresAccountId: Boolean(cfg.requiresAccountId),
        hasAccountId: Boolean(creds.accountId),
        lastCheckedEpochMs: Date.now(),
        message: `${cfg.envKey} loaded securely (${creds.source === "USER_VAULT" ? "Encrypted Vault" : "Server Env"}).`,
        models: cfg.availableModels
      };
    }

    const startedMs = Date.now();
    const probeResult = await probeProviderEndpoint(cfg, creds);
    const checkedAt = Date.now();
    return {
      providerId: cfg.providerId,
      displayName: cfg.displayName,
      freeTier: Boolean(cfg.freeTier),
      connected: probeResult.ok,
      statusCode: probeResult.statusCode,
      errorCategory: probeResult.errorCategory || (probeResult.ok ? "NONE" : "PROVIDER_UNAVAILABLE"),
      keyConfigured: true,
      keySource: creds.source,
      maskedKeyHint: creds.maskedHint,
      requiresAccountId: Boolean(cfg.requiresAccountId),
      hasAccountId: Boolean(creds.accountId),
      latencyMs: Math.max(1, checkedAt - startedMs),
      lastCheckedEpochMs: checkedAt,
      message: probeResult.message,
      models: probeResult.discoveredModels
    };
  }

  async function getProviderStatuses({ liveProbe = false, userId = "", freeOnly = false } = {}) {
    const statuses = [];
    for (const cfg of Object.values(PROVIDER_CONFIGS)) {
      if (freeOnly && !cfg.freeTier) continue;
      statuses.push(await probeSingleProvider(cfg.providerId, { liveProbe, userId }));
    }
    return statuses;
  }

  async function invokeSingleProvider({ providerId, modelId, systemPrompt, prompt, jsonMode = false, userId = "" }) {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) {
      throw new ProviderCallError(`Unknown external provider '${providerId}'.`, 400, "UNKNOWN_PROVIDER", "CONFIGURATION_ERROR");
    }
    const creds = resolveCredentials(providerId, userId);
    if (creds.vaultError) {
      throw new ProviderCallError(creds.vaultError, 500, "CONFIGURATION_ERROR", "CONFIGURATION_ERROR");
    }
    if (!creds.apiKey || (cfg.requiresAccountId && !creds.accountId)) {
      const missingName = !creds.apiKey ? cfg.envKey : cfg.accountIdEnvKey;
      throw new ProviderCallError(
        `${cfg.displayName} is not connected: ${missingName} is missing.`,
        503,
        "MISSING_API_KEY",
        "CONFIGURATION_ERROR"
      );
    }

    const chosenModel = (modelId && modelId.trim()) || cfg.defaultModel;
    const startedAt = Date.now();
    const sysText = systemPrompt || "You are MYRAA AI SUPER BRAIN engineering agent.";

    try {
      if (cfg.apiStyle === "anthropic") {
        const resp = await fetchImpl("https://api.anthropic.com/v1/messages", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            ...buildAuthHeaders(cfg, creds.apiKey)
          },
          body: JSON.stringify({
            model: chosenModel,
            max_tokens: 4096,
            system: sysText,
            messages: [{ role: "user", content: prompt }]
          })
        });
        const raw = await resp.text().catch(() => "");
        const details = extractErrorDetails(raw);
        if (!resp.ok) {
          const classified = classifyUpstreamFailure(resp.status, details.message, null, {
            rawBody: details.rawBody,
            providerId
          });
          throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode, classified.errorCategory);
        }
        const json = details.parsed;
        const text = Array.isArray(json.content)
          ? json.content.filter((c) => c.type === "text").map((c) => c.text).join("\n")
          : "";
        return {
          providerId,
          modelId: chosenModel,
          text,
          promptTokens: Number(json?.usage?.input_tokens || 0),
          completionTokens: Number(json?.usage?.output_tokens || 0),
          latencyMs: Math.max(1, Date.now() - startedAt)
        };
      }

      if (cfg.apiStyle === "cohere_v2") {
        const resp = await fetchImpl(resolveChatEndpoint(cfg, creds.accountId), {
          method: "POST",
          headers: {
            "content-type": "application/json",
            ...buildAuthHeaders(cfg, creds.apiKey)
          },
          body: JSON.stringify({
            model: chosenModel,
            messages: [
              { role: "system", content: sysText },
              { role: "user", content: prompt }
            ]
          })
        });
        const raw = await resp.text().catch(() => "");
        const details = extractErrorDetails(raw);
        if (!resp.ok) {
          const classified = classifyUpstreamFailure(resp.status, details.message, null, {
            rawBody: details.rawBody,
            providerId
          });
          throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode, classified.errorCategory);
        }
        const json = details.parsed;
        const contentArr = Array.isArray(json?.message?.content) ? json.message.content : [];
        const text = contentArr
          .filter((item) => item?.type === "text" || typeof item?.text === "string")
          .map((item) => item.text)
          .join("\n") || String(json?.text || "");
        const promptTokens = Number(json?.usage?.tokens?.input_tokens ?? json?.usage?.billed_units?.input_tokens ?? 0);
        const completionTokens = Number(json?.usage?.tokens?.output_tokens ?? json?.usage?.billed_units?.output_tokens ?? 0);
        return {
          providerId,
          modelId: chosenModel,
          text,
          promptTokens,
          completionTokens,
          latencyMs: Math.max(1, Date.now() - startedAt)
        };
      }

      // OpenAI-compatible (Groq, OpenRouter, Mistral, Cloudflare Workers AI, NVIDIA NIM, SambaNova, OpenAI, DeepSeek, Meta Llama)
      const endpoint = resolveChatEndpoint(cfg, creds.accountId);
      const body = {
        model: chosenModel,
        messages: [
          { role: "system", content: sysText },
          { role: "user", content: prompt }
        ]
      };
      if (jsonMode && (providerId === "openai_gpt" || providerId === "groq" || providerId === "mistral")) {
        body.response_format = { type: "json_object" };
      }

      const resp = await fetchImpl(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          ...buildAuthHeaders(cfg, creds.apiKey)
        },
        body: JSON.stringify(body)
      });
      const raw = await resp.text().catch(() => "");
      const details = extractErrorDetails(raw);
      if (!resp.ok) {
        const classified = classifyUpstreamFailure(resp.status, details.message, null, {
          rawBody: details.rawBody,
          providerId,
          cloudflareErrorCode: details.cloudflareErrorCode
        });
        throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode, classified.errorCategory);
      }

      const json = details.parsed;
      const text = json?.choices?.[0]?.message?.content || "";
      return {
        providerId,
        modelId: json?.model || chosenModel,
        text,
        promptTokens: Number(json?.usage?.prompt_tokens || 0),
        completionTokens: Number(json?.usage?.completion_tokens || 0),
        latencyMs: Math.max(1, Date.now() - startedAt)
      };
    } catch (err) {
      if (err instanceof ProviderCallError) throw err;
      const classified = classifyUpstreamFailure(0, "", err, { providerId });
      throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode, classified.errorCategory);
    }
  }

  async function generateText({
    providerId,
    modelId,
    systemPrompt,
    prompt,
    jsonMode = false,
    userId = "",
    allowFallback = false
  }) {
    try {
      return await invokeSingleProvider({ providerId, modelId, systemPrompt, prompt, jsonMode, userId });
    } catch (primaryErr) {
      const canFallback =
        allowFallback &&
        PROVIDER_CONFIGS[providerId]?.freeTier === true &&
        primaryErr instanceof ProviderCallError &&
        ["RATE_LIMITED", "QUOTA_EXPIRED", "UNAVAILABLE", "OFFLINE"].includes(primaryErr.errorCode);

      if (!canFallback) {
        throw primaryErr;
      }

      const fallbackCandidates = Object.values(PROVIDER_CONFIGS).filter((cfg) => {
        if (!cfg.freeTier || cfg.providerId === providerId) return false;
        const creds = resolveCredentials(cfg.providerId, userId);
        return Boolean(!creds.vaultError && creds.apiKey && (!cfg.requiresAccountId || creds.accountId));
      });

      for (const candidate of fallbackCandidates) {
        try {
          const fallbackRes = await invokeSingleProvider({
            providerId: candidate.providerId,
            modelId: candidate.defaultModel,
            systemPrompt,
            prompt,
            jsonMode,
            userId
          });
          return {
            ...fallbackRes,
            fallbackUsed: true,
            fallbackFromProviderId: providerId,
            fallbackReason: primaryErr.errorCode
          };
        } catch {
          // Continue trying remaining configured free providers
        }
      }

      throw primaryErr;
    }
  }

  return {
    getProviderStatuses,
    probeSingleProvider,
    probeCandidateCredentials,
    generateText,
    keyVault
  };
}
