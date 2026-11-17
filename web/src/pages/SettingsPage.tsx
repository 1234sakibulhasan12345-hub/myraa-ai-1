import { useState } from "react";
import { Check, RotateCcw, Server, Smartphone } from "lucide-react";
import { DEFAULT_BACKEND_URL, validateBackendUrl } from "../config/firebaseConfig";
import { backendClient, BackendApiError } from "../services/backendClient";
import {
  ANDROID_ONLY_CAPABILITIES,
  type BrainLanguage,
  type RoutingPolicy
} from "../types/myraa";

interface SettingsPageProps {
  backendUrl: string;
  language: BrainLanguage;
  routingPolicy: RoutingPolicy;
  androidApkUrl: string;
  onSaveBackendUrl: (newUrl: string) => Promise<void>;
  onChangeLanguage: (lang: BrainLanguage) => void;
  onChangeRoutingPolicy: (policy: RoutingPolicy) => void;
}

export function SettingsPage({
  backendUrl,
  language,
  routingPolicy,
  androidApkUrl,
  onSaveBackendUrl,
  onChangeLanguage,
  onChangeRoutingPolicy
}: SettingsPageProps) {
  const [urlDraft, setUrlDraft] = useState(backendUrl);
  const [testing, setTesting] = useState(false);
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null);

  const handleTestAndSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const validationErr = validateBackendUrl(urlDraft);
    if (validationErr) {
      setFeedback({ ok: false, text: validationErr });
      return;
    }

    setTesting(true);
    setFeedback(null);
    try {
      const health = await backendClient.checkHealth(urlDraft);
      await onSaveBackendUrl(urlDraft);
      setFeedback({
        ok: true,
        text: `Verified ${health.service} (${health.status.toUpperCase()}, ${health.latencyMs}ms) and saved Backend URL.`
      });
    } catch (err) {
      const msg =
        err instanceof BackendApiError
          ? `${err.errorCategory}: ${err.message}`
          : err instanceof Error
          ? err.message
          : "Health verification failed.";
      setFeedback({ ok: false, text: msg });
    } finally {
      setTesting(false);
    }
  };

  const handleResetDefault = async () => {
    setUrlDraft(DEFAULT_BACKEND_URL);
    await onSaveBackendUrl(DEFAULT_BACKEND_URL);
    setFeedback({
      ok: true,
      text: `Restored default Render Backend URL: ${DEFAULT_BACKEND_URL}`
    });
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px", maxWidth: "920px" }}>
      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
          <Server size={18} style={{ color: "var(--cyber-blue)" }} />
          <h2 style={{ margin: 0 }}>Backend Server Connection</h2>
        </div>

        <form onSubmit={handleTestAndSave} style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
          <label className="field-label">
            <span>MYRAA Render Backend HTTPS URL</span>
            <input
              type="url"
              className="input-control"
              value={urlDraft}
              onChange={(e) => setUrlDraft(e.target.value)}
              placeholder={DEFAULT_BACKEND_URL}
            />
          </label>

          {feedback && (
            <span className={`badge ${feedback.ok ? "badge-teal" : "badge-crimson"}`}>
              {feedback.text}
            </span>
          )}

          <div style={{ display: "flex", flexWrap: "wrap", gap: "10px" }}>
            <button type="submit" className="btn btn-primary" disabled={testing}>
              <Check size={16} />
              <span>{testing ? "Testing /health..." : "Verify Health & Save URL"}</span>
            </button>
            <button type="button" className="btn" onClick={handleResetDefault}>
              <RotateCcw size={16} />
              <span>Reset Default URL</span>
            </button>
          </div>
        </form>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <h3 style={{ margin: 0 }}>AI Language & Routing Defaults</h3>
        <div className="grid-cards">
          <label className="field-label">
            <span>Preferred AI Language</span>
            <select
              className="select-control"
              value={language}
              onChange={(e) => onChangeLanguage(e.target.value as BrainLanguage)}
            >
              <option value="AUTO">Auto Detect (Recommended)</option>
              <option value="BN">Bengali (বাংলা)</option>
              <option value="EN">English</option>
              <option value="BN_EN">Banglish (BN + EN)</option>
              <option value="HI">Hindi (हिन्दी)</option>
              <option value="HINGLISH">Hinglish (HI + EN)</option>
            </select>
          </label>

          <label className="field-label">
            <span>Model Routing Policy</span>
            <select
              className="select-control"
              value={routingPolicy}
              onChange={(e) => onChangeRoutingPolicy(e.target.value as RoutingPolicy)}
            >
              <option value="AUTO_BALANCED">Auto Balanced</option>
              <option value="QUALITY_DEFAULT">Quality First</option>
              <option value="ULTRA_FAST">Ultra Fast</option>
            </select>
          </label>
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <Smartphone size={18} style={{ color: "var(--amber-signal)" }} />
            <h3 style={{ margin: 0 }}>MYRAA Android Companion & System Features</h3>
          </div>
          {androidApkUrl ? (
            <a href={androidApkUrl} target="_blank" rel="noopener noreferrer" className="btn btn-teal">
              Download Verified MYRAA APK
            </a>
          ) : (
            <span className="badge badge-amber">
              Build APK from the Android project (`gradle :app:assembleDebug`) or configure VITE_ANDROID_APK_URL
            </span>
          )}
        </div>

        <div className="grid-cards">
          {ANDROID_ONLY_CAPABILITIES.map((cap) => (
            <div
              key={cap.id}
              style={{
                padding: "12px",
                borderRadius: "12px",
                background: "rgba(6, 7, 20, 0.5)",
                border: "1px solid var(--border-subtle)"
              }}
            >
              <strong style={{ fontSize: "0.86rem" }}>{cap.title}</strong>
              <p style={{ margin: "6px 0", fontSize: "0.8rem", color: "var(--text-secondary)" }}>
                {cap.description}
              </p>
              <span className="badge badge-amber" style={{ fontSize: "0.7rem" }}>
                {cap.notice}
              </span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
