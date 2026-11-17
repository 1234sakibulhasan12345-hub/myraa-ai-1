import { useMemo, useState } from "react";
import { Check, Cpu, Play, Sparkles, Zap } from "lucide-react";
import { backendClient, BackendApiError } from "../services/backendClient";
import { MarkdownRenderer } from "../components/MarkdownRenderer";
import type { ProviderStatusItem, RoutingPolicy } from "../types/myraa";

interface ModelsPageProps {
  backendUrl: string;
  providers: ProviderStatusItem[];
  selectedProviderId: string;
  selectedModelId: string;
  routingPolicy: RoutingPolicy;
  onSelectDefaultModel: (providerId: string, modelId: string) => void;
  onChangeRoutingPolicy: (policy: RoutingPolicy) => void;
  onGoToChat: () => void;
}

interface CompareResult {
  providerId: string;
  displayName: string;
  modelId: string;
  status: "running" | "success" | "error";
  text?: string;
  latencyMs?: number;
  errorMessage?: string;
}

const ROUTING_POLICIES: Array<{ id: RoutingPolicy; title: string; summary: string }> = [
  {
    id: "AUTO_BALANCED",
    title: "Auto Balanced (Recommended)",
    summary: "Balances speed, reasoning quality, and automatic failover across connected providers."
  },
  {
    id: "QUALITY_DEFAULT",
    title: "Quality First",
    summary: "Prioritizes highest-capability reasoning and coding models before falling back."
  },
  {
    id: "ULTRA_FAST",
    title: "Ultra Fast",
    summary: "Prioritizes sub-second low-latency inference (Groq, SambaNova, Cerebras, Gemini Flash)."
  }
];

