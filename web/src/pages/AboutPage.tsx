import { Cpu, Globe, Lock, Server, Smartphone } from "lucide-react";
import { NeuralOrb } from "../components/NeuralOrb";
import {
  DEFAULT_BACKEND_URL,
  DEFAULT_FIREBASE_PROJECT_ID,
  DEFAULT_FIRESTORE_DATABASE_ID
} from "../config/firebaseConfig";

export function AboutPage() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px", maxWidth: "960px" }}>
      <section className="glass-card" style={{ display: "flex", alignItems: "center", gap: "20px", flexWrap: "wrap" }}>
        <NeuralOrb size={108} mood="idle" />
        <div style={{ flex: "1 1 280px" }}>
          <span className="badge badge-purple">MYRAA AI • WEB PLATFORM</span>
          <h2 style={{ margin: "8px 0 4px" }}>MYRAA AI — Your Personal AI Intelligence</h2>
          <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.9rem" }}>
            Companion web interface built additively alongside MYRAA AI SUPER BRAIN Android (`com.aistudio.myraa.qxkrmp`).
            Designed for Android Chrome, Desktop Chrome, Firefox, Edge, and Safari with installable PWA support.
          </p>
        </div>
      </section>

      <section className="grid-cards">
        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <Lock size={18} style={{ color: "var(--electric-teal)" }} />
            <strong>Zero-Exposure Security</strong>
          </div>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            API keys, Firebase Admin SDK service accounts, and user credentials never touch browser bundle outputs, logs, or client `localStorage`. All provider keys reside in the AES-256-GCM encrypted server vault.
          </p>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <Server size={18} style={{ color: "var(--cyber-blue)" }} />
            <strong>Connected Render Backend</strong>
          </div>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            Routes SSE streaming chat, model fallback, and provider diagnostics through{" "}
            <code>{DEFAULT_BACKEND_URL}</code> using Firebase ID tokens and Web App Check.
          </p>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <Cpu size={18} style={{ color: "var(--neon-purple)" }} />
            <strong>Shared Firebase Project</strong>
          </div>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            Shares Firebase Auth project <code>{DEFAULT_FIREBASE_PROJECT_ID}</code> and named Firestore database{" "}
            <code>{DEFAULT_FIRESTORE_DATABASE_ID}</code> seamlessly with MYRAA Android.
          </p>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <Globe size={18} style={{ color: "var(--cyber-blue)" }} />
            <strong>Multilingual & PWA Ready</strong>
          </div>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            Supports Bengali, English, Banglish, Hindi, and Hinglish conversations plus installable progressive web app support and browser voice interaction.
          </p>
        </div>

        <div className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <Smartphone size={18} style={{ color: "var(--amber-signal)" }} />
            <strong>Honest Mobile vs. Web Scope</strong>
          </div>
          <p style={{ margin: 0, fontSize: "0.85rem", color: "var(--text-secondary)" }}>
            OS-level Android features (always-on wake word, accessibility automation, SOS hardware triggers, TV/PC bridge) strictly require the MYRAA Android application.
          </p>
        </div>
      </section>
    </div>
  );
}
