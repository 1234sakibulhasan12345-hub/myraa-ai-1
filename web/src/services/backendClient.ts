import { normalizeBackendUrl, validateBackendUrl } from "../config/firebaseConfig";
import type {
  AppCheckRegistrarStatus,
  BackendErrorCategory,
  BackendHealthInfo,
  ProviderKeySummaryItem,
  ProviderStatusItem,
  VaultDiagnosticsInfo
} from "../types/myraa";
import { getFirebaseIdToken, getWebAppCheckTokenBestEffort } from "./firebaseClient";

export class BackendApiError extends Error {
  httpStatus: number;
  errorCode: string;
  errorCategory: BackendErrorCategory;
  tokenRefreshedOnRetry: boolean;

  constructor(
    message: string,
    httpStatus = 500,
    errorCode = "PROVIDER_UNAVAILABLE",
    errorCategory: BackendErrorCategory = "PROVIDER_UNAVAILABLE",
    tokenRefreshedOnRetry = false
  ) {
    super(message);
    this.name = "BackendApiError";
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.errorCategory = errorCategory;
    this.tokenRefreshedOnRetry = tokenRefreshedOnRetry;
  }
}

export interface TokenFetcher {
  (forceRefresh: boolean): Promise<string | null>;
}

export interface GenerateAiResponse {
  providerId: string;
  modelId: string;
  text: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  fallbackUsed: boolean;
  fallbackFromProviderId: string | null;
}

export interface SaveKeyResponse {
  saved: boolean;
  verified: boolean;
  preservedExistingKey?: boolean;
  provider: ProviderStatusItem;
}

export interface ProvidersKeysResponse {
  vaultStatus?: VaultDiagnosticsInfo;
  providers: ProviderKeySummaryItem[];
}

export function detectCrossProviderKeyPrefixMismatch(
  providerId: string,
  rawKey: string
): string | null {
  const key = String(rawKey || "").trim();
  if (!key) return null;
  if (key.startsWith("sk-or-") && providerId !== "openrouter_free") {
    return "Wrong provider card: keys starting with 'sk-or-' belong to OpenRouter Free Models, not this provider. Paste it under OpenRouter Free Models instead.";
  }
  if (key.startsWith("gsk_") && providerId !== "groq") {
    return "Wrong provider card: keys starting with 'gsk_' belong to Groq, not this provider. Paste it under Groq instead.";
  }
  if (key.startsWith("nvapi-") && providerId !== "nvidia_nim") {
    return "Wrong provider card: keys starting with 'nvapi-' belong to NVIDIA NIM, not this provider.";
  }
  if (key.startsWith("sk-ant-") && providerId !== "anthropic_claude") {
    return "Wrong provider card: keys starting with 'sk-ant-' belong to Anthropic Claude, not this provider.";
  }
  return null;
}

export function classifyBackendErrorCategory(
  httpStatus: number,
  rawErrorCode?: string,
  rawCategory?: string,
  message = ""
): BackendErrorCategory {
  const upperCode = String(rawErrorCode || "").toUpperCase();
  const upperCat = String(rawCategory || "").toUpperCase();
  const lowerMsg = String(message || "").toLowerCase();

  const knownCategories: BackendErrorCategory[] = [
    "INVALID_API_KEY",
    "FIREBASE_AUTH_REQUIRED",
    "APP_CHECK_REQUIRED",
    "CONFIGURATION_ERROR",
    "PROVIDER_UNAVAILABLE",
    "NETWORK_ERROR",
    "RATE_LIMITED",
    "QUOTA_EXPIRED",
    "MISSING_API_KEY"
  ];

  if (knownCategories.includes(upperCode as BackendErrorCategory)) {
    return upperCode as BackendErrorCategory;
  }
  if (upperCat === "INVALID_KEY") return "INVALID_API_KEY";
  if (knownCategories.includes(upperCat as BackendErrorCategory)) {
    return upperCat as BackendErrorCategory;
  }

  if (httpStatus === 0) return "NETWORK_ERROR";
  if (httpStatus === 401) {
    if (lowerMsg.includes("app check") || lowerMsg.includes("attestation")) {
      return "APP_CHECK_REQUIRED";
    }
    return "FIREBASE_AUTH_REQUIRED";
  }
  if (httpStatus === 402) return "QUOTA_EXPIRED";
  if (httpStatus === 429) return "RATE_LIMITED";
  if ([400, 403, 404, 410, 422].includes(httpStatus)) return "CONFIGURATION_ERROR";
  return "PROVIDER_UNAVAILABLE";
}

