import { Activity, Bot, Cpu, Mic, RefreshCw, Server, Smartphone } from "lucide-react";
import { NeuralOrb } from "../components/NeuralOrb";
import {
  ANDROID_ONLY_CAPABILITIES,
  type AuthenticatedUserView,
  type BackendHealthInfo,
  type ConversationProject,
  type NavPageId,
  type ProviderStatusItem
} from "../types/myraa";

interface OverviewPageProps {
  user: AuthenticatedUserView;
  health: BackendHealthInfo | null;
  providers: ProviderStatusItem[];
  projects: ConversationProject[];
  activeProjectId: string;
  androidApkUrl: string;
  onNavigate: (page: NavPageId) => void;
  onRefreshStatus: () => void;
  isRefreshing: boolean;
}

export function OverviewPage({
  user,
  health,
  providers,
  projects,
  activeProjectId,
  androidApkUrl,
  onNavigate,
  onRefreshStatus,
  isRefreshing
}: OverviewPageProps) {
  const connectedCount = providers.filter((p) => p.connected).length;
  const activeProject = projects.find((p) => p.id === activeProjectId);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px" }}>
      <section
        className="glass-card"
        style={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "center",
          justifyContent: "space-between",
          gap: "20px"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "20px", flexWrap: "wrap", flex: "1 1 320px" }}>
          <NeuralOrb size={120} mood={isRefreshing ? "thinking" : health ? "idle" : "error"} />
          <div style={{ display: "flex", flexDirection: "column", gap: "8px", minWidth: "220px", flex: 1 }}>
            <span className="badge badge-blue" style={{ width: "fit-content" }}>
              MYRAA AI SUPER BRAIN • WEB
            </span>
            <h2 style={{ margin: 0, fontSize: "1.45rem" }}>
              Welcome back, {user.displayName}
            </h2>
            <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.92rem", maxWidth: "60ch" }}>
              Your Personal AI Intelligence connected to Firebase project{" "}
              <code>gen-lang-client-0645829429</code> and your encrypted Render AI backend.
              Chat in Bengali, English, Hindi, or Hinglish seamlessly.
            </p>
            <div style={{ display: "flex", flexWrap: "wrap", gap: "10px", marginTop: "6px" }}>
              <button type="button" className="btn btn-primary" onClick={() => onNavigate("chat")}>
                <Bot size={16} />
                <span>Open AI Chat</span>
              </button>
              <button type="button" className="btn" onClick={() => onNavigate("voice")}>
                <Mic size={16} />
                <span>Voice Assistant</span>
              </button>
              <button type="button" className="btn" onClick={onRefreshStatus} disabled={isRefreshing}>
                <RefreshCw size={16} />
                <span>{isRefreshing ? "Refreshing..." : "Refresh Live Status"}</span>
              </button>
            </div>
          </div>
        </div>
      </section>

      <section className="grid-cards">
        <div className="glass-card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <span style={{ color: "var(--text-secondary)", fontSize: "0.85rem" }}>Backend Service</span>
            <Server size={18} style={{ color: "var(--cyber-blue)" }} />
          </div>
          <h3 style={{ margin: "10px 0 4px" }}>
            {health ? `${health.service}` : "Not reachable"}
          </h3>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            {health
              ? `Status: ${health.status.toUpperCase()} • ${health.latencyMs}ms`
              : "Verify Backend URL or CORS in Settings / Diagnostics"}
          </p>
        </div>

        <div className="glass-card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <span style={{ color: "var(--text-secondary)", fontSize: "0.85rem" }}>AI Providers Connected</span>
            <Cpu size={18} style={{ color: "var(--electric-teal)" }} />
          </div>
          <h3 style={{ margin: "10px 0 4px" }}>
            {connectedCount} / {providers.length} Active
          </h3>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            Managed securely via encrypted server key vault.
          </p>
        </div>

        <div className="glass-card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <span style={{ color: "var(--text-secondary)", fontSize: "0.85rem" }}>Synced Workspace Session</span>
            <Activity size={18} style={{ color: "var(--neon-purple)" }} />
          </div>
          <h3 style={{ margin: "10px 0 4px" }}>
            {activeProject ? activeProject.name : "Default Session"}
          </h3>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            {projects.length} project(s) synced with MYRAA Android via Firestore.
          </p>
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <Smartphone size={20} style={{ color: "var(--amber-signal)" }} />
            <h3 style={{ margin: 0 }}>Android vs. Browser Capability Boundary</h3>
          </div>
          {androidApkUrl && (
            <a href={androidApkUrl} target="_blank" rel="noopener noreferrer" className="btn btn-teal">
              Download MYRAA Android App
            </a>
          )}
        </div>
        <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.88rem" }}>
          This website runs in standard browsers (Chrome, Firefox, Edge, Safari) and cannot access OS-level Android background services. The features below belong exclusively to the companion MYRAA Android app:
        </p>
        <div className="grid-cards">
          {ANDROID_ONLY_CAPABILITIES.map((cap) => (
            <div
              key={cap.id}
              style={{
                padding: "12px",
                borderRadius: "12px",
                background: "rgba(6, 7, 20, 0.55)",
                border: "1px solid var(--border-subtle)"
              }}
            >
              <strong style={{ fontSize: "0.88rem" }}>{cap.title}</strong>
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
