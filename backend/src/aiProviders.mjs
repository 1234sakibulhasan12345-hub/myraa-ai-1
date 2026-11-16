import { createProviderKeyVault, maskSecretHint } from "./providerKeyVault.mjs";

export class ProviderCallError extends Error {
  constructor(message, statusCode = 502, errorCode = "PROVIDER_ERROR") {
    super(message);
    this.name = "ProviderCallError";
    this.statusCode = statusCode;
    this.errorCode = errorCode;
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
    defaultModel: "meta/llama-3.3-70b-instruct",
    availableModels: [
      "meta/llama-3.3-70b-instruct",
      "deepseek-ai/deepseek-r1",
      "qwen/qwen2.5-coder-32b-instruct"
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
      "DeepSeek-R1",
      "Qwen3-32B"
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

export function classifyUpstreamFailure(httpStatus, errorBodyMessage, err = null) {
  if (err && !httpStatus) {
    const msg = String(err.message || err || "");
    const lower = msg.toLowerCase();
    if (
      err.name === "AbortError" ||
      lower.includes("timeout") ||
      lower.includes("econnrefused") ||
      lower.includes("enotfound") ||
      lower.includes("fetch failed") ||
      lower.includes("network")
    ) {
      return {
        statusCode: "OFFLINE",
        httpStatus: 503,
        message: `Provider endpoint offline or unreachable: ${msg}`
      };
    }
    return {
      statusCode: "UNAVAILABLE",
      httpStatus: 502,
      message: msg || "Provider request failed."
    };
  }

  const msg = String(errorBodyMessage || "").trim();
  const lower = msg.toLowerCase();
  if (httpStatus === 401 || httpStatus === 403 || lower.includes("invalid api key") || lower.includes("unauthorized")) {
    return {
      statusCode: "INVALID_API_KEY",
      httpStatus: httpStatus || 401,
      message: msg || `API key rejected (HTTP ${httpStatus}). Check key in Settings.`
    };
  }
  if (
    httpStatus === 402 ||
    lower.includes("insufficient_quota") ||
    lower.includes("quota exceeded") ||
    lower.includes("credit") ||
    lower.includes("billing") ||
    lower.includes("payment required") ||
    lower.includes("neuron") && lower.includes("limit")
  ) {
    return {
      statusCode: "QUOTA_EXPIRED",
      httpStatus: httpStatus || 402,
      message: msg || `Free tier quota or credits exhausted (HTTP ${httpStatus}).`
    };
  }
  if (httpStatus === 429 || lower.includes("rate limit") || lower.includes("too many requests")) {
    return {
      statusCode: "RATE_LIMITED",
      httpStatus: 429,
      message: msg || "Provider rate limit reached (HTTP 429). Ready for auto-fallback."
    };
  }
  if (httpStatus === 500 || httpStatus === 502 || httpStatus === 503 || httpStatus === 504) {
    return {
      statusCode: "UNAVAILABLE",
      httpStatus,
      message: msg || `Provider service temporarily unavailable (HTTP ${httpStatus}).`
    };
  }
  return {
    statusCode: "PROBE_FAILED",
    httpStatus: httpStatus || 502,
    message: msg || `Upstream API returned HTTP ${httpStatus}.`
  };
}

export function createAiProviderRouter(options = {}) {
  const env = options.env || process.env;
  const fetchImpl = options.fetchImpl || fetch;
  const keyVault = options.keyVault || createProviderKeyVault({ env });

  function resolveCredentials(providerId, userId = "") {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) return { apiKey: "", accountId: "", source: "NONE", maskedHint: "" };

    const vaultCreds = userId ? keyVault.getProviderCredentials(userId, providerId) : null;
    const envApiKey = (env[cfg.envKey] || "").trim();
    const envAccountId = cfg.accountIdEnvKey ? (env[cfg.accountIdEnvKey] || "").trim() : "";

    const apiKey = (vaultCreds?.apiKey || envApiKey || "").trim();
    const accountId = (vaultCreds?.accountId || envAccountId || "").trim();
    const source = vaultCreds?.apiKey ? "USER_VAULT" : envApiKey ? "SERVER_ENV" : "NONE";
    const maskedHint = apiKey ? (vaultCreds?.maskedHint || maskSecretHint(apiKey)) : "";

    return { apiKey, accountId, source, maskedHint };
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
    if (cfg.apiStyle === "anthropic") {
      return {
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01"
      };
    }
    const headers = {
      Authorization: `Bearer ${apiKey}`
    };
    if (cfg.apiStyle === "openrouter") {
      headers["HTTP-Referer"] = "https://myraa-super-brain.app";
      headers["X-Title"] = "MYRAA AI SUPER BRAIN";
    }
    return headers;
  }

  async function probeProviderEndpoint(cfg, creds) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
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
        headers: buildAuthHeaders(cfg, apiKey),
        signal: controller.signal
      });
      const raw = await resp.text().catch(() => "");
      let parsed = {};
      try {
        parsed = raw ? JSON.parse(raw) : {};
      } catch {
        parsed = {};
      }

