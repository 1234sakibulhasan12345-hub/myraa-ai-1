import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "../components/AppShell";
import { AboutPage } from "../pages/AboutPage";
import { AuthPage } from "../pages/AuthPage";
import { ChatPage } from "../pages/ChatPage";
import { DiagnosticsPage } from "../pages/DiagnosticsPage";
import { HistoryPage } from "../pages/HistoryPage";
import { ModelsPage } from "../pages/ModelsPage";
import { OverviewPage } from "../pages/OverviewPage";
import { ProfilePage } from "../pages/ProfilePage";
import { ProvidersPage } from "../pages/ProvidersPage";
import { SettingsPage } from "../pages/SettingsPage";
import { VoiceAssistantPage } from "../pages/VoiceAssistantPage";
import type {
  AuthenticatedUserView,
  ChatMessage,
  NavPageId,
  ProviderStatusItem
} from "../types/myraa";

afterEach(() => {
  cleanup();
});

const SAMPLE_USER: AuthenticatedUserView = {
  uid: "user_123",
  displayName: "Sakibul",
  email: "sakibul@example.com",
  phoneNumber: "",
  photoURL: "",
  emailVerified: true,
  providerIds: ["google.com"]
};

const SAMPLE_PROVIDERS: ProviderStatusItem[] = [
  {
    providerId: "groq",
    displayName: "Groq",
    freeTier: true,
    connected: true,
    statusCode: "CONNECTED",
    errorCategory: "NONE",
    keyConfigured: true,
    keySource: "USER_VAULT",
    maskedKeyHint: "gsk_...abcd",
    requiresAccountId: false,
    hasAccountId: false,
    latencyMs: 142,
    lastCheckedEpochMs: 1700000000000,
    message: "Verified against Groq API",
    models: ["llama-3.3-70b-versatile"]
  },
  {
    providerId: "openrouter_free",
    displayName: "OpenRouter Free Models",
    freeTier: true,
    connected: false,
    statusCode: "INVALID_API_KEY",
    errorCategory: "INVALID_API_KEY",
    keyConfigured: false,
    keySource: "NONE",
    maskedKeyHint: "",
    requiresAccountId: false,
    hasAccountId: false,
    message: "Provider rejected API key",
    models: []
  }
];

