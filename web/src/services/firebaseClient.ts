import { initializeApp, getApps, type FirebaseApp } from "firebase/app";
import {
  browserLocalPersistence,
  getAuth,
  setPersistence,
  type Auth
} from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";
import {
  initializeAppCheck,
  getToken as getAppCheckToken,
  ReCaptchaEnterpriseProvider,
  type AppCheck
} from "firebase/app-check";
import { getAI, getGenerativeModel, GoogleAIBackend } from "firebase/ai";
import {
  getFirebaseWebConfigStatus,
  loadWebRuntimeConfig,
  type MyraaWebRuntimeConfig
} from "../config/firebaseConfig";

export interface FirebaseClientDiagnostics {
  configured: boolean;
  missingConfigKeys: string[];
  projectId: string;
  databaseId: string;
  appCheckEnabled: boolean;
  appCheckProvider: string;
  appCheckLastStatus: string;
  geminiWebAvailable: boolean;
}

let cachedApp: FirebaseApp | null = null;
let cachedAuth: Auth | null = null;
let cachedDb: Firestore | null = null;
let cachedAppCheck: AppCheck | null = null;
let appCheckInitError = "";
let cachedGeminiAvailable = false;

export function getRuntimeConfig(): MyraaWebRuntimeConfig {
  return loadWebRuntimeConfig();
}

export function ensureFirebaseInitialized(): {
  app: FirebaseApp | null;
  auth: Auth | null;
  db: Firestore | null;
  appCheck: AppCheck | null;
  diagnostics: FirebaseClientDiagnostics;
} {
  const cfg = getRuntimeConfig();
  const status = getFirebaseWebConfigStatus(cfg);

  if (!status.ready) {
    return {
      app: null,
      auth: null,
      db: null,
      appCheck: null,
      diagnostics: {
        configured: false,
        missingConfigKeys: status.missingFields,
        projectId: cfg.firebase.projectId,
        databaseId: cfg.firebase.databaseId,
        appCheckEnabled: false,
        appCheckProvider: "ReCaptchaEnterpriseProvider (Waiting for Web App config)",
        appCheckLastStatus: `Missing: ${status.missingFields.join(", ")}`,
        geminiWebAvailable: false
      }
    };
  }

  if (!cachedApp) {
    const existing = getApps();
    cachedApp =
      existing.length > 0
        ? existing[0]
        : initializeApp({
            apiKey: cfg.firebase.apiKey,
            authDomain: cfg.firebase.authDomain,
            projectId: cfg.firebase.projectId,
            storageBucket: cfg.firebase.storageBucket,
            messagingSenderId: cfg.firebase.messagingSenderId,
            appId: cfg.firebase.appId
          });

    cachedAuth = getAuth(cachedApp);
    setPersistence(cachedAuth, browserLocalPersistence).catch(() => {
      // Fallback safely if third-party storage is restricted
    });

    cachedDb = getFirestore(cachedApp, cfg.firebase.databaseId);

    if (cfg.recaptchaEnterpriseSiteKey && typeof window !== "undefined") {
      try {
        cachedAppCheck = initializeAppCheck(cachedApp, {
          provider: new ReCaptchaEnterpriseProvider(cfg.recaptchaEnterpriseSiteKey),
          isTokenAutoRefreshEnabled: true
        });
        appCheckInitError = "";
      } catch (err) {
        appCheckInitError =
          err instanceof Error ? err.message : "Failed to initialize reCAPTCHA Enterprise App Check.";
      }
    }

    try {
      getAI(cachedApp, { backend: new GoogleAIBackend() });
      cachedGeminiAvailable = true;
    } catch {
      cachedGeminiAvailable = false;
    }
  }

  return {
    app: cachedApp,
    auth: cachedAuth,
    db: cachedDb,
    appCheck: cachedAppCheck,
    diagnostics: {
      configured: true,
      missingConfigKeys: [],
      projectId: cfg.firebase.projectId,
      databaseId: cfg.firebase.databaseId,
      appCheckEnabled: Boolean(cachedAppCheck),
      appCheckProvider: cachedAppCheck
        ? "ReCaptchaEnterpriseProvider"
        : "Not initialized",
      appCheckLastStatus: cachedAppCheck
        ? "Initialized (auto-refresh enabled)"
        : appCheckInitError || "reCAPTCHA Enterprise site key missing",
      geminiWebAvailable: cachedGeminiAvailable
    }
  };
}

export async function getFirebaseIdToken(forceRefresh = false): Promise<string | null> {
  const { auth } = ensureFirebaseInitialized();
  const user = auth?.currentUser;
  if (!user) return null;
  try {
    return await user.getIdToken(forceRefresh);
  } catch {
    return null;
  }
}

export async function getWebAppCheckTokenBestEffort(forceRefresh = false): Promise<string | null> {
  const { appCheck } = ensureFirebaseInitialized();
  if (!appCheck) return null;
  try {
    const result = await getAppCheckToken(appCheck, forceRefresh);
    return result?.token || null;
  } catch {
    return null;
  }
}

export async function generateWithFirebaseGeminiWeb(options: {
  modelId?: string;
  systemPrompt?: string;
  prompt: string;
}): Promise<{ text: string; modelId: string; latencyMs: number }> {
  const { app } = ensureFirebaseInitialized();
  if (!app) {
    throw new Error("Firebase Web App is not configured yet.");
  }
  const chosenModel = options.modelId || "gemini-2.5-flash";
  const ai = getAI(app, { backend: new GoogleAIBackend() });
  const model = getGenerativeModel(ai, {
    model: chosenModel,
    systemInstruction: options.systemPrompt || undefined
  });
  const started = performance.now();
  const result = await model.generateContent(options.prompt);
  const text = result.response.text();
  return {
    text,
    modelId: chosenModel,
    latencyMs: Math.max(1, Math.round(performance.now() - started))
  };
}
