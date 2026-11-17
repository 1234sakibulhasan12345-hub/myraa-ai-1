import { useState } from "react";
import type { ConfirmationResult } from "firebase/auth";
import { AlertTriangle, KeyRound, Mail, Phone, ShieldCheck } from "lucide-react";
import { NeuralOrb } from "../components/NeuralOrb";
import {
  confirmPhoneOtpCode,
  registerWithEmailWeb,
  sendPasswordResetWeb,
  signInWithEmailWeb,
  signInWithGoogleWeb,
  startPhoneOtpSignIn
} from "../services/authService";
import type { FirebaseClientDiagnostics } from "../services/firebaseClient";
import type { AuthenticatedUserView } from "../types/myraa";

interface AuthPageProps {
  diagnostics: FirebaseClientDiagnostics;
  onAuthenticated: (user: AuthenticatedUserView) => void;
}

type AuthTab = "login" | "register" | "phone";

export function AuthPage({ diagnostics, onAuthenticated }: AuthPageProps) {
  const [tab, setTab] = useState<AuthTab>("login");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [phoneNumber, setPhoneNumber] = useState("");
  const [otpCode, setOtpCode] = useState("");
  const [otpConfirmation, setOtpConfirmation] = useState<ConfirmationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [infoMsg, setInfoMsg] = useState<string | null>(null);

  const handleGoogle = async () => {
    setBusy(true);
    setErrorMsg(null);
    setInfoMsg(null);
    try {
      const user = await signInWithGoogleWeb();
      if (user) onAuthenticated(user);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Google Sign-In failed.");
    } finally {
      setBusy(false);
    }
  };

  const handleEmailSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrorMsg(null);
    setInfoMsg(null);
    try {
      if (tab === "register") {
        const user = await registerWithEmailWeb(displayName, email, password);
        onAuthenticated(user);
      } else {
        const user = await signInWithEmailWeb(email, password);
        onAuthenticated(user);
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Email authentication failed.");
    } finally {
      setBusy(false);
    }
  };

  const handleResetPassword = async () => {
    if (!email.trim()) {
      setErrorMsg("Enter your email address first to receive a password reset link.");
      return;
    }
    setBusy(true);
    setErrorMsg(null);
    setInfoMsg(null);
    try {
      await sendPasswordResetWeb(email);
      setInfoMsg("Password reset email sent. Check your inbox.");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Failed to send password reset email.");
    } finally {
      setBusy(false);
    }
  };

  const handleSendPhoneOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrorMsg(null);
    setInfoMsg(null);
    try {
      const confirmation = await startPhoneOtpSignIn(phoneNumber, "myraa-phone-recaptcha");
      setOtpConfirmation(confirmation);
      setInfoMsg("Verification code sent via SMS. Enter the 6-digit code below.");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Failed to send SMS code.");
    } finally {
      setBusy(false);
    }
  };

  const handleConfirmOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!otpConfirmation) return;
    setBusy(true);
    setErrorMsg(null);
    try {
      const user = await confirmPhoneOtpCode(otpConfirmation, otpCode);
      onAuthenticated(user);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Invalid verification code.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "24px 16px"
      }}
    >
      <div
        className="glass-card"
        style={{
          width: "100%",
          maxWidth: "480px",
          display: "flex",
          flexDirection: "column",
          gap: "18px"
        }}
      >
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            textAlign: "center",
            gap: "10px"
          }}
        >
          <NeuralOrb size={108} mood={busy ? "thinking" : errorMsg ? "error" : "idle"} />
          <span className="badge badge-purple">MYRAA AI • WEB PLATFORM</span>
          <h1 style={{ margin: 0, fontSize: "1.65rem" }}>MYRAA AI</h1>
          <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.92rem" }}>
            Your Personal AI Intelligence — Multilingual Workspace synced with your MYRAA Android account.
          </p>
        </div>

        {!diagnostics.configured && (
          <div
            role="alert"
            style={{
              padding: "12px",
              borderRadius: "12px",
              background: "rgba(251, 191, 36, 0.12)",
              border: "1px solid rgba(251, 191, 36, 0.45)",
              fontSize: "0.84rem",
              display: "flex",
              gap: "10px",
              alignItems: "flex-start"
            }}
          >
            <AlertTriangle size={18} style={{ color: "var(--amber-signal)", flexShrink: 0, marginTop: "2px" }} />
            <div>
              <strong>Firebase Web App Config Needed</strong>
              <p style={{ margin: "4px 0 0", color: "var(--text-secondary)" }}>
                Set <code>{diagnostics.missingConfigKeys.join(", ")}</code> in{" "}
                <code>web/.env</code> or Render Static Site environment variables for project{" "}
                <code>{diagnostics.projectId}</code>.
              </p>
            </div>
          </div>
        )}

        <button
          type="button"
          className="btn btn-primary"
          disabled={busy || !diagnostics.configured}
          onClick={handleGoogle}
          style={{ width: "100%" }}
        >
          <ShieldCheck size={18} />
          <span>Continue with Google Sign-In</span>
        </button>

        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(3, 1fr)",
            gap: "6px",
            background: "rgba(6, 7, 20, 0.65)",
            padding: "4px",
            borderRadius: "12px"
          }}
          role="tablist"
        >
          {(
            [
              { id: "login", label: "Email Login", icon: Mail },
              { id: "register", label: "Register", icon: KeyRound },
              { id: "phone", label: "Phone OTP", icon: Phone }
            ] as const
          ).map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              className={`btn ${tab === id ? "btn-teal" : ""}`}
              style={{ minHeight: "36px", padding: "6px 8px", fontSize: "0.8rem" }}
              onClick={() => {
                setTab(id);
                setErrorMsg(null);
                setInfoMsg(null);
              }}
            >
              <Icon size={14} />
              <span>{label}</span>
            </button>
          ))}
        </div>

        {(tab === "login" || tab === "register") && (
          <form onSubmit={handleEmailSubmit} style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
            {tab === "register" && (
              <label className="field-label">
                <span>Display Name</span>
                <input
                  type="text"
                  className="input-control"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="Your name"
                  required
                />
              </label>
            )}
            <label className="field-label">
              <span>Email Address</span>
              <input
                type="email"
                className="input-control"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                required
              />
            </label>
            <label className="field-label">
              <span>Password</span>
              <input
                type="password"
                className="input-control"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Minimum 6 characters"
                minLength={6}
                required
              />
            </label>
            <button
              type="submit"
              className="btn"
              disabled={busy || !diagnostics.configured}
              style={{ width: "100%" }}
            >
              {busy ? "Authenticating..." : tab === "register" ? "Create Account" : "Sign In with Email"}
            </button>
            {tab === "login" && (
              <button
                type="button"
                className="btn"
                style={{ background: "transparent", borderColor: "transparent", fontSize: "0.8rem" }}
                disabled={busy || !diagnostics.configured}
                onClick={handleResetPassword}
              >
                Forgot password? Send reset email
              </button>
            )}
          </form>
        )}

        {tab === "phone" && (
          <div style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
            <form onSubmit={handleSendPhoneOtp} style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
              <label className="field-label">
                <span>Phone Number (with country code)</span>
                <input
                  type="tel"
                  className="input-control"
                  value={phoneNumber}
                  onChange={(e) => setPhoneNumber(e.target.value)}
                  placeholder="+8801XXXXXXXXX"
                  required
                />
              </label>
              <button
                type="submit"
                className="btn"
                disabled={busy || !diagnostics.configured}
              >
                {busy ? "Sending SMS..." : "Send OTP Code"}
              </button>
            </form>

            {otpConfirmation && (
              <form onSubmit={handleConfirmOtp} style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
                <label className="field-label">
                  <span>6-Digit Verification Code</span>
                  <input
                    type="text"
                    className="input-control"
                    value={otpCode}
                    onChange={(e) => setOtpCode(e.target.value)}
                    placeholder="123456"
                    required
                  />
                </label>
                <button type="submit" className="btn btn-teal" disabled={busy}>
                  Verify OTP & Sign In
                </button>
              </form>
            )}
            <div id="myraa-phone-recaptcha" />
          </div>
        )}

        {errorMsg && (
          <div
            role="alert"
            style={{
              padding: "10px 12px",
              borderRadius: "12px",
              background: "rgba(251, 113, 133, 0.14)",
              border: "1px solid rgba(251, 113, 133, 0.45)",
              color: "var(--crimson-error)",
              fontSize: "0.85rem"
            }}
          >
            {errorMsg}
          </div>
        )}

        {infoMsg && (
          <div
            role="status"
            style={{
              padding: "10px 12px",
              borderRadius: "12px",
              background: "rgba(45, 212, 191, 0.12)",
              border: "1px solid rgba(45, 212, 191, 0.4)",
              color: "var(--electric-teal)",
              fontSize: "0.85rem"
            }}
          >
            {infoMsg}
          </div>
        )}

        <div
          style={{
            fontSize: "0.76rem",
            color: "var(--text-muted)",
            textAlign: "center",
            lineHeight: 1.5
          }}
        >
          Firebase Project: <code>{diagnostics.projectId}</code> • App Check:{" "}
          <code>{diagnostics.appCheckProvider}</code>
        </div>
      </div>
    </div>
  );
}
