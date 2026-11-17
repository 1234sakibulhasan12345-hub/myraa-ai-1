import { useState } from "react";
import { Check, LogOut, Shield, User } from "lucide-react";
import type { AuthenticatedUserView, UserWorkspaceProfile } from "../types/myraa";

interface ProfilePageProps {
  user: AuthenticatedUserView;
  workspace: UserWorkspaceProfile | null;
  onUpdateDisplayName: (newName: string) => Promise<void>;
  onSignOut: () => Promise<void>;
}

export function ProfilePage({
  user,
  workspace,
  onUpdateDisplayName,
  onSignOut
}: ProfilePageProps) {
  const [nameInput, setNameInput] = useState(user.displayName);
  const [saving, setSaving] = useState(false);
  const [statusMessage, setStatusMessage] = useState("");
  const [errorMessage, setErrorMessage] = useState("");

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = nameInput.trim();
    if (!trimmed) {
      setErrorMessage("Display name cannot be empty.");
      return;
    }
    setSaving(true);
    setErrorMessage("");
    setStatusMessage("");
    try {
      await onUpdateDisplayName(trimmed);
      setStatusMessage("Profile updated and synced to Firebase Auth & Firestore.");
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : "Failed to update display name.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px", maxWidth: "820px" }}>
      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "14px", flexWrap: "wrap" }}>
          <div
            style={{
              width: "64px",
              height: "64px",
              borderRadius: "50%",
              background: "linear-gradient(135deg, var(--cyber-blue), var(--neon-purple))",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "#060714",
              fontWeight: 800,
              fontSize: "1.4rem",
              overflow: "hidden",
              flexShrink: 0
            }}
          >
            {user.photoURL ? (
              <img
                src={user.photoURL}
                alt={user.displayName}
                width={64}
                height={64}
                style={{ objectFit: "cover" }}
              />
            ) : (
              <User size={28} />
            )}
          </div>
          <div>
            <h2 style={{ margin: 0 }}>{user.displayName}</h2>
            <p style={{ margin: "4px 0 0", color: "var(--text-secondary)", fontSize: "0.88rem" }}>
              {user.email || user.phoneNumber || "Authenticated Firebase User"}
            </p>
          </div>
        </div>

        <div className="grid-cards">
          <div className="glass-card" style={{ padding: "12px" }}>
            <span style={{ fontSize: "0.78rem", color: "var(--text-secondary)" }}>Firebase UID</span>
            <div style={{ marginTop: "4px", fontSize: "0.86rem", wordBreak: "break-all" }}>
              <code>{user.uid}</code>
            </div>
          </div>

          <div className="glass-card" style={{ padding: "12px" }}>
            <span style={{ fontSize: "0.78rem", color: "var(--text-secondary)" }}>Auth Providers</span>
            <div style={{ marginTop: "6px", display: "flex", flexWrap: "wrap", gap: "6px" }}>
              {user.providerIds.length > 0 ? (
                user.providerIds.map((pid) => (
                  <span key={pid} className="badge badge-blue">
                    {pid}
                  </span>
                ))
              ) : (
                <span className="badge badge-blue">firebase</span>
              )}
            </div>
          </div>

          <div className="glass-card" style={{ padding: "12px" }}>
            <span style={{ fontSize: "0.78rem", color: "var(--text-secondary)" }}>Verification & Workspace</span>
            <div style={{ marginTop: "6px", display: "flex", flexWrap: "wrap", gap: "6px" }}>
              <span className={`badge ${user.emailVerified ? "badge-teal" : "badge-amber"}`}>
                {user.emailVerified ? "EMAIL VERIFIED" : "UNVERIFIED / PHONE"}
              </span>
              <span className="badge badge-purple">
                {workspace ? "FIRESTORE SYNCED" : "LOCAL SESSION"}
              </span>
            </div>
          </div>
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
          <Shield size={18} style={{ color: "var(--electric-teal)" }} />
          <h3 style={{ margin: 0 }}>Account Preferences</h3>
        </div>

        <form onSubmit={handleSave} style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
          <label className="field-label">
            <span>Display Name</span>
            <input
              type="text"
              className="input-control"
              value={nameInput}
              onChange={(e) => setNameInput(e.target.value)}
              maxLength={80}
            />
          </label>

          {statusMessage && <span className="badge badge-teal">{statusMessage}</span>}
          {errorMessage && <span className="badge badge-crimson">{errorMessage}</span>}

          <div style={{ display: "flex", flexWrap: "wrap", gap: "10px" }}>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              <Check size={16} />
              <span>{saving ? "Saving..." : "Save Display Name"}</span>
            </button>
            <button type="button" className="btn btn-danger" onClick={onSignOut}>
              <LogOut size={16} />
              <span>Sign Out</span>
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
