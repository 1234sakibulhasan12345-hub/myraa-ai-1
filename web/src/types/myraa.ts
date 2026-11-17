export type NavPageId =
  | "dashboard"
  | "chat"
  | "voice"
  | "providers"
  | "models"
  | "history"
  | "profile"
  | "settings"
  | "diagnostics"
  | "about";

export type BrainLanguage = "AUTO" | "EN" | "BN" | "BN_EN" | "HI" | "HINGLISH";
export type FirestoreLanguage = "AUTO" | "EN" | "BN" | "BN_EN";
export type RoutingPolicy = "AUTO_BALANCED" | "QUALITY_DEFAULT" | "ULTRA_FAST";

export type BackendErrorCategory =
  | "INVALID_API_KEY"
  | "FIREBASE_AUTH_REQUIRED"
  | "APP_CHECK_REQUIRED"
  | "CONFIGURATION_ERROR"
  | "PROVIDER_UNAVAILABLE"
  | "NETWORK_ERROR"
  | "RATE_LIMITED"
  | "QUOTA_EXPIRED"
  | "NONE"
  | "MISSING_API_KEY";

export interface ProviderStatusItem {
  providerId: string;
  displayName: string;
  freeTier: boolean;
  connected: boolean;
  statusCode: string;
  errorCategory: BackendErrorCategory | string;
  keyConfigured: boolean;
  keySource: "USER_VAULT" | "SERVER_ENV" | "NONE" | "VAULT_ERROR" | string;
  maskedKeyHint: string;
  requiresAccountId: boolean;
  hasAccountId: boolean;
  latencyMs?: number;
  lastCheckedEpochMs?: number;
  message: string;
  models: string[];
}

export interface ProviderKeySummaryItem {
  providerId: string;
  displayName: string;
  keyConfigured: boolean;
  keySource: string;
  maskedKeyHint: string;
  requiresAccountId: boolean;
  hasAccountId: boolean;
  statusCode: string;
  errorCategory: string;
}

export interface VaultDiagnosticsInfo {
  masterKeySource: string;
  persistentMasterKey: boolean;
  vaultDirConfigured: boolean;
}

export interface BackendHealthInfo {
  status: string;
  service: string;
  appCheckAutoRegistrarConfigured: boolean;
  corsAllowlistCount?: number;
  timestampEpochMs: number;
  latencyMs: number;
}

export interface AppCheckRegistrarStatus {
  configured: boolean;
  credentialMode: string;
  projectId: string;
  androidPackage: string;
  reason: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  providerId: string;
  modelId: string;
  language: BrainLanguage;
  latencyMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  fallbackUsed?: boolean;
  fallbackFromProviderId?: string | null;
  createdAtMs: number;
  error?: {
    category: BackendErrorCategory;
    message: string;
  };
}

export interface ConversationProject {
  id: string;
  userId: string;
  name: string;
  targetFramework: "ANDROID_COMPOSE" | "WEB_APP" | "BACKEND_API" | "FULLSTACK_SYSTEM";
  requirementsSummary: string;
  detectedLanguage: FirestoreLanguage;
  architectureSummary: string;
  status: "PLANNING" | "ACTIVE" | "PAUSED" | "ARCHIVED";
  updatedAtMs: number;
  syncedToFirestore: boolean;
}

export interface UserWorkspaceProfile {
  userId: string;
  displayName: string;
  preferredLanguage: FirestoreLanguage;
  routingPolicy: RoutingPolicy;
  activeProjectId?: string | null;
  backendServiceUrl?: string | null;
  preferredBuildType?: "DEBUG" | "RELEASE";
  defaultPackagePrefix?: string;
}

export interface AuthenticatedUserView {
  uid: string;
  displayName: string;
  email: string;
  phoneNumber: string;
  photoURL: string;
  emailVerified: boolean;
  providerIds: string[];
}

export interface AndroidOnlyCapability {
  id: string;
  title: string;
  description: string;
  notice: string;
}

export const ANDROID_ONLY_CAPABILITIES: AndroidOnlyCapability[] = [
  {
    id: "bg_mic",
    title: "Background Microphone & Wake Word Service",
    description: "Always-on foreground/background audio capture when the screen is off.",
    notice: "This feature requires the MYRAA Android application."
  },
  {
    id: "hands_free",
    title: "Hands-Free System Overlay Assistant",
    description: "Global floating overlay across third-party apps on Android.",
    notice: "This feature requires the MYRAA Android application."
  },
  {
    id: "accessibility",
    title: "Accessibility & Screen Awareness",
    description: "Reading UI hierarchy and automating on-screen actions safely.",
    notice: "This feature requires the MYRAA Android application."
  },
  {
    id: "sos",
    title: "Emergency SOS Automation",
    description: "Hardware button triggers, emergency SMS/location dispatch.",
    notice: "This feature requires the MYRAA Android application."
  },
  {
    id: "device_controls",
    title: "Phone, Google TV & PC Remote Controls",
    description: "Native intents, ADB/Cast/LAN device bridge and system settings toggles.",
    notice: "This feature requires the MYRAA Android application."
  }
];
