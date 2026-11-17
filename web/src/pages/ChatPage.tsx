import { useEffect, useRef, useState } from "react";
import {
  Check,
  Copy,
  Plus,
  RefreshCw,
  Send,
  Sparkles,
  Square
} from "lucide-react";
import { MarkdownRenderer } from "../components/MarkdownRenderer";
import { NeuralOrb } from "../components/NeuralOrb";
import type { BrainLanguage, ChatMessage, ProviderStatusItem } from "../types/myraa";

interface ChatPageProps {
  messages: ChatMessage[];
  providers: ProviderStatusItem[];
  selectedProviderId: string;
  selectedModelId: string;
  language: BrainLanguage;
  geminiWebAvailable: boolean;
  isGenerating: boolean;
  onSelectProvider: (providerId: string, modelId: string) => void;
  onSelectLanguage: (lang: BrainLanguage) => void;
  onSendPrompt: (prompt: string) => void;
  onRegenerateLast: () => void;
  onStopGeneration: () => void;
  onNewChat: () => void;
}

const LANGUAGE_SYSTEM_LABELS: Record<BrainLanguage, string> = {
  AUTO: "Auto (BN/EN/HI)",
  EN: "English",
  BN: "বাংলা (Bengali)",
  HI: "हिन्दी (Hindi)",
  HINGLISH: "Hinglish",
  BN_EN: "Banglish (BN-EN)"
};

