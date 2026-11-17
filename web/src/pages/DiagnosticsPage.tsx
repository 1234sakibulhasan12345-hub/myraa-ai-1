import { useState } from "react";
import { Activity, RefreshCw, ShieldAlert, ShieldCheck } from "lucide-react";
import { backendClient, BackendApiError } from "../services/backendClient";
import { ensureFirebaseInitialized } from "../services/firebaseClient";
import type {
  AppCheckRegistrarStatus,
  BackendHealthInfo,
  VaultDiagnosticsInfo
} from "../types/myraa";

interface DiagnosticsPageProps {
  backendUrl: string;
  health: BackendHealthInfo | null;
  vaultDiagnostics: VaultDiagnosticsInfo | null;
  onHealthUpdated: (health: BackendHealthInfo | null) => void;
}

export function DiagnosticsPage({
  backendUrl,
  health,
  vaultDiagnostics,
  onHealthUpdated
}: DiagnosticsPageProps) {
  const [running, setRunning] = useState(false);
  const [registrarStatus, setRegistrarStatus] = useState<
    (AppCheckRegistrarStatus & { tokenRefreshedOnRetry: boolean }) | null
  >(null);
  const [lastVaultInfo, setLastVaultInfo] = useState<VaultDiagnosticsInfo | null>(
    vaultDiagnostics
  );
  const [diagnosticError, setDiagnosticError] = useState("");
  const [checkedAtMs, setCheckedAtMs] = useState<number | null>(null);

  const firebaseDiag = ensureFirebaseInitialized().diagnostics;

  const runFullDiagnostics = async () => {
    setRunning(true);
    setDiagnosticError("");
    try {
      const h = await backendClient.checkHealth(backendUrl);
      onHealthUpdated(h);
      const [reg, keysRes] = await Promise.all([
        backendClient.fetchRegistrarStatus(backendUrl),
        backendClient.fetchProviderKeysSummary(backendUrl)
      ]);
      setRegistrarStatus(reg);
      if (keysRes.vaultStatus) {
        setLastVaultInfo(keysRes.vaultStatus);
      }
      setCheckedAtMs(Date.now());
    } catch (err) {
      const msg =
        err instanceof BackendApiError
          ? `${err.errorCategory} (HTTP ${err.httpStatus}): ${err.message}`
          : err instanceof Error
          ? err.message
          : "Diagnostics run failed.";
      setDiagnosticError(msg);
      if (err instanceof BackendApiError && err.errorCategory === "NETWORK_ERROR") {
        onHealthUpdated(null);
      }
    } finally {
      setRunning(false);
    }
  };

  const currentOrigin = typeof window !== "undefined" ? window.location.origin : "";

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px" }}>
      <section className="glass-card" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "12px" }}>
        <div>
          <h2 style={{ margin: 0 }}>Backend, Auth & App Check Diagnostics</h2>
          <p style={{ margin: "4px 0 0", color: "var(--text-secondary)", fontSize: "0.86rem" }}>
            Truthful runtime verification of Firebase Auth JWT forwarding, Web App Check attestation, CORS allowlist, and Render backend health.
          </p>
        </div>
        <button type="button" className="btn btn-primary" onClick={runFullDiagnostics} disabled={running}>
          <RefreshCw size={16} />
          <span>{running ? "Running Probes..." : "Run Live Diagnostics"}</span>
        </button>
      </section>

      {diagnosticError && (
        <div className="glass-card" style={{ borderColor: "rgba(251, 113, 133, 0.5)" }}>
          <span className="badge badge-crimson">{diagnosticError}</span>
        </div>
      )}

      <section className="grid-cards">
        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <strong>1. Render Backend `/health`</strong>
            <Activity size={18} style={{ color: "var(--cyber-blue)" }} />
          </div>
          <span className={`badge ${health ? "badge-teal" : "badge-crimson"}`}>
            {health ? `OK • ${health.latencyMs}ms` : "UNREACHABLE / CORS BLOCKED"}
          </span>
          <div style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
            URL: <code>{backendUrl}</code>
            <br />
            Service: <code>{health?.service || "unknown"}</code>
            <br />
            CORS allowlist entries:{" "}
            <code>{health?.corsAllowlistCount ?? "check ALLOWED_WEB_ORIGINS"}</code>
          </div>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <strong>2. Firebase Web SDK & App Check</strong>
            {firebaseDiag.appCheckEnabled ? (
              <ShieldCheck size={18} style={{ color: "var(--electric-teal)" }} />
            ) : (
              <ShieldAlert size={18} style={{ color: "var(--amber-signal)" }} />
            )}
          </div>
          <span className={`badge ${firebaseDiag.configured ? "badge-teal" : "badge-amber"}`}>
            {firebaseDiag.configured ? "FIREBASE WEB CONFIGURED" : "NEEDS WEB APP CONFIG"}
          </span>
          <div style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
            Project: <code>{firebaseDiag.projectId}</code>
            <br />
            Named DB: <code>{firebaseDiag.databaseId}</code>
            <br />
            App Check: <code>{firebaseDiag.appCheckProvider}</code>
            <br />
            Status: {firebaseDiag.appCheckLastStatus}
          </div>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <strong>3. Authenticated Registrar Status</strong>
          <span className={`badge ${registrarStatus?.configured ? "badge-teal" : "badge-blue"}`}>
            {registrarStatus
              ? registrarStatus.configured
                ? `CONFIGURED (${registrarStatus.credentialMode})`
                : "NOT CONFIGURED"
              : "RUN PROBE TO INSPECT"}
          </span>
          <div style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
            {registrarStatus ? (
              <>
                Android Pkg: <code>{registrarStatus.androidPackage}</code>
                <br />
                Reason: {registrarStatus.reason}
                <br />
                ID Token Refreshed on 401 Retry:{" "}
                <code>{registrarStatus.tokenRefreshedOnRetry ? "YES" : "NO"}</code>
              </>
            ) : (
              "Click Run Live Diagnostics to verify Authorization: Bearer <Firebase ID Token> with automatic expired-token retry."
            )}
          </div>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <strong>4. Encrypted Key Vault Status</strong>
          <span
            className={`badge ${
              lastVaultInfo?.persistentMasterKey ? "badge-teal" : "badge-amber"
            }`}
          >
            {lastVaultInfo
              ? lastVaultInfo.persistentMasterKey
                ? `PERSISTENT (${lastVaultInfo.masterKeySource})`
                : `EPHEMERAL (${lastVaultInfo.masterKeySource})`
              : "RUN PROBE TO INSPECT"}
          </span>
          <div style={{ fontSize: "0.82rem", color: "var(--text-secondary)" }}>
            Vault Directory Configured: <code>{String(lastVaultInfo?.vaultDirConfigured ?? false)}</code>
            <br />
            {checkedAtMs && `Last checked: ${new Date(checkedAtMs).toLocaleTimeString()}`}
          </div>
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
        <h3 style={{ margin: 0 }}>Browser Origin & CORS Checklist</h3>
        <p style={{ margin: 0, fontSize: "0.86rem", color: "var(--text-secondary)" }}>
          If your browser reports a network or CORS error against <code>{backendUrl}</code>, add your exact website origin below to the Render backend environment variable <code>ALLOWED_WEB_ORIGINS</code>:
        </p>
        <code style={{ padding: "8px 12px", borderRadius: "10px", background: "rgba(6, 7, 20, 0.7)" }}>
          ALLOWED_WEB_ORIGINS={currentOrigin || "https://your-myraa-web.onrender.com"}
        </code>
      </section>
    </div>
  );
}