export function ModelsPage({
  backendUrl,
  providers,
  selectedProviderId,
  selectedModelId,
  routingPolicy,
  onSelectDefaultModel,
  onChangeRoutingPolicy,
  onGoToChat
}: ModelsPageProps) {
  const [comparePrompt, setComparePrompt] = useState(
    "Give 3 concise architectural advantages of Firebase + Render separation."
  );
  const [selectedCompareProviders, setSelectedCompareProviders] = useState<string[]>([]);
  const [compareResults, setCompareResults] = useState<CompareResult[]>([]);
  const [isComparing, setIsComparing] = useState(false);
  const [compareBanner, setCompareBanner] = useState("");

  const connectedProviders = useMemo(
    () => providers.filter((p) => p.connected && p.models.length > 0),
    [providers]
  );

  const toggleCompareProvider = (id: string) => {
    setSelectedCompareProviders((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id].slice(0, 4)
    );
  };

  const runComparison = async () => {
    const cleanPrompt = comparePrompt.trim();
    if (!cleanPrompt) return;
    const targetIds =
      selectedCompareProviders.length > 0
        ? selectedCompareProviders
        : connectedProviders.slice(0, 2).map((p) => p.providerId);

    if (targetIds.length === 0) {
      setCompareBanner("Connect at least one AI provider in AI Providers before comparing models.");
      return;
    }

    setCompareBanner("");
    setIsComparing(true);

    const targets = targetIds
      .map((id) => connectedProviders.find((p) => p.providerId === id))
      .filter((p): p is ProviderStatusItem => Boolean(p));

    setCompareResults(
      targets.map((p) => ({
        providerId: p.providerId,
        displayName: p.displayName,
        modelId: p.models[0],
        status: "running"
      }))
    );

    await Promise.all(
      targets.map(async (provider) => {
        const modelId = provider.models[0];
        try {
          const res = await backendClient.generateText(backendUrl, {
            providerId: provider.providerId,
            modelId,
            prompt: cleanPrompt,
            allowFallback: false
          });
          setCompareResults((prev) =>
            prev.map((row) =>
              row.providerId === provider.providerId
                ? {
                    ...row,
                    status: "success",
                    modelId: res.modelId || modelId,
                    text: res.text,
                    latencyMs: res.latencyMs
                  }
                : row
            )
          );
        } catch (err) {
          const msg =
            err instanceof BackendApiError
              ? `${err.errorCategory}: ${err.message}`
              : err instanceof Error
              ? err.message
              : "Comparison call failed.";
          setCompareResults((prev) =>
            prev.map((row) =>
              row.providerId === provider.providerId
                ? { ...row, status: "error", errorMessage: msg }
                : row
            )
          );
        }
      })
    );

    setIsComparing(false);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px" }}>
      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" }}>
          <div>
            <h2 style={{ margin: 0 }}>Routing Policy & Default Model</h2>
            <p style={{ margin: "4px 0 0", color: "var(--text-secondary)", fontSize: "0.88rem" }}>
              Choose how MYRAA AI picks models across your encrypted provider keys and fallback chain.
            </p>
          </div>
          <button type="button" className="btn btn-primary" onClick={onGoToChat}>
            <Sparkles size={16} />
            <span>Use Selected Model in Chat</span>
          </button>
        </div>

        <div className="grid-cards">
          {ROUTING_POLICIES.map((policy) => {
            const active = routingPolicy === policy.id;
            return (
              <button
                key={policy.id}
                type="button"
                className={`glass-card ${active ? "glass-card-highlight" : ""}`}
                onClick={() => onChangeRoutingPolicy(policy.id)}
                style={{
                  textAlign: "left",
                  cursor: "pointer",
                  display: "flex",
                  flexDirection: "column",
                  gap: "6px"
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", width: "100%" }}>
                  <strong>{policy.title}</strong>
                  {active && <span className="badge badge-teal">ACTIVE</span>}
                </div>
                <span style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
                  {policy.summary}
                </span>
              </button>
            );
          })}
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
          <Cpu size={18} style={{ color: "var(--cyber-blue)" }} />
          <h3 style={{ margin: 0 }}>Available Models by Provider</h3>
        </div>

        <div className="grid-cards">
          {providers.map((provider) => {
            const isSelectedProvider = provider.providerId === selectedProviderId;
            return (
              <div
                key={provider.providerId}
                className={`glass-card ${provider.connected ? "glass-card-highlight" : ""}`}
                style={{ display: "flex", flexDirection: "column", gap: "10px" }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "8px" }}>
                  <strong>{provider.displayName}</strong>
                  <span className={`badge ${provider.connected ? "badge-teal" : "badge-amber"}`}>
                    {provider.connected ? "CONNECTED" : provider.statusCode || "NOT CONFIGURED"}
                  </span>
                </div>

                {provider.models.length === 0 ? (
                  <p style={{ margin: 0, fontSize: "0.82rem", color: "var(--text-secondary)" }}>
                    No verified models returned yet. Add or verify key in AI Providers.
                  </p>
                ) : (
                  <div style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                    {provider.models.map((model) => {
                      const active = isSelectedProvider && selectedModelId === model;
                      return (
                        <button
                          key={model}
                          type="button"
                          className={`btn ${active ? "btn-teal" : ""}`}
                          style={{
                            justifyContent: "space-between",
                            minHeight: "38px",
                            padding: "6px 12px",
                            fontSize: "0.82rem"
                          }}
                          onClick={() => onSelectDefaultModel(provider.providerId, model)}
                          disabled={!provider.connected}
                        >
                          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                            {model}
                          </span>
                          {active && <Check size={14} />}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
          <Zap size={18} style={{ color: "var(--neon-purple)" }} />
          <h3 style={{ margin: 0 }}>Side-by-Side Model Comparison</h3>
        </div>
        <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.86rem" }}>
          Select up to 4 connected providers to benchmark output quality and response latency on the same prompt.
        </p>

        <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
          {connectedProviders.map((provider) => {
            const checked = selectedCompareProviders.includes(provider.providerId);
            return (
              <button
                key={provider.providerId}
                type="button"
                className={`btn ${checked ? "btn-primary" : ""}`}
                style={{ minHeight: "36px", padding: "6px 12px", fontSize: "0.82rem" }}
                onClick={() => toggleCompareProvider(provider.providerId)}
              >
                {provider.displayName} ({provider.models[0]})
              </button>
            );
          })}
        </div>

        <label className="field-label">
          <span>Benchmark Prompt</span>
          <textarea
            className="textarea-control"
            rows={2}
            value={comparePrompt}
            onChange={(e) => setComparePrompt(e.target.value)}
          />
        </label>

        {compareBanner && <span className="badge badge-crimson">{compareBanner}</span>}

        <div>
          <button
            type="button"
            className="btn btn-primary"
            onClick={runComparison}
            disabled={isComparing || connectedProviders.length === 0}
          >
            <Play size={16} />
            <span>{isComparing ? "Running Comparison..." : "Run Live Model Comparison"}</span>
          </button>
        </div>

        {compareResults.length > 0 && (
          <div className="grid-cards">
            {compareResults.map((res) => (
              <div key={res.providerId} className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "8px" }}>
                  <strong>{res.displayName}</strong>
                  <span
                    className={`badge ${
                      res.status === "success"
                        ? "badge-teal"
                        : res.status === "error"
                        ? "badge-crimson"
                        : "badge-blue"
                    }`}
                  >
                    {res.status === "running"
                      ? "RUNNING"
                      : res.status === "success"
                      ? `${res.latencyMs ?? 0} ms`
                      : "ERROR"}
                  </span>
                </div>
                <span style={{ fontSize: "0.78rem", color: "var(--text-secondary)" }}>
                  Model: <code>{res.modelId}</code>
                </span>
                {res.status === "success" && res.text && (
                  <div style={{ fontSize: "0.86rem" }}>
                    <MarkdownRenderer content={res.text} />
                  </div>
                )}
                {res.status === "error" && (
                  <p style={{ margin: 0, color: "var(--crimson-error)", fontSize: "0.84rem" }}>
                    {res.errorMessage}
                  </p>
                )}
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
