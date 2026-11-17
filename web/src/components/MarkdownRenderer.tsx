import { Fragment, useState } from "react";
import { Check, Copy } from "lucide-react";

interface MarkdownRendererProps {
  content: string;
}

interface TextSegment {
  type: "text" | "code";
  lang?: string;
  value: string;
}

function splitFencedCodeBlocks(raw: string): TextSegment[] {
  const segments: TextSegment[] = [];
  const regex = /```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = regex.exec(raw)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ type: "text", value: raw.slice(lastIndex, match.index) });
    }
    segments.push({
      type: "code",
      lang: match[1] || "text",
      value: match[2].replace(/\n$/, "")
    });
    lastIndex = regex.lastIndex;
  }

  if (lastIndex < raw.length) {
    segments.push({ type: "text", value: raw.slice(lastIndex) });
  }
  return segments;
}

function renderInlineFormatting(line: string): React.ReactNode[] {
  const tokens = line.split(/(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\(https?:\/\/[^)]+\))/g);
  return tokens.map((tok, idx) => {
    if (tok.startsWith("`") && tok.endsWith("`") && tok.length >= 2) {
      return (
        <code key={idx} className="inline-code">
          {tok.slice(1, -1)}
        </code>
      );
    }
    if (tok.startsWith("**") && tok.endsWith("**") && tok.length >= 4) {
      return <strong key={idx}>{tok.slice(2, -2)}</strong>;
    }
    const linkMatch = tok.match(/^\[([^\]]+)\]\((https?:\/\/[^)]+)\)$/);
    if (linkMatch) {
      return (
        <a key={idx} href={linkMatch[2]} target="_blank" rel="noopener noreferrer">
          {linkMatch[1]}
        </a>
      );
    }
    return <Fragment key={idx}>{tok}</Fragment>;
  });
}

function CodeBlockView({ lang, code }: { lang: string; code: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="code-block-shell">
      <div className="code-block-header">
        <span>{lang || "code"}</span>
        <button
          type="button"
          className="btn btn-icon"
          style={{ minHeight: "28px", minWidth: "28px", padding: "4px 8px", fontSize: "0.75rem" }}
          onClick={handleCopy}
          aria-label={`Copy ${lang || "code"} snippet`}
        >
          {copied ? <Check size={14} /> : <Copy size={14} />}
          <span>{copied ? "Copied" : "Copy"}</span>
        </button>
      </div>
      <pre className="code-block-pre">
        <code>{code}</code>
      </pre>
    </div>
  );
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const segments = splitFencedCodeBlocks(content || "");

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
      {segments.map((seg, idx) => {
        if (seg.type === "code") {
          return <CodeBlockView key={idx} lang={seg.lang || "code"} code={seg.value} />;
        }
        const lines = seg.value.split("\n");
        return (
          <div key={idx} style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
            {lines.map((line, lIdx) => {
              const trimmed = line.trim();
              if (!trimmed) return <div key={lIdx} style={{ height: "4px" }} />;
              if (trimmed.startsWith("### ")) {
                return (
                  <h4 key={lIdx} style={{ margin: "6px 0 2px", color: "var(--cyber-blue)" }}>
                    {renderInlineFormatting(trimmed.slice(4))}
                  </h4>
                );
              }
              if (trimmed.startsWith("## ")) {
                return (
                  <h3 key={lIdx} style={{ margin: "8px 0 2px", color: "var(--text-primary)" }}>
                    {renderInlineFormatting(trimmed.slice(3))}
                  </h3>
                );
              }
              if (trimmed.startsWith("# ")) {
                return (
                  <h2 key={lIdx} style={{ margin: "8px 0 2px", color: "var(--text-primary)" }}>
                    {renderInlineFormatting(trimmed.slice(2))}
                  </h2>
                );
              }
              if (/^[-*•]\s+/.test(trimmed)) {
                return (
                  <div key={lIdx} style={{ display: "flex", gap: "8px", paddingLeft: "6px" }}>
                    <span style={{ color: "var(--electric-teal)" }}>•</span>
                    <span>{renderInlineFormatting(trimmed.replace(/^[-*•]\s+/, ""))}</span>
                  </div>
                );
              }
              if (/^\d+\.\s+/.test(trimmed)) {
                const numMatch = trimmed.match(/^(\d+\.)\s+(.*)$/);
                return (
                  <div key={lIdx} style={{ display: "flex", gap: "8px", paddingLeft: "6px" }}>
                    <span style={{ color: "var(--neon-purple)", fontWeight: 700 }}>
                      {numMatch?.[1]}
                    </span>
                    <span>{renderInlineFormatting(numMatch?.[2] || trimmed)}</span>
                  </div>
                );
              }
              return (
                <p key={lIdx} style={{ margin: 0 }}>
                  {renderInlineFormatting(line)}
                </p>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}