describe("MYRAA AI Web UI Pages", () => {
  it("renders AuthPage with Google, Email Login, Register, Phone OTP, and missing config banner", () => {
    render(
      <AuthPage
        diagnostics={{
          configured: false,
          missingConfigKeys: ["VITE_FIREBASE_API_KEY", "VITE_FIREBASE_APP_ID"],
          projectId: "gen-lang-client-0645829429",
          databaseId: "ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6",
          appCheckEnabled: false,
          appCheckProvider: "ReCaptchaEnterpriseProvider (Waiting for Web App config)",
          appCheckLastStatus: "Missing keys",
          geminiWebAvailable: false
        }}
        onAuthenticated={vi.fn()}
      />
    );

    expect(screen.getByText("MYRAA AI")).toBeInTheDocument();
    expect(screen.getByText(/Firebase Web App Config Needed/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Continue with Google Sign-In/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: /Phone OTP/i }));
    expect(screen.getByPlaceholderText("+8801XXXXXXXXX")).toBeInTheDocument();
  });

  it("renders AppShell navigation for all 10 MYRAA Web pages", () => {
    const onSelect = vi.fn();
    render(
      <AppShell
        activePage={"dashboard" as NavPageId}
        onSelectPage={onSelect}
        user={SAMPLE_USER}
        backendHealthy={true}
        connectedProviderCount={1}
        onSignOut={vi.fn()}
      >
        <div>Dashboard Child</div>
      </AppShell>
    );

    const expectedPages = [
      "Home Dashboard",
      "AI Chat",
      "Voice Assistant",
      "AI Providers",
      "Model Management",
      "Conversation History",
      "User Profile",
      "Settings",
      "Backend Diagnostics",
      "About MYRAA"
    ];

    for (const label of expectedPages) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("button", { name: "AI Chat" }));
    expect(onSelect).toHaveBeenCalledWith("chat");
  });

  it("renders ChatPage markdown code block, copy button, and triggers prompt submission", () => {
    const onSend = vi.fn();
    const messages: ChatMessage[] = [
      {
        id: "m1",
        role: "user",
        content: "Show Kotlin sample",
        providerId: "groq",
        modelId: "llama-3.3-70b-versatile",
        language: "AUTO",
        createdAtMs: Date.now()
      },
      {
        id: "m2",
        role: "assistant",
        content: "Here is a snippet:\n```kotlin\nfun greet() = println(\"MYRAA\")\n```",
        providerId: "groq",
        modelId: "llama-3.3-70b-versatile",
        language: "AUTO",
        latencyMs: 120,
        createdAtMs: Date.now()
      }
    ];

    render(
      <ChatPage
        messages={messages}
        providers={SAMPLE_PROVIDERS}
        selectedProviderId="groq"
        selectedModelId="llama-3.3-70b-versatile"
        language="AUTO"
        geminiWebAvailable={true}
        isGenerating={false}
        onSelectProvider={vi.fn()}
        onSelectLanguage={vi.fn()}
        onSendPrompt={onSend}
        onRegenerateLast={vi.fn()}
        onStopGeneration={vi.fn()}
        onNewChat={vi.fn()}
      />
    );

    expect(screen.getByText(/fun greet\(\)/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Copy kotlin snippet/i })).toBeInTheDocument();

    const input = screen.getByLabelText("Chat message input");
    fireEvent.change(input, { target: { value: "বাংলায় উত্তর দাও" } });
    fireEvent.click(screen.getByRole("button", { name: "Send message" }));
    expect(onSend).toHaveBeenCalledWith("বাংলায় উত্তর দাও");
  });

  it("renders ProvidersPage showing connected status, error category, latency, and vault save action", () => {
    const onSaveKey = vi.fn(async () => ({
      saved: true,
      verified: true,
      message: "Verified",
      category: "NONE" as const
    }));

    render(
      <ProvidersPage
        providers={SAMPLE_PROVIDERS}
        vaultStatus={{
          masterKeySource: "MYRAA_KEY_VAULT_SECRET",
          persistentMasterKey: true,
          vaultDirConfigured: true
        }}
        isLoading={false}
        onRefresh={vi.fn()}
        onSaveKey={onSaveKey}
        onDeleteKey={vi.fn()}
      />
    );

    expect(screen.getByText("Groq")).toBeInTheDocument();
    expect(screen.getByText("OpenRouter Free Models")).toBeInTheDocument();
    expect(screen.getAllByText("INVALID_API_KEY").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/142ms/)).toBeInTheDocument();
  });

  it("renders VoiceAssistantPage with honest Android-only background mic notice", () => {
    render(<VoiceAssistantPage onRunPrompt={vi.fn(async () => "Reply")} />);
    expect(
      screen.getByText(/This feature requires the MYRAA Android application\./i)
    ).toBeInTheDocument();
  });

  it("renders Overview, Models, History, Profile, Settings, Diagnostics, and About pages", () => {
    render(
      <OverviewPage
        user={SAMPLE_USER}
        health={{
          status: "ok",
          service: "myraa-backend",
          appCheckAutoRegistrarConfigured: true,
          corsAllowlistCount: 1,
          timestampEpochMs: Date.now(),
          latencyMs: 42
        }}
        providers={SAMPLE_PROVIDERS}
        projects={[]}
        activeProjectId=""
        androidApkUrl=""
        onNavigate={vi.fn()}
        onRefreshStatus={vi.fn()}
        isRefreshing={false}
      />
    );
    expect(screen.getByText(/Welcome back, Sakibul/i)).toBeInTheDocument();
    cleanup();

    render(
      <ModelsPage
        backendUrl="https://myraa-super-brain-backend.onrender.com"
        providers={SAMPLE_PROVIDERS}
        selectedProviderId="groq"
        selectedModelId="llama-3.3-70b-versatile"
        routingPolicy="AUTO_BALANCED"
        onSelectDefaultModel={vi.fn()}
        onChangeRoutingPolicy={vi.fn()}
        onGoToChat={vi.fn()}
      />
    );
    expect(screen.getByText(/Side-by-Side Model Comparison/i)).toBeInTheDocument();
    cleanup();

    render(
      <HistoryPage
        projects={[
          {
            id: "proj_1",
            userId: "user_123",
            name: "Android Architecture",
            targetFramework: "ANDROID_COMPOSE",
            requirementsSummary: "Multi-AI session",
            detectedLanguage: "AUTO",
            architectureSummary: "",
            status: "ACTIVE",
            updatedAtMs: Date.now(),
            syncedToFirestore: true
          }
        ]}
        activeProjectId="proj_1"
        activeMessages={[]}
        onSelectProject={vi.fn()}
        onCreateProject={vi.fn(async () => {})}
        onArchiveProject={vi.fn(async () => {})}
      />
    );
    expect(screen.getByText("Android Architecture")).toBeInTheDocument();
    cleanup();

    render(
      <ProfilePage
        user={SAMPLE_USER}
        workspace={null}
        onUpdateDisplayName={vi.fn(async () => {})}
        onSignOut={vi.fn(async () => {})}
      />
    );
    expect(screen.getByText("user_123")).toBeInTheDocument();
    cleanup();

    render(
      <SettingsPage
        backendUrl="https://myraa-super-brain-backend.onrender.com"
        language="AUTO"
        routingPolicy="AUTO_BALANCED"
        androidApkUrl=""
        onSaveBackendUrl={vi.fn(async () => {})}
        onChangeLanguage={vi.fn()}
        onChangeRoutingPolicy={vi.fn()}
      />
    );
    expect(screen.getByText(/MYRAA Render Backend HTTPS URL/i)).toBeInTheDocument();
    cleanup();

    render(
      <DiagnosticsPage
        backendUrl="https://myraa-super-brain-backend.onrender.com"
        health={null}
        vaultDiagnostics={null}
        onHealthUpdated={vi.fn()}
      />
    );
    expect(screen.getByText(/Backend, Auth & App Check Diagnostics/i)).toBeInTheDocument();
    cleanup();

    render(<AboutPage />);
    expect(screen.getByText(/Zero-Exposure Security/i)).toBeInTheDocument();
  });
});
