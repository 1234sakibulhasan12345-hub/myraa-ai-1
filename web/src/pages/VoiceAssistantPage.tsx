import { useEffect, useRef, useState } from "react";
import { AlertCircle, Mic, MicOff, Smartphone, Volume2, VolumeX } from "lucide-react";
import { NeuralOrb, type OrbMood } from "../components/NeuralOrb";
import { MarkdownRenderer } from "../components/MarkdownRenderer";

interface BrowserSpeechRecognitionAlternative {
  transcript: string;
}

interface BrowserSpeechRecognitionResult {
  isFinal: boolean;
  0: BrowserSpeechRecognitionAlternative;
}

interface BrowserSpeechRecognitionEvent {
  results: ArrayLike<BrowserSpeechRecognitionResult>;
}

interface BrowserSpeechRecognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onstart: (() => void) | null;
  onend: (() => void) | null;
  onerror: ((ev: { error?: string }) => void) | null;
  onresult: ((ev: BrowserSpeechRecognitionEvent) => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}

type SpeechRecognitionCtor = new () => BrowserSpeechRecognition;

interface VoiceAssistantPageProps {
  onRunPrompt: (prompt: string) => Promise<string>;
}

type MicPermissionState = "unknown" | "granted" | "denied" | "unsupported";