export class MyraaBackendClient {
  private fetchImpl: typeof fetch;
  private getIdToken: TokenFetcher;
  private getAppCheckToken: TokenFetcher;

  constructor(options?: {
    fetchImpl?: typeof fetch;
    getIdToken?: TokenFetcher;
    getAppCheckToken?: TokenFetcher;
  }) {
    this.fetchImpl = options?.fetchImpl || ((...args) => fetch(...args));
    this.getIdToken = options?.getIdToken || getFirebaseIdToken;
    this.getAppCheckToken = options?.getAppCheckToken || getWebAppCheckTokenBestEffort;
  }

  private assertValidUrl(baseUrl: string): string {
    const clean = normalizeBackendUrl(baseUrl);
    const err = validateBackendUrl(clean);
    if (err) {
      throw new BackendApiError(err, 400, "CONFIGURATION_ERROR", "CONFIGURATION_ERROR");
    }
    return clean;
  }

  private async fetchWithAuthRetry(
    url: string,
    initFactory: (bearer: string, appCheckToken: string | null) => RequestInit
  ): Promise<{ response: Response; tokenRefreshedOnRetry: boolean }> {
    const initialToken = (await this.getIdToken(false))?.trim() || "";
    if (!initialToken) {
      throw new BackendApiError(
        "Sign in with Firebase Authentication to send requests to the MYRAA backend.",
        401,
        "FIREBASE_AUTH_REQUIRED",
        "FIREBASE_AUTH_REQUIRED"
      );
    }
    const appCheckToken = (await this.getAppCheckToken(false))?.trim() || null;

    let resp: Response;
    try {
      resp = await this.fetchImpl(url, initFactory(initialToken, appCheckToken));
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error contacting MYRAA backend.";
      throw new BackendApiError(msg, 0, "NETWORK_ERROR", "NETWORK_ERROR");
    }

    if (resp.status !== 401) {
      return { response: resp, tokenRefreshedOnRetry: false };
    }

    // Check if the 401 is specifically APP_CHECK_REQUIRED before attempting ID token refresh
    const firstCloneText = await resp.clone().text().catch(() => "");
    let parsedErr: { errorCode?: string; error?: string } = {};
    try {
      parsedErr = JSON.parse(firstCloneText) as { errorCode?: string; error?: string };
    } catch {
      parsedErr = {};
    }
    if (parsedErr.errorCode === "APP_CHECK_REQUIRED") {
      return { response: resp, tokenRefreshedOnRetry: false };
    }

    const refreshedToken = (await this.getIdToken(true))?.trim() || "";
    if (!refreshedToken) {
      return { response: resp, tokenRefreshedOnRetry: false };
    }

    const refreshedAppCheck =
      (await this.getAppCheckToken(false))?.trim() || appCheckToken;

    try {
      const retryResp = await this.fetchImpl(url, initFactory(refreshedToken, refreshedAppCheck));
      return { response: retryResp, tokenRefreshedOnRetry: true };
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error retrying authenticated call.";
      throw new BackendApiError(msg, 0, "NETWORK_ERROR", "NETWORK_ERROR", true);
    }
  }

  private async parseJsonOrThrow<T>(
    resp: Response,
    fallbackMsg: string,
    tokenRefreshedOnRetry = false
  ): Promise<T> {
    const raw = await resp.text().catch(() => "");
    let json: Record<string, unknown> = {};
    try {
      json = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    } catch {
      json = {};
    }

    if (!resp.ok) {
      const msg =
        (typeof json.error === "string" && json.error) ||
        `${fallbackMsg} (HTTP ${resp.status})`;
      const code = typeof json.errorCode === "string" ? json.errorCode : "";
      const catRaw = typeof json.errorCategory === "string" ? json.errorCategory : "";
      const category = classifyBackendErrorCategory(resp.status, code, catRaw, msg);
      throw new BackendApiError(
        msg,
        resp.status,
        code || category,
        category,
        tokenRefreshedOnRetry
      );
    }
    return json as T;
  }

