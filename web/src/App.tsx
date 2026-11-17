import { useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "./components/AppShell";
import {
  DEFAULT_BACKEND_URL,
  normalizeBackendUrl
} from "./config/firebaseConfig";
import { AboutPage } from "./pages/AboutPage";
import { AuthPage } from "./pages/AuthPage";
import { ChatPage } from "./pages/ChatPage";
import { DiagnosticsPage } from "./pages/DiagnosticsPage";
import { HistoryPage } from "./pages/HistoryPage";
import { ModelsPage } from "./pages/ModelsPage";
import { OverviewPage } from "./pages/OverviewPage";
import { ProfilePage } from "./pages/ProfilePage";
import { ProvidersPage } from "./pages/ProvidersPage";
import { SettingsPage } from "./pages/SettingsPage";
import { VoiceAssistantPage } from "./pages/VoiceAssistantPage";
import {
  signOutWeb,
  subscribeAuthState,
  updateUserDisplayNameWeb
} from "./services/authService";
import { backendClient, BackendApiError } from "./services/backendClient";
import {
  ensureFirebaseInitialized,
  generateWithFirebaseGeminiWeb,
  getRuntimeConfig
} from "./services/firebaseClient";
import {
  archiveProjectInFirestore,
  createConversationProjectInFirestore,
  ensureUserWorkspaceInFirestore,
  fetchProjectMessagesFromFirestore,
  recordUsageLogToFirestore,
  saveMessageToFirestore,
  subscribeProjectsInFirestore,
  toFirestoreLanguage,
  updateWorkspaceSettingsInFirestore
} from "./services/firestoreSyncService";
import type {
  AuthenticatedUserView,
  BackendErrorCategory,
  BackendHealthInfo,
  BrainLanguage,
  ChatMessage,
  ConversationProject,
  NavPageId,
  ProviderStatusItem,
  RoutingPolicy,
  UserWorkspaceProfile,
  VaultDiagnosticsInfo
} from "./types/myraa";

function buildLanguageInstruction(language: BrainLanguage): string {
  switch (language) {
    case "BN":
      return "Respond naturally in Bengali (বাংলা) unless code blocks are requested.";
    case "EN":
      return "Respond in clear, concise English.";
    case "BN_EN":
      return "Respond in natural Banglish (Bengali + English mix) matching user tone.";
    case "HI":
      return "Respond naturally in Hindi (हिन्दी).";
    case "HINGLISH":
      return "Respond in natural Hinglish (Hindi + English conversational style).";
    default:
      return "Automatically reply in the same language as the user (Bengali, English, Hindi, Hinglish, or Banglish).";
  }
}

export function App() {
  const initialRuntime = getRuntimeConfig();
  const [user, setUser] = useState<AuthenticatedUserView | null>(null);
  const [authInitialized, setAuthInitialized] = useState(false);
  const [activePage, setActivePage] = useState<NavPageId>("dashboard");

  const [backendUrl, setBackendUrl] = useState<string>(initialRuntime.backendUrl);
  const [workspace, setWorkspace] = useState<UserWorkspaceProfile | null>(null);
  const [language, setLanguage] = useState<BrainLanguage>("AUTO");
  const [routingPolicy, setRoutingPolicy] = useState<RoutingPolicy>("AUTO_BALANCED");

  const [health, setHealth] = useState<BackendHealthInfo | null>(null);
  const [providers, setProviders] = useState<ProviderStatusItem[]>([]);
  const [vaultStatus, setVaultStatus] = useState<VaultDiagnosticsInfo | null>(null);
  const [isRefreshingStatus, setIsRefreshingStatus] = useState(false);

  const [selectedProviderId, setSelectedProviderId] = useState<string>("groq");
  const [selectedModelId, setSelectedModelId] = useState<string>("llama-3.3-70b-versatile");

  const [projects, setProjects] = useState<ConversationProject[]>([]);
  const [activeProjectId, setActiveProjectId] = useState<string>("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isGenerating, setIsGenerating] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const firebaseInfo = ensureFirebaseInitialized();

  const refreshBackendAndProviders = useCallback(
    async (targetUrl: string) => {
      setIsRefreshingStatus(true);
      try {
        const h = await backendClient.checkHealth(targetUrl);
        setHealth(h);
      } catch {
        setHealth(null);
      }

      try {
        const [provRes, keysRes] = await Promise.all([
          backendClient.fetchProviderStatuses(targetUrl, { liveProbe: true }),
          backendClient.fetchProviderKeysSummary(targetUrl).catch(() => null)
        ]);
        setProviders(provRes.providers);
        if (keysRes?.vaultStatus) {
          setVaultStatus(keysRes.vaultStatus);
        }
        const firstConnected = provRes.providers.find((p) => p.connected && p.models.length > 0);
        if (firstConnected) {
          setSelectedProviderId((prev) => {
            const existingConnected = provRes.providers.find(
              (p) => p.providerId === prev && p.connected
            );
            if (existingConnected || prev === "google_gemini") return prev;
            setSelectedModelId(firstConnected.models[0]);
            return firstConnected.providerId;
          });
        } else if (firebaseInfo.diagnostics.geminiWebAvailable) {
          setSelectedProviderId((prev) => (prev ? prev : "google_gemini"));
          setSelectedModelId((prev) => (prev ? prev : "gemini-2.5-flash"));
        }
      } catch {
        // Provider fetch may fail if auth/appcheck or CORS isn't configured yet
      } finally {
        setIsRefreshingStatus(false);
      }
    },
    [firebaseInfo.diagnostics.geminiWebAvailable]
  );

  useEffect(() => {
    const unsub = subscribeAuthState((fbUser) => {
      setUser(fbUser);
      setAuthInitialized(true);
      if (!fbUser) {
        setWorkspace(null);
        setProjects([]);
        setMessages([]);
        setActiveProjectId("");
      }
    });
    return () => unsub();
  }, []);

  useEffect(() => {
    if (!user) return;
    let active = true;

    (async () => {
      try {
        const ws = await ensureUserWorkspaceInFirestore(
          user.uid,
          user.displayName,
          backendUrl || DEFAULT_BACKEND_URL
        );
        if (!active || !ws) return;
        setWorkspace(ws);
        if (ws.preferredLanguage) {
          setLanguage(ws.preferredLanguage);
        }
        if (ws.routingPolicy) {
          setRoutingPolicy(ws.routingPolicy);
        }
        const effectiveBackend = normalizeBackendUrl(ws.backendServiceUrl || backendUrl);
        if (effectiveBackend && effectiveBackend !== backendUrl) {
          setBackendUrl(effectiveBackend);
          if (typeof window !== "undefined") {
            window.localStorage.setItem("myraa_web_backend_url", effectiveBackend);
          }
        }
        if (ws.activeProjectId) {
          setActiveProjectId(ws.activeProjectId);
        }
        void refreshBackendAndProviders(effectiveBackend || DEFAULT_BACKEND_URL);
      } catch {
        void refreshBackendAndProviders(backendUrl);
      }
    })();

    const unsubProjects = subscribeProjectsInFirestore(user.uid, (list) => {
      if (!active) return;
      setProjects(list);
      setActiveProjectId((prev) => {
        if (prev && list.some((p) => p.id === prev)) return prev;
        return list[0]?.id || "";
      });
    });

    return () => {
      active = false;
      unsubProjects();
    };
  }, [user?.uid]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!user || !activeProjectId) return;
    let mounted = true;
    fetchProjectMessagesFromFirestore(user.uid, activeProjectId)
      .then((loaded) => {
        if (mounted) {
          setMessages(loaded);
        }
      })
      .catch(() => {
        // Keep current in-memory transcript on transient error
      });
    return () => {
      mounted = false;
    };
  }, [user?.uid, activeProjectId]);

  const ensureActiveProject = async (titleHint: string): Promise<string> => {
    if (activeProjectId) return activeProjectId;
    if (!user) return "";
    const createdId = await createConversationProjectInFirestore(
      user.uid,
      titleHint.slice(0, 60) || "MYRAA Web Chat",
      language
    );
    if (createdId) {
      setActiveProjectId(createdId);
      await updateWorkspaceSettingsInFirestore(user.uid, {
        activeProjectId: createdId
      }).catch(() => {});
      return createdId;
    }
    return "";
  };

  const executePrompt = async (
    rawPrompt: string,
    options?: { skipUserAppend?: boolean; speakResultOnly?: boolean }
  ): Promise<string> => {
    const clean = rawPrompt.trim();
    if (!clean) return "";

    const projectId = await ensureActiveProject(clean);
    const systemPrompt = [
      "You are MYRAA AI — Your Personal AI Intelligence.",
      buildLanguageInstruction(language),
      `Routing policy preference: ${routingPolicy}.`
    ].join(" ");

    const userMsg: ChatMessage = {
      id: `u_${Date.now()}`,
      role: "user",
      content: clean,
      providerId: selectedProviderId,
      modelId: selectedModelId,
      language,
      createdAtMs: Date.now()
    };

    const assistantId = `a_${Date.now() + 1}`;
    const placeholderAssistant: ChatMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      providerId: selectedProviderId,
      modelId: selectedModelId,
      language,
      createdAtMs: Date.now()
    };

    if (!options?.speakResultOnly) {
      setMessages((prev) =>
        options?.skipUserAppend
          ? [...prev, placeholderAssistant]
          : [...prev, userMsg, placeholderAssistant]
      );
      if (user && projectId && !options?.skipUserAppend) {
        void saveMessageToFirestore(user.uid, projectId, userMsg).catch(() => {});
      }
    }

    setIsGenerating(true);
    const controller = new AbortController();
    abortRef.current = controller;

    try {
      let finalText = "";
      let usedProvider = selectedProviderId;
      let usedModel = selectedModelId;
      let latencyMs = 0;
      let fallbackUsed = false;
      let fallbackFrom: string | null = null;
      let promptTokens = 0;
      let completionTokens = 0;

      if (selectedProviderId === "google_gemini" && firebaseInfo.diagnostics.geminiWebAvailable) {
        const gem = await generateWithFirebaseGeminiWeb({
          modelId: selectedModelId || "gemini-2.5-flash",
          systemPrompt,
          prompt: clean
        });
        finalText = gem.text;
        usedProvider = "google_gemini";
        usedModel = gem.modelId;
        latencyMs = gem.latencyMs;
      } else {
        const res = await backendClient.streamText(backendUrl, {
          providerId: selectedProviderId,
          modelId: selectedModelId,
          systemPrompt,
          prompt: clean,
          allowFallback: true,
          signal: controller.signal,
          onDelta: (_delta, accumulated) => {
            if (!options?.speakResultOnly) {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantId ? { ...m, content: accumulated } : m
                )
              );
            }
          }
        });
        finalText = res.text;
        usedProvider = res.providerId;
        usedModel = res.modelId;
        latencyMs = res.latencyMs;
        fallbackUsed = res.fallbackUsed;
        fallbackFrom = res.fallbackFromProviderId;
        promptTokens = res.promptTokens;
        completionTokens = res.completionTokens;
      }

      const finalMsg: ChatMessage = {
        id: assistantId,
        role: "assistant",
        content: finalText,
        providerId: usedProvider,
        modelId: usedModel,
        language,
        latencyMs,
        promptTokens,
        completionTokens,
        fallbackUsed,
        fallbackFromProviderId: fallbackFrom,
        createdAtMs: Date.now()
      };

      if (!options?.speakResultOnly) {
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantId ? finalMsg : m))
        );
      }

      if (user && projectId) {
        void saveMessageToFirestore(user.uid, projectId, finalMsg).catch(() => {});
        void recordUsageLogToFirestore(user.uid, projectId, {
          providerId: usedProvider,
          modelId: usedModel,
          language,
          inputChars: clean.length,
          outputChars: finalText.length,
          latencyMs,
          succeeded: true,
          promptTokens,
          completionTokens
        }).catch(() => {});
      }

      return finalText;
    } catch (err) {
      const aborted = err instanceof DOMException && err.name === "AbortError";
      const category: BackendErrorCategory =
        err instanceof BackendApiError ? err.errorCategory : "PROVIDER_UNAVAILABLE";
      const message = aborted
        ? "Generation stopped by user."
        : err instanceof Error
        ? err.message
        : "Failed to generate response.";

      if (!options?.speakResultOnly) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? {
                  ...m,
                  content: aborted ? m.content || "_Stopped._" : "",
                  error: aborted ? undefined : { category, message }
                }
              : m
          )
        );
      }

      if (user && projectId && !aborted) {
        void recordUsageLogToFirestore(user.uid, projectId, {
          providerId: selectedProviderId,
          modelId: selectedModelId,
          language,
          inputChars: clean.length,
          outputChars: 0,
          latencyMs: 0,
          succeeded: false
        }).catch(() => {});
      }
      if (!options?.speakResultOnly) {
        return "";
      }
      throw err;
    } finally {
      abortRef.current = null;
      setIsGenerating(false);
    }
  };

  const handleStopGeneration = () => {
    abortRef.current?.abort();
  };

  const handleRegenerateLast = () => {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (!lastUser) return;
    void executePrompt(lastUser.content, { skipUserAppend: true });
  };

  const handleNewChat = async () => {
    setMessages([]);
    if (!user) return;
    const newId = await createConversationProjectInFirestore(
      user.uid,
      `Web Chat ${new Date().toLocaleTimeString()}`,
      language
    );
    if (newId) {
      setActiveProjectId(newId);
      await updateWorkspaceSettingsInFirestore(user.uid, {
        activeProjectId: newId
      }).catch(() => {});
    }
  };

  const handleSaveProviderKey = async (
    providerId: string,
    apiKey: string,
    accountId?: string
  ) => {
    const res = await backendClient.saveProviderKey(backendUrl, {
      providerId,
      apiKey,
      accountId
    });
    setProviders((prev) => {
      const idx = prev.findIndex((p) => p.providerId === providerId);
      if (idx === -1) return [...prev, res.provider];
      const next = [...prev];
      next[idx] = res.provider;
      return next;
    });
    return {
      saved: res.saved,
      verified: res.verified,
      message: res.provider.message,
      category: (res.provider.errorCategory || "NONE") as BackendErrorCategory
    };
  };

  const handleDeleteProviderKey = async (providerId: string) => {
    const res = await backendClient.deleteProviderKey(backendUrl, providerId);
    setProviders((prev) =>
      prev.map((p) => (p.providerId === providerId ? res.provider : p))
    );
  };

  const handleSaveBackendUrl = async (newUrl: string) => {
    const clean = normalizeBackendUrl(newUrl);
    setBackendUrl(clean);
    if (typeof window !== "undefined") {
      window.localStorage.setItem("myraa_web_backend_url", clean);
    }
    if (user) {
      await updateWorkspaceSettingsInFirestore(user.uid, {
        backendServiceUrl: clean
      }).catch(() => {});
    }
    await refreshBackendAndProviders(clean);
  };

  const handleChangeLanguage = (lang: BrainLanguage) => {
    setLanguage(lang);
    if (user) {
      void updateWorkspaceSettingsInFirestore(user.uid, {
        preferredLanguage: toFirestoreLanguage(lang)
      }).catch(() => {});
    }
  };

  const handleChangeRoutingPolicy = (policy: RoutingPolicy) => {
    setRoutingPolicy(policy);
    if (user) {
      void updateWorkspaceSettingsInFirestore(user.uid, {
        routingPolicy: policy
      }).catch(() => {});
    }
  };

  if (!authInitialized) {
    return (
      <div
        style={{
          minHeight: "100vh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "var(--text-secondary)"
        }}
      >
        Initializing MYRAA AI Intelligence...
      </div>
    );
  }

  if (!user) {
    return (
      <AuthPage
        diagnostics={firebaseInfo.diagnostics}
        onAuthenticated={(u) => setUser(u)}
      />
    );
  }

  const connectedCount = providers.filter((p) => p.connected).length;

  return (
    <AppShell
      activePage={activePage}
      onSelectPage={setActivePage}
      user={user}
      backendHealthy={health ? health.status === "ok" : false}
      connectedProviderCount={connectedCount}
      onSignOut={() => void signOutWeb()}
    >
      {activePage === "dashboard" && (
        <OverviewPage
          user={user}
          health={health}
          providers={providers}
          projects={projects}
          activeProjectId={activeProjectId}
          androidApkUrl={initialRuntime.androidApkUrl}
          onNavigate={setActivePage}
          onRefreshStatus={() => void refreshBackendAndProviders(backendUrl)}
          isRefreshing={isRefreshingStatus}
        />
      )}

      {activePage === "chat" && (
        <ChatPage
          messages={messages}
          providers={providers}
          selectedProviderId={selectedProviderId}
          selectedModelId={selectedModelId}
          language={language}
          geminiWebAvailable={firebaseInfo.diagnostics.geminiWebAvailable}
          isGenerating={isGenerating}
          onSelectProvider={(pid, mid) => {
            setSelectedProviderId(pid);
            setSelectedModelId(mid);
          }}
          onSelectLanguage={handleChangeLanguage}
          onSendPrompt={(prompt) => void executePrompt(prompt)}
          onRegenerateLast={handleRegenerateLast}
          onStopGeneration={handleStopGeneration}
          onNewChat={() => void handleNewChat()}
        />
      )}

      {activePage === "voice" && (
        <VoiceAssistantPage
          onRunPrompt={(spoken) =>
            executePrompt(spoken, { speakResultOnly: true })
          }
        />
      )}

      {activePage === "providers" && (
        <ProvidersPage
          providers={providers}
          vaultStatus={vaultStatus}
          isLoading={isRefreshingStatus}
          onRefresh={() => void refreshBackendAndProviders(backendUrl)}
          onSaveKey={handleSaveProviderKey}
          onDeleteKey={handleDeleteProviderKey}
        />
      )}

      {activePage === "models" && (
        <ModelsPage
          backendUrl={backendUrl}
          providers={providers}
          selectedProviderId={selectedProviderId}
          selectedModelId={selectedModelId}
          routingPolicy={routingPolicy}
          onSelectDefaultModel={(pid, mid) => {
            setSelectedProviderId(pid);
            setSelectedModelId(mid);
          }}
          onChangeRoutingPolicy={handleChangeRoutingPolicy}
          onGoToChat={() => setActivePage("chat")}
        />
      )}

      {activePage === "history" && (
        <HistoryPage
          projects={projects}
          activeProjectId={activeProjectId}
          activeMessages={messages}
          onSelectProject={(pid) => {
            setActiveProjectId(pid);
            setActivePage("chat");
            void updateWorkspaceSettingsInFirestore(user.uid, {
              activeProjectId: pid
            }).catch(() => {});
          }}
          onCreateProject={async (title) => {
            const newId = await createConversationProjectInFirestore(
              user.uid,
              title,
              language
            );
            if (newId) {
              setActiveProjectId(newId);
              setMessages([]);
              setActivePage("chat");
            }
          }}
          onArchiveProject={(pid) => archiveProjectInFirestore(user.uid, pid)}
        />
      )}

      {activePage === "profile" && (
        <ProfilePage
          user={user}
          workspace={workspace}
          onUpdateDisplayName={async (newName) => {
            const updated = await updateUserDisplayNameWeb(newName);
            setUser(updated);
            await updateWorkspaceSettingsInFirestore(user.uid, {
              displayName: updated.displayName
            }).catch(() => {});
          }}
          onSignOut={() => signOutWeb()}
        />
      )}

      {activePage === "settings" && (
        <SettingsPage
          backendUrl={backendUrl}
          language={language}
          routingPolicy={routingPolicy}
          androidApkUrl={initialRuntime.androidApkUrl}
          onSaveBackendUrl={handleSaveBackendUrl}
          onChangeLanguage={handleChangeLanguage}
          onChangeRoutingPolicy={handleChangeRoutingPolicy}
        />
      )}

      {activePage === "diagnostics" && (
        <DiagnosticsPage
          backendUrl={backendUrl}
          health={health}
          vaultDiagnostics={vaultStatus}
          onHealthUpdated={setHealth}
        />
      )}

      {activePage === "about" && <AboutPage />}
    </AppShell>
  );
}

export default App;
