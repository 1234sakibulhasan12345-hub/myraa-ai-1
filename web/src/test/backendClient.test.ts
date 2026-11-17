import { describe, expect, it, vi } from "vitest";
import {
  BackendApiError,
  MyraaBackendClient,
  classifyBackendErrorCategory,
  detectCrossProviderKeyPrefixMismatch
} from "../services/backendClient";

describe("MyraaBackendClient", () => {
  it("attaches Authorization Bearer Firebase ID token and X-Firebase-AppCheck header", async () => {
    const capturedHeaders: Array<Record<string, string>> = [];
    const mockFetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      capturedHeaders.push((init?.headers as Record<string, string>) || {});
      return new Response(
        JSON.stringify({
          providers: [
            {
              providerId: "groq",
              displayName: "Groq",
              freeTier: true,
              connected: true,
              statusCode: "CONNECTED",
              errorCategory: "NONE",
              keyConfigured: true,
              keySource: "USER_VAULT",
              maskedKeyHint: "gsk_...1234",
              requiresAccountId: false,
              hasAccountId: false,
              message: "Connected",
              models: ["llama-3.3-70b-versatile"]
            }
          ]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    });

    const client = new MyraaBackendClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      getIdToken: async () => "firebase-id-token-initial",
      getAppCheckToken: async () => "web-app-check-token"
    });

    const result = await client.fetchProviderStatuses("https://myraa-super-brain-backend.onrender.com");
    expect(result.providers).toHaveLength(1);
    expect(result.tokenRefreshedOnRetry).toBe(false);
    expect(capturedHeaders[0].Authorization).toBe("Bearer firebase-id-token-initial");
    expect(capturedHeaders[0]["X-Firebase-AppCheck"]).toBe("web-app-check-token");
  });

  it("refreshes expired Firebase ID token and retries once on 401 FIREBASE_AUTH_REQUIRED", async () => {
    const tokensSeen: string[] = [];
    const tokenFetcher = vi.fn(async (forceRefresh: boolean) =>
      forceRefresh ? "firebase-id-token-refreshed" : "firebase-id-token-expired"
    );
    const mockFetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = (init?.headers as Record<string, string>) || {};
      tokensSeen.push(headers.Authorization || "");
      if (tokensSeen.length === 1) {
        return new Response(
          JSON.stringify({
            error: "Token expired",
            errorCode: "FIREBASE_AUTH_REQUIRED"
          }),
          { status: 401, headers: { "Content-Type": "application/json" } }
        );
      }
      return new Response(
        JSON.stringify({
          configured: true,
          credentialMode: "FIREBASE_ADMIN_JSON",
          projectId: "gen-lang-client-0645829429",
          androidPackage: "com.aistudio.myraa.qxkrmp",
          reason: "Ready"
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    });

    const client = new MyraaBackendClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      getIdToken: tokenFetcher,
      getAppCheckToken: async () => null
    });

    const status = await client.fetchRegistrarStatus("https://myraa-super-brain-backend.onrender.com");
    expect(status.configured).toBe(true);
    expect(status.tokenRefreshedOnRetry).toBe(true);
    expect(tokenFetcher).toHaveBeenNthCalledWith(1, false);
    expect(tokenFetcher).toHaveBeenNthCalledWith(2, true);
    expect(tokensSeen).toEqual([
      "Bearer firebase-id-token-expired",
      "Bearer firebase-id-token-refreshed"
    ]);
  });

  it("does not wastefully retry ID token when 401 is APP_CHECK_REQUIRED", async () => {
    const tokenFetcher = vi.fn(async () => "firebase-id-token-valid");
    const mockFetch = vi.fn(async () =>
      new Response(
        JSON.stringify({
          error: "App Check verification failed",
          errorCode: "APP_CHECK_REQUIRED"
        }),
        { status: 401, headers: { "Content-Type": "application/json" } }
      )
    );

    const client = new MyraaBackendClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      getIdToken: tokenFetcher,
      getAppCheckToken: async () => null
    });

    await expect(
      client.fetchProviderStatuses("https://myraa-super-brain-backend.onrender.com")
    ).rejects.toMatchObject({
      httpStatus: 401,
      errorCategory: "APP_CHECK_REQUIRED"
    });
    expect(tokenFetcher).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  it("parses SSE stream chunks progressively", async () => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"delta":"Hello "}\n\n'));
        controller.enqueue(encoder.encode('data: {"delta":"MYRAA!"}\n\n'));
        controller.enqueue(
          encoder.encode(
            'data: {"done":true,"providerId":"groq","modelId":"llama-3.3-70b-versatile","latencyMs":140}\n\n'
          )
        );
        controller.close();
      }
    });

    const mockFetch = vi.fn(async () =>
      new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } })
    );

    const client = new MyraaBackendClient({
      fetchImpl: mockFetch as unknown as typeof fetch,
      getIdToken: async () => "valid-jwt",
      getAppCheckToken: async () => "valid-appcheck"
    });

    const seenDeltas: string[] = [];
    const result = await client.streamText("https://myraa-super-brain-backend.onrender.com", {
      providerId: "groq",
      prompt: "Say hi",
      onDelta: (delta) => seenDeltas.push(delta)
    });

    expect(seenDeltas).toEqual(["Hello ", "MYRAA!"]);
    expect(result.text).toBe("Hello MYRAA!");
    expect(result.providerId).toBe("groq");
    expect(result.latencyMs).toBe(140);
  });

  it("detects cross-provider key prefix mismatches before transmitting", async () => {
    const mismatchMsg = detectCrossProviderKeyPrefixMismatch("groq", "sk-or-test-key-value");
    expect(mismatchMsg).toContain("OpenRouter");

    const client = new MyraaBackendClient({
      fetchImpl: vi.fn() as unknown as typeof fetch,
      getIdToken: async () => "valid-jwt",
      getAppCheckToken: async () => null
    });

    await expect(
      client.saveProviderKey("https://myraa-super-brain-backend.onrender.com", {
        providerId: "groq",
        apiKey: "sk-or-test-key-value"
      })
    ).rejects.toBeInstanceOf(BackendApiError);
  });

  it("maps all 8 canonical backend error categories accurately", () => {
    expect(classifyBackendErrorCategory(401, "INVALID_API_KEY")).toBe("INVALID_API_KEY");
    expect(classifyBackendErrorCategory(401, "FIREBASE_AUTH_REQUIRED")).toBe(
      "FIREBASE_AUTH_REQUIRED"
    );
    expect(classifyBackendErrorCategory(401, "APP_CHECK_REQUIRED")).toBe("APP_CHECK_REQUIRED");
    expect(classifyBackendErrorCategory(400, "CONFIGURATION_ERROR")).toBe("CONFIGURATION_ERROR");
    expect(classifyBackendErrorCategory(503, "PROVIDER_UNAVAILABLE")).toBe(
      "PROVIDER_UNAVAILABLE"
    );
    expect(classifyBackendErrorCategory(0, "")).toBe("NETWORK_ERROR");
    expect(classifyBackendErrorCategory(429, "")).toBe("RATE_LIMITED");
    expect(classifyBackendErrorCategory(402, "")).toBe("QUOTA_EXPIRED");
  });
});
