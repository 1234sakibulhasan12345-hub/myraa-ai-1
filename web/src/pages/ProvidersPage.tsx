import { useState } from "react";
import { KeyRound, RefreshCw, Shield, Trash2 } from "lucide-react";
import {
  BackendApiError,
  detectCrossProviderKeyPrefixMismatch
} from "../services/backendClient";
import type {
  BackendErrorCategory,
  ProviderStatusItem,
  VaultDiagnosticsInfo
} from "../types/myraa";

interface ProvidersPageProps {
  providers: ProviderStatusItem[];
  vaultStatus: VaultDiagnosticsInfo | null;
  isLoading: boolean;
  onRefresh: () => void;
  onSaveKey: (providerId: string, apiKey: string, accountId?: string) => Promise<{
    saved: boolean;
    verified: boolean;
    message: string;
    category: BackendErrorCategory;
  }>;
  onDeleteKey: (providerId: string) => Promise<void>;
}

const KEY_ENV_HINTS: Record<string, string> = {
  groq: "GROQ_API_KEY (starts with gsk_)",
  openrouter_free: "OPENROUTER_API_KEY (starts with sk-or-)",
  mistral: "MISTRAL_API_KEY",
  cohere: "COHERE_API_KEY",
  cloudflare_ai: "CLOUDFLARE_API_TOKEN + Account ID",
  nvidia_nim: "NVIDIA_NIM_API_KEY (starts with nvapi-)",
  sambanova: "SAMBANOVA_API_KEY",
  openai_gpt: "OPENAI_API_KEY",
  anthropic_claude: "ANTHROPIC_API_KEY",
  deepseek: "DEEPSEEK_API_KEY",
  meta_llama: "META_LLAMA_API_KEY"
};

function categoryBadgeClass(category: string, connected: boolean): string {
  if (connected) return "badge-teal";
  switch (category) {
    case "MISSING_API_KEY":
      return "badge-amber";
    case "RATE_LIMITED":
    case "QUOTA_EXPIRED":
      return "badge-amber";
    case "CONFIGURATION_ERROR":
    case "APP_CHECK_REQUIRED":
    case "FIREBASE_AUTH_REQUIRED":
      return "badge-purple";
    default:
      return "badge-crimson";
  }
}