      if (!resp.ok) {
        const upstreamMsg = parsed?.error?.message || parsed?.message || parsed?.errors?.[0]?.message || "";
        const classified = classifyUpstreamFailure(resp.status, upstreamMsg);
        return {
          ok: false,
          statusCode: classified.statusCode,
          message: classified.message,
          discoveredModels: cfg.availableModels
        };
      }

      let discoveredModels = cfg.availableModels;
      if (cfg.apiStyle === "openrouter") {
        // Check if OpenRouter account hit negative balance or zero free quota
        const limitRemaining = parsed?.data?.limit_remaining;
        if (typeof limitRemaining === "number" && limitRemaining < 0) {
          return {
            ok: false,
            statusCode: "QUOTA_EXPIRED",
            message: "OpenRouter account balance is negative; free routing is blocked until resolved.",
            discoveredModels
          };
        }
        // Optionally discover live :free models on OpenRouter
        try {
          const modelsResp = await fetchImpl("https://openrouter.ai/api/v1/models", { signal: controller.signal });
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
        message: "Live upstream free-tier handshake verified.",
        discoveredModels
      };
    } catch (err) {
      const classified = classifyUpstreamFailure(0, "", err);
      return {
        ok: false,
        statusCode: classified.statusCode,
        message: classified.message,
        discoveredModels: cfg.availableModels
      };
    } finally {
      clearTimeout(timeout);
    }
  }

  async function probeSingleProvider(providerId, { liveProbe = true, userId = "" } = {}) {
    const cfg = PROVIDER_CONFIGS[providerId];
    if (!cfg) {
      throw new ProviderCallError(`Unknown provider '${providerId}'.`, 400, "UNKNOWN_PROVIDER");
    }

    const creds = resolveCredentials(cfg.providerId, userId);
    const missingAccount = cfg.requiresAccountId && !creds.accountId;

    if (!creds.apiKey || missingAccount) {
      const missingField = !creds.apiKey ? cfg.envKey : cfg.accountIdEnvKey;
      return {
        providerId: cfg.providerId,
        displayName: cfg.displayName,
        freeTier: Boolean(cfg.freeTier),
        connected: false,
        statusCode: "MISSING_API_KEY",
        keyConfigured: false,
        keySource: creds.source,
        maskedKeyHint: creds.maskedHint,
        requiresAccountId: Boolean(cfg.requiresAccountId),
        hasAccountId: Boolean(creds.accountId),
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
        keyConfigured: true,
        keySource: creds.source,
        maskedKeyHint: creds.maskedHint,
        requiresAccountId: Boolean(cfg.requiresAccountId),
        hasAccountId: Boolean(creds.accountId),
        message: `${cfg.envKey} loaded securely (${creds.source === "USER_VAULT" ? "Encrypted Vault" : "Server Env"}).`,
        models: cfg.availableModels
      };
    }

    const probeResult = await probeProviderEndpoint(cfg, creds);
    return {
      providerId: cfg.providerId,
      displayName: cfg.displayName,
      freeTier: Boolean(cfg.freeTier),
      connected: probeResult.ok,
      statusCode: probeResult.statusCode,
      keyConfigured: true,
      keySource: creds.source,
      maskedKeyHint: creds.maskedHint,
      requiresAccountId: Boolean(cfg.requiresAccountId),
      hasAccountId: Boolean(creds.accountId),
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
      throw new ProviderCallError(`Unknown external provider '${providerId}'.`, 400, "UNKNOWN_PROVIDER");
    }
    const creds = resolveCredentials(providerId, userId);
    if (!creds.apiKey || (cfg.requiresAccountId && !creds.accountId)) {
      const missingName = !creds.apiKey ? cfg.envKey : cfg.accountIdEnvKey;
      throw new ProviderCallError(
        `${cfg.displayName} is not connected: ${missingName} is missing.`,
        503,
        "MISSING_API_KEY"
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
        const json = await resp.json().catch(() => ({}));
        if (!resp.ok) {
          const classified = classifyUpstreamFailure(resp.status, json?.error?.message || "");
          throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode);
        }
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
        const json = await resp.json().catch(() => ({}));
        if (!resp.ok) {
          const classified = classifyUpstreamFailure(resp.status, json?.message || json?.error?.message || "");
          throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode);
        }
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
      const json = await resp.json().catch(() => ({}));
      if (!resp.ok) {
        const errDetail = json?.error?.message || json?.errors?.[0]?.message || json?.message || "";
        const classified = classifyUpstreamFailure(resp.status, errDetail);
        throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode);
      }

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
      const classified = classifyUpstreamFailure(0, "", err);
      throw new ProviderCallError(classified.message, classified.httpStatus, classified.statusCode);
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
        return Boolean(creds.apiKey && (!cfg.requiresAccountId || creds.accountId));
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
    generateText,
    keyVault
  };
}