export function VoiceAssistantPage({ onRunPrompt }: VoiceAssistantPageProps) {
  const [voiceLang, setVoiceLang] = useState("en-US");
  const [autoSpeak, setAutoSpeak] = useState(true);
  const [micPermission, setMicPermission] = useState<MicPermissionState>("unknown");
  const [isListening, setIsListening] = useState(false);
  const [isThinking, setIsThinking] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [replyText, setReplyText] = useState("");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const recognitionRef = useRef<BrowserSpeechRecognition | null>(null);

  const SpeechCtor: SpeechRecognitionCtor | null =
    typeof window !== "undefined"
      ? ((window as unknown as { SpeechRecognition?: SpeechRecognitionCtor; webkitSpeechRecognition?: SpeechRecognitionCtor })
          .SpeechRecognition ||
          (window as unknown as { webkitSpeechRecognition?: SpeechRecognitionCtor })
            .webkitSpeechRecognition ||
          null)
      : null;

  const ttsSupported =
    typeof window !== "undefined" && "speechSynthesis" in window;
  const sttSupported = Boolean(SpeechCtor);

  useEffect(() => {
    return () => {
      recognitionRef.current?.abort();
      if (ttsSupported) {
        window.speechSynthesis.cancel();
      }
    };
  }, [ttsSupported]);

  const speakText = (text: string) => {
    if (!ttsSupported || !text.trim()) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text.replace(/```[\s\S]*?```/g, "code block omitted."));
    utterance.lang = voiceLang;
    utterance.onstart = () => setIsSpeaking(true);
    utterance.onend = () => setIsSpeaking(false);
    utterance.onerror = () => setIsSpeaking(false);
    window.speechSynthesis.speak(utterance);
  };

  const stopSpeech = () => {
    if (ttsSupported) {
      window.speechSynthesis.cancel();
    }
    setIsSpeaking(false);
  };

  const submitTranscript = async (spoken: string) => {
    const clean = spoken.trim();
    if (!clean) return;
    setIsThinking(true);
    setErrorMsg(null);
    try {
      const answer = await onRunPrompt(clean);
      setReplyText(answer);
      if (autoSpeak) {
        speakText(answer);
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "Voice query failed.");
    } finally {
      setIsThinking(false);
    }
  };

  const handleStartListening = async () => {
    setErrorMsg(null);
    stopSpeech();

    if (!SpeechCtor) {
      setMicPermission("unsupported");
      setErrorMsg(
        "Speech-to-Text (Web Speech API) is not supported in this browser. Try Desktop/Android Chrome or Edge, or type your prompt below."
      );
      return;
    }

    if (typeof navigator !== "undefined" && navigator.mediaDevices?.getUserMedia) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((t) => t.stop());
        setMicPermission("granted");
      } catch {
        setMicPermission("denied");
        setErrorMsg("Microphone permission denied. Allow microphone access in browser site settings.");
        return;
      }
    }

    const recognition = new SpeechCtor();
    recognition.lang = voiceLang;
    recognition.continuous = false;
    recognition.interimResults = true;

    let finalTranscript = "";
    recognition.onstart = () => setIsListening(true);
    recognition.onresult = (ev) => {
      let combined = "";
      for (let i = 0; i < ev.results.length; i++) {
        combined += ev.results[i][0]?.transcript || "";
        if (ev.results[i].isFinal) {
          finalTranscript = combined;
        }
      }
      setTranscript(combined);
    };
    recognition.onerror = (ev) => {
      setIsListening(false);
      setErrorMsg(`Voice input error: ${ev.error || "microphone interrupted"}`);
    };
    recognition.onend = () => {
      setIsListening(false);
      const chosen = finalTranscript.trim();
      if (chosen) {
        void submitTranscript(chosen);
      }
    };

    recognitionRef.current = recognition;
    recognition.start();
  };

  const handleStopListening = () => {
    recognitionRef.current?.stop();
    setIsListening(false);
  };

  const orbMood: OrbMood = errorMsg
    ? "error"
    : isListening
    ? "listening"
    : isThinking
    ? "thinking"
    : isSpeaking
    ? "speaking"
    : "idle";

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px", maxWidth: "900px", margin: "0 auto" }}>
      <section
        className="glass-card"
        style={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          textAlign: "center",
          gap: "14px",
          padding: "28px 20px"
        }}
      >
        <NeuralOrb size={148} mood={orbMood} label={`Voice state: ${orbMood}`} />

        <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", justifyContent: "center" }}>
          <span className={`badge ${sttSupported ? "badge-teal" : "badge-amber"}`}>
            STT: {sttSupported ? "Supported" : "Unsupported"}
          </span>
          <span className={`badge ${ttsSupported ? "badge-blue" : "badge-amber"}`}>
            TTS: {ttsSupported ? "Supported" : "Unsupported"}
          </span>
          <span className="badge badge-purple">
            Mic Permission: {micPermission.toUpperCase()}
          </span>
        </div>

        <h2 style={{ margin: 0 }}>
          {isListening
            ? "Listening..."
            : isThinking
            ? "MYRAA AI is thinking..."
            : isSpeaking
            ? "Speaking response..."
            : "MYRAA Browser Voice Assistant"}
        </h2>

        <div style={{ display: "flex", flexWrap: "wrap", gap: "10px", justifyContent: "center", alignItems: "center" }}>
          <select
            aria-label="Voice language"
            className="select-control"
            style={{ width: "auto" }}
            value={voiceLang}
            onChange={(e) => setVoiceLang(e.target.value)}
          >
            <option value="en-US">English (US)</option>
            <option value="bn-BD">বাংলা (Bangladesh)</option>
            <option value="bn-IN">বাংলা (India)</option>
            <option value="hi-IN">हिन्दी (India)</option>
          </select>

          <label style={{ display: "inline-flex", alignItems: "center", gap: "6px", fontSize: "0.86rem" }}>
            <input
              type="checkbox"
              checked={autoSpeak}
              onChange={(e) => setAutoSpeak(e.target.checked)}
            />
            <span>Auto-speak replies</span>
          </label>
        </div>

        <div style={{ display: "flex", flexWrap: "wrap", gap: "10px", justifyContent: "center" }}>
          {!isListening ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleStartListening}
              disabled={isThinking}
            >
              <Mic size={18} />
              <span>Start Voice Input</span>
            </button>
          ) : (
            <button type="button" className="btn btn-danger" onClick={handleStopListening}>
              <MicOff size={18} />
              <span>Stop Listening</span>
            </button>
          )}

          {isSpeaking && (
            <button type="button" className="btn btn-danger" onClick={stopSpeech}>
              <VolumeX size={18} />
              <span>Interrupt & Stop Speech</span>
            </button>
          )}

          {!isSpeaking && replyText && ttsSupported && (
            <button type="button" className="btn" onClick={() => speakText(replyText)}>
              <Volume2 size={18} />
              <span>Replay Voice</span>
            </button>
          )}
        </div>
      </section>

      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submitTranscript(transcript);
          }}
          style={{ display: "flex", gap: "10px", flexWrap: "wrap" }}
        >
          <input
            type="text"
            className="input-control"
            style={{ flex: "1 1 260px" }}
            value={transcript}
            onChange={(e) => setTranscript(e.target.value)}
            placeholder="Voice transcript appears here (or type to test voice reply)..."
            aria-label="Voice transcript input"
          />
          <button
            type="submit"
            className="btn btn-teal"
            disabled={isThinking || !transcript.trim()}
          >
            {isThinking ? "Processing..." : "Ask MYRAA"}
          </button>
        </form>

        {errorMsg && (
          <div
            role="alert"
            style={{
              padding: "10px 12px",
              borderRadius: "10px",
              background: "rgba(251, 113, 133, 0.14)",
              border: "1px solid rgba(251, 113, 133, 0.45)",
              color: "var(--crimson-error)",
              display: "flex",
              alignItems: "center",
              gap: "8px",
              fontSize: "0.86rem"
            }}
          >
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        {replyText && (
          <div
            style={{
              padding: "14px",
              borderRadius: "12px",
              background: "rgba(6, 7, 20, 0.65)",
              border: "1px solid var(--border-subtle)"
            }}
          >
            <MarkdownRenderer content={replyText} />
          </div>
        )}
      </section>

      <div
        className="glass-card"
        style={{
          borderColor: "rgba(251, 191, 36, 0.35)",
          display: "flex",
          gap: "12px",
          alignItems: "flex-start"
        }}
      >
        <Smartphone size={20} style={{ color: "var(--amber-signal)", flexShrink: 0, marginTop: "2px" }} />
        <div style={{ fontSize: "0.85rem", color: "var(--text-secondary)" }}>
          <strong style={{ color: "var(--amber-signal)" }}>Browser Microphone Scope:</strong>{" "}
          This browser voice assistant works only while the tab is open with user permission. Always-on background microphone wake-word detection and hands-free system overlays are Android-only.{" "}
          <em>This feature requires the MYRAA Android application.</em>
        </div>
      </div>
    </div>
  );
}
