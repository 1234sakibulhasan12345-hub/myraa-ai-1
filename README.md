# MYRAA AI SUPER BRAIN — Android, Backend & Web Platform

**MYRAA AI — Your Personal AI Intelligence** consists of three unified components inside this repository, preserving the existing Firebase project (`gen-lang-client-0645829429`), named Firestore database (`ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6`), and Render backend (`https://myraa-super-brain-backend.onrender.com`):

1. **`app/` — MYRAA Android App (`com.aistudio.myraa.qxkrmp`)**
   - Jetpack Compose Material 3 Android client with Firebase Auth, Firestore sync, Play Integrity App Check, Gemini AI Logic, Encrypted Provider Vault integration, and Android-only system intelligence (background mic/wake word, hands-free overlay, accessibility, SOS, TV/PC remote).
2. **`backend/` — MYRAA Render Node.js Backend**
   - Deployed at `https://myraa-super-brain-backend.onrender.com` (Blueprint: `render.yaml`).
   - Provides Firebase ID Token verification, App Check token verification & automatic Android debug token registration, AES-256-GCM encrypted API Key Vault (`MYRAA_KEY_VAULT_SECRET`), SSE streaming (`/v1/ai/stream`), multi-provider routing/fallback, and strict origin-allowlist CORS (`backend/src/cors.mjs`).
3. **`web/` — MYRAA AI Website (React 19 + TypeScript + Vite PWA)**
   - Responsive dark futuristic web interface alongside the Android app—zero impact on Android code or packages.
   - Includes 10 pages: Home Dashboard, AI Chat (SSE streaming + Markdown/code copy + Bengali/English/Hindi/Hinglish), Voice Assistant (Web Speech API STT/TTS), AI Providers (Encrypted Vault CRUD + 8 error categories + latency), Model Management (routing + side-by-side comparison), Synced Conversation History, User Profile, Settings, Backend Diagnostics, and About MYRAA.

---

## Security & Secret Rules

- **Never** commit `.env`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `MYRAA_KEY_VAULT_SECRET`, or any provider API keys to GitHub or client bundles.
- Keep the GitHub repository **private**.
- Never change `MYRAA_KEY_VAULT_SECRET` on the Render backend service after keys have been stored, or previously encrypted user vault files will become unreadable.

---

## Local Development & Verification

### 1. Backend (`/backend`)

```bash
cd backend
npm test
```

For local web development against a local backend, `backend/src/cors.mjs` automatically permits `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:4173`, and `http://127.0.0.1:4173` when `NODE_ENV !== "production"`.

### 2. Website (`/web`)

```bash
cd web
cp .env.example .env
# Fill in VITE_FIREBASE_API_KEY and VITE_FIREBASE_APP_ID from Firebase Console -> Project Settings -> Your Apps (Web App)
npm install
npm run lint
npm test
npm run build
npm run dev
```

### 3. Android (`/app`)

```bash
firebase emulators:exec --only firestore,auth --project demo-no-project "gradle :app:testDebugUnitTest :app:assembleDebug"
```

---

## Deploying the MYRAA Website to Render Static Site

The website is intentionally separated from the Android APK and backend Node service via `web/render-static-site.yaml`:

1. **Register a Web App in existing Firebase project `gen-lang-client-0645829429`:**
   - Open Firebase Console → Project Settings → **Your apps** → **Add app** → **Web (`</>`)**.
   - Copy `apiKey` (`VITE_FIREBASE_API_KEY`) and `appId` (`VITE_FIREBASE_APP_ID`).
2. **Deploy Static Site on Render:**
   - In Render Dashboard → **New** → **Static Site**, connect your private repo (`main` branch).
   - **Root Directory:** `web`
   - **Build Command:** `npm ci && npm run build`
   - **Publish Directory:** `dist`
   - **Rewrite Rule:** `/*` → `/index.html` (Rewrite)
   - Set environment variables from `web/.env.example` (`VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_APP_ID`, `VITE_MYRAA_BACKEND_URL=https://myraa-super-brain-backend.onrender.com`, `VITE_FIREBASE_AUTH_DOMAIN=gen-lang-client-0645829429.firebaseapp.com`, `VITE_FIREBASE_PROJECT_ID=gen-lang-client-0645829429`, `VITE_FIREBASE_STORAGE_BUCKET=gen-lang-client-0645829429.firebasestorage.app`, `VITE_FIREBASE_MESSAGING_SENDER_ID=514839656668`, `VITE_FIREBASE_DATABASE_ID=ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6`, `VITE_RECAPTCHA_ENTERPRISE_SITE_KEY=6LfIxhouAAAAAMQICWv6vV65q5IRonntyE0V1BFs`).
3. **Configure Authorized Domain & Backend CORS:**
   - Copy your deployed Render Static Site origin (for example `https://myraa-ai-web.onrender.com`).
   - Add that hostname to **Firebase Console → Authentication → Settings → Authorized domains**.
   - Add the deployed site domain under **Firebase Console → App Check** (reCAPTCHA Enterprise).
   - In Render Dashboard for **`myraa-super-brain-backend`**, add environment variable:
     - `ALLOWED_WEB_ORIGINS=https://your-deployed-web-site.onrender.com`
   - Note: Never use wildcard `*` for `ALLOWED_WEB_ORIGINS` when Bearer tokens are forwarded.