export function ChatPage({
  messages,
  providers,
  selectedProviderId,
  selectedModelId,
  language,
  geminiWebAvailable,
  isGenerating,
  onSelectProvider,
  onSelectLanguage,
  onSendPrompt,
  onRegenerateLast,
  onStopGeneration,
  onNewChat
}: ChatPageProps) {
  const [draft, setDraft] = useState("");
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [messages.length, isGenerating]);

  const activeProvider = providers.find((p) => p.providerId === selectedProviderId);
  const availableModels =
    selectedProviderId === "google_gemini"
      ? ["gemini-2.5-flash", "gemini-2.5-flash-lite"]
      : activeProvider?.models || [selectedModelId].filter(Boolean);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const clean = draft.trim();
    if (!clean || isGenerating) return;
    setDraft("");
    onSendPrompt(clean);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleCopyMessage = async (id: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(id);
      setTimeout(() => setCopiedId((prev) => (prev === id ? null : prev)), 1800);
    } catch {
      // Clipboard API unavailable
    }
  };

  const canRegenerate =
    !isGenerating && messages.some((m) => m.role === "user");

  return (
    <div className="chat-layout">
      {/* Chat Toolbar */}
      <div
        className="glass-card"
        style={{
          padding: "10px 14px",
          display: "flex",
          flexWrap: "wrap",
          gap: "10px",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", flexWrap: "wrap", gap: "8px", alignItems: "center" }}>
          <select
            aria-label="Select AI Provider"
            className="select-control"
            style={{ width: "auto", minWidth: "175px", padding: "7px 10px" }}
            value={selectedProviderId}
            onChange={(e) => {
              const pid = e.target.value;
              if (pid === "google_gemini") {
                onSelectProvider(pid, "gemini-2.5-flash");
              } else {
                const target = providers.find((p) => p.providerId === pid);
                onSelectProvider(pid, target?.models?.[0] || "");
              }
            }}
          >
            {geminiWebAvailable && (
              <option value="google_gemini">Google Gemini (Firebase AI Logic)</option>
            )}
            {providers.map((p) => (
              <option key={p.providerId} value={p.providerId}>
                {p.displayName} {p.connected ? "• LIVE" : `(${p.statusCode})`}
              </option>
            ))}
          </select>

          <select
            aria-label="Select AI Model"
            className="select-control"
            style={{ width: "auto", minWidth: "180px", padding: "7px 10px" }}
            value={selectedModelId}
            onChange={(e) => onSelectProvider(selectedProviderId, e.target.value)}
          >
            {availableModels.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>

          <select
            aria-label="Response Language"
            className="select-control"
            style={{ width: "auto", minWidth: "140px", padding: "7px 10px" }}
            value={language}
            onChange={(e) => onSelectLanguage(e.target.value as BrainLanguage)}
          >
            {(Object.keys(LANGUAGE_SYSTEM_LABELS) as BrainLanguage[]).map((lang) => (
              <option key={lang} value={lang}>
                {LANGUAGE_SYSTEM_LABELS[lang]}
              </option>
            ))}
          </select>
        </div>

        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
          {canRegenerate && (
            <button
              type="button"
              className="btn"
              onClick={onRegenerateLast}
              title="Regenerate last response"
            >
              <RefreshCw size={15} />
              <span>Regenerate</span>
            </button>
          )}
          <button type="button" className="btn btn-teal" onClick={onNewChat}>
            <Plus size={15} />
            <span>New Chat</span>
          </button>
        </div>
      </div>

      {/* Messages Area */}
      <div className="chat-messages" aria-live="polite">
        {messages.length === 0 ? (
          <div
            className="glass-card"
            style={{
              margin: "auto",
              maxWidth: "620px",
              textAlign: "center",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              gap: "12px",
              padding: "28px 20px"
            }}
          >
            <NeuralOrb size={112} mood="idle" />
            <span className="badge badge-purple">
              <Sparkles size={13} /> MYRAA AI SUPER BRAIN CHAT
            </span>
            <h2 style={{ margin: 0 }}>Ask in বাংলা, English, हिन्दी, or Hinglish</h2>
            <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: "0.9rem" }}>
              Streams real-time responses through your encrypted Render backend or Firebase AI Logic.
            </p>
            <div style={{ display: "flex", flexWrap: "wrap", gap: "8px", justifyContent: "center" }}>
              {[
                "বাংলায় একটি Android Kotlin Coroutines গাইড দাও",
                "Explain REST vs GraphQL with TypeScript code examples",
                "Bhai ek fast startup MVP roadmap bana de Hinglish mein"
              ].map((starter) => (
                <button
                  key={starter}
                  type="button"
                  className="btn"
                  style={{ fontSize: "0.8rem" }}
                  onClick={() => onSendPrompt(starter)}
                >
                  {starter}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((msg) => (
            <article
              key={msg.id}
              className={`chat-bubble ${msg.role}`}
              aria-label={msg.role === "user" ? "Your prompt" : "MYRAA AI response"}
            >
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  gap: "8px",
                  marginBottom: "8px",
                  flexWrap: "wrap"
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
                  <strong style={{ fontSize: "0.84rem", color: msg.role === "user" ? "var(--cyber-blue)" : "var(--electric-teal)" }}>
                    {msg.role === "user" ? "You" : "MYRAA AI"}
                  </strong>
                  {msg.role === "assistant" && msg.providerId && (
                    <span className="badge badge-blue">
                      {msg.providerId} {msg.modelId ? `• ${msg.modelId}` : ""}
                    </span>
                  )}
                  {msg.fallbackUsed && msg.fallbackFromProviderId && (
                    <span className="badge badge-amber">
                      Fallback from {msg.fallbackFromProviderId}
                    </span>
                  )}
                  {typeof msg.latencyMs === "number" && (
                    <span style={{ fontSize: "0.75rem", color: "var(--text-muted)" }}>
                      {msg.latencyMs}ms
                    </span>
                  )}
                </div>

                {msg.content && (
                  <button
                    type="button"
                    className="btn btn-icon"
                    style={{ minHeight: "30px", padding: "4px 8px", fontSize: "0.74rem" }}
                    onClick={() => handleCopyMessage(msg.id, msg.content)}
                    aria-label="Copy response"
                  >
                    {copiedId === msg.id ? <Check size={14} /> : <Copy size={14} />}
                    <span>{copiedId === msg.id ? "Copied" : "Copy"}</span>
                  </button>
                )}
              </div>

              {msg.error ? (
                <div
                  role="alert"
                  style={{
                    padding: "10px 12px",
                    borderRadius: "10px",
                    background: "rgba(251, 113, 133, 0.14)",
                    border: "1px solid rgba(251, 113, 133, 0.4)",
                    color: "var(--crimson-error)",
                    fontSize: "0.88rem"
                  }}
                >
                  <div style={{ marginBottom: "6px" }}>
                    <span className="badge badge-crimson">{msg.error.category}</span>
                  </div>
                  <div>{msg.error.message}</div>
                </div>
              ) : msg.role === "user" ? (
                <p style={{ margin: 0, whiteSpace: "pre-wrap" }}>{msg.content}</p>
              ) : (
                <MarkdownRenderer content={msg.content || "..."} />
              )}
            </article>
          ))
        )}
        <div ref={bottomRef} />
      </div>

      {/* Composer */}
      <form
        onSubmit={handleSubmit}
        className="glass-card"
        style={{
          padding: "10px 12px",
          display: "flex",
          gap: "10px",
          alignItems: "flex-end"
        }}
      >
        <textarea
          className="textarea-control"
          rows={2}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Message MYRAA AI in বাংলা, English, हिन्दी, or Hinglish... (Shift+Enter for newline)"
          aria-label="Chat message input"
          style={{ resize: "vertical", minHeight: "48px", maxHeight: "180px" }}
        />
        {isGenerating ? (
          <button
            type="button"
            className="btn btn-danger"
            onClick={onStopGeneration}
            aria-label="Stop generation"
          >
            <Square size={16} />
            <span>Stop</span>
          </button>
        ) : (
          <button
            type="submit"
            className="btn btn-primary"
            disabled={!draft.trim()}
            aria-label="Send message"
          >
            <Send size={16} />
            <span>Send</span>
          </button>
        )}
      </form>
    </div>
  );
}
