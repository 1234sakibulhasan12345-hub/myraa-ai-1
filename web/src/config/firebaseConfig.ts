export interface MyraaWebRuntimeConfig {
  backendUrl: string;
  androidApkUrl: string;
  recaptchaEnterpriseSiteKey: string;
  firebase: {
    apiKey: string;
    authDomain: string;
    projectId: string;
    storageBucket: string;
    messagingSenderId: string;
    appId: string;
    databaseId: string;
  };
}

export const DEFAULT_BACKEND_URL = "https://myraa-super-brain-backend.onrender.com";
export const DEFAULT_FIREBASE_PROJECT_ID = "gen-lang-client-0645829429";
export const DEFAULT_FIREBASE_AUTH_DOMAIN = "gen-lang-client-0645829429.firebaseapp.com";
export const DEFAULT_FIREBASE_STORAGE_BUCKET = "gen-lang-client-0645829429.firebasestorage.app";
export const DEFAULT_FIREBASE_SENDER_ID = "514839656668";
export const DEFAULT_FIRESTORE_DATABASE_ID =
  "ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6";
export const DEFAULT_RECAPTCHA_ENTERPRISE_SITE_KEY =
  "6LfIxhouAAAAAMQICWv6vV65q5IRonntyE0V1BFs";

export function normalizeBackendUrl(raw: string): string {
  return String(raw || "").trim().replace(/\/+$/, "");
}

export function validateBackendUrl(raw: string): string | null {
  const cleaned = normalizeBackendUrl(raw);
  if (!cleaned) return "Backend Server URL is required.";
  if (/\s/.test(cleaned)) return "Backend Server URL must not contain spaces.";
  try {
    const parsed = new URL(cleaned);
    if (parsed.username || parsed.password) {
      return "Do not include credentials inside the Backend Server URL.";
    }
    const isLocalHost =
      parsed.hostname === "localhost" ||
      parsed.hostname === "127.0.0.1" ||
      parsed.hostname === "10.0.2.2";
    if (parsed.protocol === "https:") return null;
    if (parsed.protocol === "http:" && isLocalHost) return null;
    return "Production backend URLs must use HTTPS.";
  } catch {
    return "Enter a valid URL such as https://myraa-super-brain-backend.onrender.com";
  }
}

export function loadWebRuntimeConfig(): MyraaWebRuntimeConfig {
  const env = import.meta.env;
  const savedBackendOverride =
    typeof window !== "undefined" ? window.localStorage.getItem("myraa_web_backend_url") : null;

  const backendCandidate = normalizeBackendUrl(
    savedBackendOverride || env.VITE_MYRAA_BACKEND_URL || DEFAULT_BACKEND_URL
  );

  return {
    backendUrl: backendCandidate || DEFAULT_BACKEND_URL,
    androidApkUrl: String(env.VITE_ANDROID_APK_URL || "").trim(),
    recaptchaEnterpriseSiteKey: String(
      env.VITE_RECAPTCHA_ENTERPRISE_SITE_KEY || DEFAULT_RECAPTCHA_ENTERPRISE_SITE_KEY
    ).trim(),
    firebase: {
      apiKey: String(env.VITE_FIREBASE_API_KEY || "").trim(),
      authDomain: String(env.VITE_FIREBASE_AUTH_DOMAIN || DEFAULT_FIREBASE_AUTH_DOMAIN).trim(),
      projectId: String(env.VITE_FIREBASE_PROJECT_ID || DEFAULT_FIREBASE_PROJECT_ID).trim(),
      storageBucket: String(
        env.VITE_FIREBASE_STORAGE_BUCKET || DEFAULT_FIREBASE_STORAGE_BUCKET
      ).trim(),
      messagingSenderId: String(
        env.VITE_FIREBASE_MESSAGING_SENDER_ID || DEFAULT_FIREBASE_SENDER_ID
      ).trim(),
      appId: String(env.VITE_FIREBASE_APP_ID || "").trim(),
      databaseId: String(
        env.VITE_FIREBASE_DATABASE_ID || DEFAULT_FIRESTORE_DATABASE_ID
      ).trim()
    }
  };
}

export function getFirebaseWebConfigStatus(cfg: MyraaWebRuntimeConfig): {
  ready: boolean;
  missingFields: string[];
  appCheckReady: boolean;
} {
  const missing: string[] = [];
  if (!cfg.firebase.apiKey) missing.push("VITE_FIREBASE_API_KEY");
  if (!cfg.firebase.appId) missing.push("VITE_FIREBASE_APP_ID");
  if (!cfg.firebase.projectId) missing.push("VITE_FIREBASE_PROJECT_ID");
  if (!cfg.firebase.authDomain) missing.push("VITE_FIREBASE_AUTH_DOMAIN");

  return {
    ready: missing.length === 0,
    missingFields: missing,
    appCheckReady: Boolean(
      missing.length === 0 && cfg.recaptchaEnterpriseSiteKey
    )
  };
}