  async checkHealth(baseUrl: string): Promise<BackendHealthInfo> {
    const clean = this.assertValidUrl(baseUrl);
    const started = performance.now();
    let resp: Response;
    try {
      resp = await this.fetchImpl(`${clean}/health`, { method: "GET" });
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to reach backend /health";
      throw new BackendApiError(msg, 0, "NETWORK_ERROR", "NETWORK_ERROR");
    }
    const payload = await this.parseJsonOrThrow<{
      status: string;
      service: string;
      appCheckAutoRegistrarConfigured: boolean;
      corsAllowlistCount?: number;
      timestampEpochMs: number;
    }>(resp, "Backend health check failed");
    return {
      ...payload,
      latencyMs: Math.max(1, Math.round(performance.now() - started))
    };
  }

  async fetchRegistrarStatus(
    baseUrl: string
  ): Promise<AppCheckRegistrarStatus & { tokenRefreshedOnRetry: boolean }> {
    const clean = this.assertValidUrl(baseUrl);
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/appcheck/registrar/status`,
      (bearer) => ({
        method: "GET",
        headers: { Authorization: `Bearer ${bearer}` }
      })
    );
    const data = await this.parseJsonOrThrow<AppCheckRegistrarStatus>(
      response,
      "Registrar status check failed",
      tokenRefreshedOnRetry
    );
    return { ...data, tokenRefreshedOnRetry };
  }

  async fetchProviderStatuses(
    baseUrl: string,
    options?: { liveProbe?: boolean; freeOnly?: boolean }
  ): Promise<{ providers: ProviderStatusItem[]; tokenRefreshedOnRetry: boolean }> {
    const clean = this.assertValidUrl(baseUrl);
    const probe = options?.liveProbe ?? true;
    const freeOnly = options?.freeOnly ?? false;
    const query = `probe=${probe ? "true" : "false"}${freeOnly ? "&freeOnly=true" : ""}`;
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/providers/status?${query}`,
      (bearer, appCheck) => ({
        method: "GET",
        headers: {
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        }
      })
    );
    const data = await this.parseJsonOrThrow<{ providers: ProviderStatusItem[] }>(
      response,
      "Failed to query AI providers",
      tokenRefreshedOnRetry
    );
    return { providers: data.providers || [], tokenRefreshedOnRetry };
  }

  async fetchProviderKeysSummary(baseUrl: string): Promise<ProvidersKeysResponse> {
    const clean = this.assertValidUrl(baseUrl);
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/providers/keys`,
      (bearer, appCheck) => ({
        method: "GET",
        headers: {
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        }
      })
    );
    return this.parseJsonOrThrow<ProvidersKeysResponse>(
      response,
      "Failed to load encrypted key vault summary",
      tokenRefreshedOnRetry
    );
  }

  async saveProviderKey(
    baseUrl: string,
    payload: { providerId: string; apiKey: string; accountId?: string }
  ): Promise<SaveKeyResponse> {
    const clean = this.assertValidUrl(baseUrl);
    const mismatch = detectCrossProviderKeyPrefixMismatch(payload.providerId, payload.apiKey);
    if (mismatch) {
      throw new BackendApiError(mismatch, 400, "CONFIGURATION_ERROR", "CONFIGURATION_ERROR");
    }
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/providers/keys`,
      (bearer, appCheck) => ({
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        },
        body: JSON.stringify({
          providerId: payload.providerId,
          apiKey: payload.apiKey.trim(),
          ...(payload.accountId?.trim() ? { accountId: payload.accountId.trim() } : {})
        })
      })
    );
    return this.parseJsonOrThrow<SaveKeyResponse>(
      response,
      "Failed to verify & save provider API key",
      tokenRefreshedOnRetry
    );
  }

  async deleteProviderKey(
    baseUrl: string,
    providerId: string
  ): Promise<{ deleted: boolean; provider: ProviderStatusItem }> {
    const clean = this.assertValidUrl(baseUrl);
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/providers/keys/${encodeURIComponent(providerId)}`,
      (bearer, appCheck) => ({
        method: "DELETE",
        headers: {
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        }
      })
    );
    return this.parseJsonOrThrow<{ deleted: boolean; provider: ProviderStatusItem }>(
      response,
      "Failed to delete provider API key",
      tokenRefreshedOnRetry
    );
  }

  async generateText(
    baseUrl: string,
    payload: {
      providerId?: string;
      modelId?: string;
      systemPrompt?: string;
      prompt: string;
      jsonMode?: boolean;
      allowFallback?: boolean;
      signal?: AbortSignal;
    }
  ): Promise<GenerateAiResponse> {
    const clean = this.assertValidUrl(baseUrl);
    const endpoint = payload.jsonMode ? `${clean}/v1/ai/plan` : `${clean}/v1/ai/generate`;
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      endpoint,
      (bearer, appCheck) => ({
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        },
        signal: payload.signal,
        body: JSON.stringify({
          providerId: payload.providerId,
          modelId: payload.modelId,
          systemPrompt: payload.systemPrompt,
          prompt: payload.prompt,
          jsonMode: Boolean(payload.jsonMode),
          allowFallback: Boolean(payload.allowFallback ?? true)
        })
      })
    );
    return this.parseJsonOrThrow<GenerateAiResponse>(
      response,
      "AI generation failed",
      tokenRefreshedOnRetry
    );
  }

  async streamText(
    baseUrl: string,
    payload: {
      providerId?: string;
      modelId?: string;
      systemPrompt?: string;
      prompt: string;
      allowFallback?: boolean;
      signal?: AbortSignal;
      onDelta: (delta: string, accumulated: string) => void;
    }
  ): Promise<GenerateAiResponse> {
    const clean = this.assertValidUrl(baseUrl);
    const { response, tokenRefreshedOnRetry } = await this.fetchWithAuthRetry(
      `${clean}/v1/ai/stream`,
      (bearer, appCheck) => ({
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${bearer}`,
          ...(appCheck ? { "X-Firebase-AppCheck": appCheck } : {})
        },
        signal: payload.signal,
        body: JSON.stringify({
          providerId: payload.providerId,
          modelId: payload.modelId,
          systemPrompt: payload.systemPrompt,
          prompt: payload.prompt,
          allowFallback: Boolean(payload.allowFallback ?? true)
        })
      })
    );

    if (!response.ok) {
      await this.parseJsonOrThrow(response, "AI stream request failed", tokenRefreshedOnRetry);
    }

    if (!response.body) {
      throw new BackendApiError(
        "Streaming response body is unavailable in this browser.",
        502,
        "PROVIDER_UNAVAILABLE",
        "PROVIDER_UNAVAILABLE"
      );
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let accumulated = "";
    let finalMeta: Partial<GenerateAiResponse> = {};

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const rawLine of lines) {
        const line = rawLine.trim();
        if (!line.startsWith("data:")) continue;
        const jsonPart = line.slice(5).trim();
        if (!jsonPart) continue;
        try {
          const parsed = JSON.parse(jsonPart) as {
            delta?: string;
            done?: boolean;
            providerId?: string;
            modelId?: string;
            promptTokens?: number;
            completionTokens?: number;
            latencyMs?: number;
            fallbackUsed?: boolean;
            fallbackFromProviderId?: string | null;
          };
          if (parsed.delta) {
            accumulated += parsed.delta;
            payload.onDelta(parsed.delta, accumulated);
          }
          if (parsed.done) {
            finalMeta = parsed;
          }
        } catch {
          // Ignore malformed SSE comment/keepalive chunk
        }
      }
    }

    if (!accumulated.trim()) {
      throw new BackendApiError(
        "Stream finished without text output.",
        502,
        "PROVIDER_UNAVAILABLE",
        "PROVIDER_UNAVAILABLE"
      );
    }

    return {
      providerId: finalMeta.providerId || payload.providerId || "groq",
      modelId: finalMeta.modelId || payload.modelId || "llama-3.3-70b-versatile",
      text: accumulated,
      promptTokens: finalMeta.promptTokens || Math.ceil(payload.prompt.length / 4),
      completionTokens: finalMeta.completionTokens || Math.ceil(accumulated.length / 4),
      latencyMs: finalMeta.latencyMs || 1,
      fallbackUsed: Boolean(finalMeta.fallbackUsed),
      fallbackFromProviderId: finalMeta.fallbackFromProviderId || null
    };
  }
}

export const backendClient = new MyraaBackendClient();