export function ProvidersPage({
  providers,
  vaultStatus,
  isLoading,
  onRefresh,
  onSaveKey,
  onDeleteKey
}: ProvidersPageProps) {
  const [expandedId, setExpandedId] = useState<string | null>("groq");
  const [keyDrafts, setKeyDrafts] = useState<Record<string, string>>({});
  const [accountDrafts, setAccountDrafts] = useState<Record<string, string>>({});
  const [busyProvider, setBusyProvider] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<
    Record<string, { ok: boolean; category: string; message: string }>
  >({});

  const handleSave = async (provider: ProviderStatusItem) => {
    const rawKey = (keyDrafts[provider.providerId] || "").trim();
    const rawAccount = (accountDrafts[provider.providerId] || "").trim();
    const mismatch = detectCrossProviderKeyPrefixMismatch(provider.providerId, rawKey);
    if (mismatch) {
      setFeedback((prev) => ({
        ...prev,
        [provider.providerId]: { ok: false, category: "CONFIGURATION_ERROR", message: mismatch }
      }));
      return;
    }

    setBusyProvider(provider.providerId);
    try {
      const res = await onSaveKey(provider.providerId, rawKey, rawAccount);
      setFeedback((prev) => ({
        ...prev,
        [provider.providerId]: {
          ok: res.verified,
          category: res.category,
          message: res.message
        }
      }));
      if (res.verified) {
        setKeyDrafts((prev) => ({ ...prev, [provider.providerId]: "" }));
      }
    } catch (err) {
      const cat =
        err instanceof BackendApiError ? err.errorCategory : "PROVIDER_UNAVAILABLE";
      const msg = err instanceof Error ? err.message : "Failed to verify provider key.";
      setFeedback((prev) => ({
        ...prev,
        [provider.providerId]: { ok: false, category: cat, message: msg }
      }));
    } finally {
      setBusyProvider(null);
    }
  };

  const handleDelete = async (providerId: string) => {
    setBusyProvider(providerId);
    try {
      await onDeleteKey(providerId);
      setFeedback((prev) => ({
        ...prev,
        [providerId]: {
          ok: true,
          category: "MISSING_API_KEY",
          message: "Removed key from encrypted backend vault."
        }
      }));
    } catch (err) {
      const cat =
        err instanceof BackendApiError ? err.errorCategory : "PROVIDER_UNAVAILABLE";
      setFeedback((prev) => ({
        ...prev,
        [providerId]: {
          ok: false,
          category: cat,
          message: err instanceof Error ? err.message : "Delete failed."
        }
      }));
    } finally {
      setBusyProvider(null);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <section
        className="glass-card"
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          flexWrap: "wrap",
          gap: "12px"
        }}
      >
        <div>
          <h2 style={{ margin: "0 0 4px" }}>AI Provider Management & Encrypted Vault</h2>
          <p style={{ margin: 0, fontSize: "0.86rem", color: "var(--text-secondary)" }}>
            Keys are verified and stored encrypted at rest (AES-256-GCM) on the authenticated backend—never in browser storage.
          </p>
          {vaultStatus && (
            <div style={{ marginTop: "8px", display: "flex", gap: "8px", flexWrap: "wrap" }}>
              <span className="badge badge-blue">
                Vault Key: {vaultStatus.masterKeySource}
              </span>
              <span className={`badge ${vaultStatus.persistentMasterKey ? "badge-teal" : "badge-amber"}`}>
                {vaultStatus.persistentMasterKey ? "Persistent Secret" : "Ephemeral Secret"}
              </span>
            </div>
          )}
        </div>
        <button type="button" className="btn btn-primary" onClick={onRefresh} disabled={isLoading}>
          <RefreshCw size={16} />
          <span>{isLoading ? "Probing Providers..." : "Probe Live Providers"}</span>
        </button>
      </section>

      <div className="grid-cards">
        {providers.map((provider) => {
          const isExpanded = expandedId === provider.providerId;
          const draft = keyDrafts[provider.providerId] || "";
          const acctDraft = accountDrafts[provider.providerId] || "";
          const cleanDraft = draft.trim();
          const mismatch = detectCrossProviderKeyPrefixMismatch(provider.providerId, cleanDraft);
          const canSave =
            busyProvider !== provider.providerId &&
            cleanDraft.length >= 8 &&
            !/\s/.test(cleanDraft) &&
            (!provider.requiresAccountId || acctDraft.trim().length > 0);
          const fb = feedback[provider.providerId];

          return (
            <div
              key={provider.providerId}
              className={`glass-card ${provider.connected ? "glass-card-highlight" : ""}`}
              style={{ display: "flex", flexDirection: "column", gap: "10px" }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: "8px" }}>
                <div>
                  <strong style={{ fontSize: "1rem" }}>{provider.displayName}</strong>
                  <div style={{ fontSize: "0.78rem", color: "var(--text-muted)", marginTop: "2px" }}>
                    {KEY_ENV_HINTS[provider.providerId] || provider.providerId}
                  </div>
                </div>
                <span className={`badge ${categoryBadgeClass(provider.errorCategory || provider.statusCode, provider.connected)}`}>
                  {provider.connected ? "CONNECTED" : provider.statusCode}
                </span>
              </div>

              <div style={{ display: "flex", flexWrap: "wrap", gap: "6px", fontSize: "0.76rem", color: "var(--text-secondary)" }}>
                <span>Tier: {provider.freeTier ? "Free" : "Proxy"}</span>
                {provider.errorCategory && provider.errorCategory !== "NONE" && (
                  <span>• Category: <strong>{provider.errorCategory}</strong></span>
                )}
                {typeof provider.latencyMs === "number" && (
                  <span>• Latency: {provider.latencyMs}ms</span>
                )}
                {provider.lastCheckedEpochMs && (
                  <span>
                    • Checked: {new Date(provider.lastCheckedEpochMs).toLocaleTimeString()}
                  </span>
                )}
              </div>

              {provider.maskedKeyHint && (
                <div style={{ display: "flex", gap: "8px", alignItems: "center", fontSize: "0.8rem" }}>
                  <Shield size={14} style={{ color: "var(--electric-teal)" }} />
                  <span>
                    {provider.keySource}: <code>{provider.maskedKeyHint}</code>
                  </span>
                </div>
              )}

              <p style={{ margin: 0, fontSize: "0.82rem", color: "var(--text-secondary)" }}>
                {provider.message}
              </p>

              <div style={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
                {provider.models.slice(0, 4).map((m) => (
                  <span
                    key={m}
                    style={{
                      fontSize: "0.72rem",
                      padding: "2px 8px",
                      borderRadius: "8px",
                      background: "rgba(6, 7, 20, 0.7)",
                      border: "1px solid var(--border-subtle)"
                    }}
                  >
                    {m}
                  </span>
                ))}
              </div>

              <button
                type="button"
                className="btn"
                style={{ minHeight: "36px" }}
                onClick={() => setExpandedId(isExpanded ? null : provider.providerId)}
              >
                <KeyRound size={15} />
                <span>{isExpanded ? "Hide Key Vault" : "Configure Key"}</span>
              </button>

              {isExpanded && (
                <div
                  style={{
                    padding: "12px",
                    borderRadius: "12px",
                    background: "rgba(6, 7, 20, 0.72)",
                    border: "1px solid var(--border-subtle)",
                    display: "flex",
                    flexDirection: "column",
                    gap: "10px"
                  }}
                >
                  <label className="field-label">
                    <span>API Key (Sent over HTTPS to Encrypted Vault)</span>
                    <input
                      type="password"
                      className="input-control"
                      value={draft}
                      onChange={(e) =>
                        setKeyDrafts((prev) => ({
                          ...prev,
                          [provider.providerId]: e.target.value
                        }))
                      }
                      placeholder={KEY_ENV_HINTS[provider.providerId] || "Paste API key"}
                      autoComplete="off"
                    />
                  </label>

                  {provider.requiresAccountId && (
                    <label className="field-label">
                      <span>Cloudflare Account ID</span>
                      <input
                        type="text"
                        className="input-control"
                        value={acctDraft}
                        onChange={(e) =>
                          setAccountDrafts((prev) => ({
                            ...prev,
                            [provider.providerId]: e.target.value
                          }))
                        }
                        placeholder="32-character Cloudflare Account ID"
                      />
                    </label>
                  )}

                  {mismatch && (
                    <div style={{ fontSize: "0.78rem", color: "var(--amber-signal)" }}>
                      {mismatch}
                    </div>
                  )}

                  {fb && (
                    <div
                      role="status"
                      style={{
                        padding: "8px 10px",
                        borderRadius: "8px",
                        fontSize: "0.8rem",
                        background: fb.ok ? "rgba(45, 212, 191, 0.12)" : "rgba(251, 113, 133, 0.14)",
                        color: fb.ok ? "var(--electric-teal)" : "var(--crimson-error)"
                      }}
                    >
                      <strong>{fb.category}:</strong> {fb.message}
                    </div>
                  )}

                  <div style={{ display: "flex", gap: "8px" }}>
                    <button
                      type="button"
                      className="btn btn-teal"
                      style={{ flex: 1 }}
                      disabled={!canSave}
                      onClick={() => handleSave(provider)}
                    >
                      {busyProvider === provider.providerId ? "Verifying..." : "Save & Verify Key"}
                    </button>
                    {provider.keySource === "USER_VAULT" && (
                      <button
                        type="button"
                        className="btn btn-danger btn-icon"
                        disabled={busyProvider === provider.providerId}
                        onClick={() => handleDelete(provider.providerId)}
                        aria-label={`Delete saved key for ${provider.displayName}`}
                      >
                        <Trash2 size={16} />
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
