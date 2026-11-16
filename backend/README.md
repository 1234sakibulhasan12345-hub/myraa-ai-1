# MYRAA AI SUPER BRAIN — Remote AI Proxy & Android APK Build Server

This directory contains the server-side Node.js (v22+) service responsible for:
1. **Free-Tier Multi-AI Provider Proxy & Encrypted Key Vault (`/v1/providers/status`, `/v1/providers/keys`, `/v1/ai/plan`, `/v1/ai/generate`, `/v1/ai/stream`)**:
   - Integrates **7 verified Free-tier providers** (`groq`, `openrouter_free`, `mistral`, `cohere`, `cloudflare_ai`, `nvidia_nim`, `sambanova`) alongside legacy paid proxy adapters (`openai_gpt`, `anthropic_claude`, `deepseek`, `meta_llama`).
   - Supports per-user **AES-256-GCM encrypted server-side key vault** (`POST /v1/providers/keys`, `GET /v1/providers/keys`, `DELETE /v1/providers/keys/:providerId`) so users can manage keys from **Backend & API Settings** without ever exposing keys in the APK, logs, or Firestore.
   - Verifies Firebase Auth ID tokens + App Check before servicing requests, classifies upstream failures honestly (`MISSING_API_KEY`, `INVALID_API_KEY`, `RATE_LIMITED`, `QUOTA_EXPIRED`, `OFFLINE`, `UNAVAILABLE`), and supports automatic cross-provider fallback across live free models.
2. **Isolated Linux Android APK Build Worker (`/v1/builds`, `/v1/builds/:jobId`, `/v1/artifacts/:jobId/apk`)**:
   - Validates paths against traversal, strips restricted Play permissions, and scans `.gradle` / `.gradle.kts` scripts against dangerous execution primitives.
   - Runs `gradle :app:assembleDebug` (or `:app:assembleRelease` when server signing keys are configured) inside an ephemeral sandbox directory with all AI API keys and `MYRAA_KEY_VAULT_SECRET` stripped from child environment variables.
   - Verifies resulting `.apk` ZIP headers, `AndroidManifest.xml`, `apksigner verify`, and SHA-256 checksum before serving a time-limited binary download.

3. **Automatic Firebase App Check Debug Token Registrar (`/v1/appcheck/debug-tokens/register`, `/v1/appcheck/registrar/status`)**:
   - Registers device-generated App Check Debug UUIDs programmatically via Google's official `projects.apps.debugTokens.create` API using privileged server-side Service Account credentials (`FIREBASE_SERVICE_ACCOUNT_JSON` or `FIREBASE_SERVICE_ACCOUNT_PATH`).
   - Strictly locked to project `gen-lang-client-0645829429` (`514839656668`) and package `com.aistudio.myraa.qxkrmp`, and auto-prunes oldest `[MYRAA-AUTO]` debug tokens when Firebase's 20-token cap is reached.

## Quick Local Run
```bash
cd backend
PORT=8088 FIREBASE_PROJECT_ID=gen-lang-client-0645829429 npm start
```

## Integration Tests (including real Gradle APK compilation)
```bash
cd backend
npm test
```

## Production Deployment (Cloud Run — Project `gen-lang-client-0645829429`)
- Grant your Cloud Run runtime Service Account (or uploaded JSON key) the **Firebase App Check Admin** (`roles/firebaseappcheck.admin`) IAM role in project `gen-lang-client-0645829429`.
- Deploy `backend/` to Cloud Run (requires `gcloud` authenticated on the target GCP project with billing & Cloud Run API enabled):
  ```bash
  gcloud run deploy myraa-backend \
    --source ./backend \
    --project gen-lang-client-0645829429 \
    --region asia-southeast1 \
    --memory 4Gi \
    --cpu 2 \
    --allow-unauthenticated \
    --set-env-vars FIREBASE_PROJECT_ID=gen-lang-client-0645829429,ALLOW_EMULATOR_TOKENS=false
  ```
  - On Cloud Run (`K_SERVICE` set), the registrar automatically uses the attached Cloud Run Service Account via GCP Metadata ADC if `FIREBASE_SERVICE_ACCOUNT_JSON` is omitted — or attach a Secret Manager secret to `FIREBASE_SERVICE_ACCOUNT_JSON` and `MYRAA_KEY_VAULT_SECRET` (`--set-secrets`).
- Paste the resulting `https://...run.app` URL into **Backend & API Settings → Remote Backend Server URL** inside the MYRAA Android app so devices automatically register and verify their App Check Debug Token UUID on launch.

## Free Hosting Deployment (Render Blueprint — `render.yaml`)
- The root `render.yaml` configures a free Render Web Service (`runtime: node`, `rootDir: backend`, `plan: free`, `startCommand: node src/server.mjs`, `healthCheckPath: /health`).
- Note on Render Free Tier scope:
  - **Supported natively (`runtime: node`):** `/health`, automatic App Check debug-token registration (`/v1/appcheck/*`), AES-256-GCM API Key Vault (`/v1/providers/keys`), and all Free-Tier Multi-AI Proxy routes (`/v1/providers/status`, `/v1/ai/*`) with zero external npm dependencies.
  - **Requires Docker + Android SDK (`/v1/builds`):** Remote Gradle APK compilation (`backend/Dockerfile`) needs JDK 21 + Android SDK + >512 MB RAM and is not active in the lightweight `node` free runtime.
- Steps in Render Dashboard:
  1. Push this project to GitHub (`Push to GitHub` from AI Studio Settings).
  2. In Render Dashboard, tap **New → Blueprint** and select the pushed GitHub repository.
  3. Paste your Firebase Service Account JSON (`gen-lang-client-0645829429`) into the prompted `FIREBASE_SERVICE_ACCOUNT_JSON` secret (`MYRAA_KEY_VAULT_SECRET` is auto-generated).
  4. After Render shows **Live**, copy the `https://...onrender.com` URL and paste it into **MYRAA → Backend & API Settings → Remote Backend Server URL** (tap **Save Backend URL** → **Test Connection**).

