import { useMemo, useState } from "react";
import { Archive, Download, History, MessageSquare, Plus, Search } from "lucide-react";
import type { ChatMessage, ConversationProject } from "../types/myraa";

interface HistoryPageProps {
  projects: ConversationProject[];
  activeProjectId: string;
  activeMessages: ChatMessage[];
  onSelectProject: (projectId: string) => void;
  onCreateProject: (name: string) => Promise<void>;
  onArchiveProject: (projectId: string) => Promise<void>;
}

export function HistoryPage({
  projects,
  activeProjectId,
  activeMessages,
  onSelectProject,
  onCreateProject,
  onArchiveProject
}: HistoryPageProps) {
  const [searchQuery, setSearchQuery] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [creating, setCreating] = useState(false);

  const filteredProjects = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return projects.filter((p) => {
      if (!q) return true;
      return (
        p.name.toLowerCase().includes(q) ||
        p.requirementsSummary.toLowerCase().includes(q) ||
        p.status.toLowerCase().includes(q)
      );
    });
  }, [projects, searchQuery]);

  const activeProject = projects.find((p) => p.id === activeProjectId);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    setCreating(true);
    try {
      await onCreateProject(newTitle.trim());
      setNewTitle("");
    } finally {
      setCreating(false);
    }
  };

  const handleExportMarkdown = () => {
    const title = activeProject?.name || "MYRAA AI Conversation";
    const lines = [
      `# ${title}`,
      `Exported: ${new Date().toISOString()}`,
      ""
    ];
    for (const msg of activeMessages) {
      lines.push(
        `## ${msg.role === "user" ? "User" : `MYRAA (${msg.providerId} / ${msg.modelId})`}`
      );
      lines.push(msg.content);
      lines.push("");
    }
    const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${title.toLowerCase().replace(/[^a-z0-9]+/g, "-") || "myraa-chat"}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "18px" }}>
      <section className="glass-card" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" }}>
          <div>
            <h2 style={{ margin: 0 }}>Synced Conversation History</h2>
            <p style={{ margin: "4px 0 0", color: "var(--text-secondary)", fontSize: "0.86rem" }}>
              Sessions stored in Firestore database <code>ai-studio-android-myraaai-...</code> sync across MYRAA Web and MYRAA Android.
            </p>
          </div>
          <button
            type="button"
            className="btn"
            onClick={handleExportMarkdown}
            disabled={activeMessages.length === 0}
          >
            <Download size={16} />
            <span>Export Active Chat (.md)</span>
          </button>
        </div>

        <form
          onSubmit={handleCreate}
          style={{ display: "flex", flexWrap: "wrap", gap: "10px", alignItems: "center" }}
        >
          <input
            type="text"
            className="input-control"
            style={{ flex: "1 1 240px" }}
            placeholder="New session title (e.g., Render Deployment Debug)"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            aria-label="New conversation session title"
          />
          <button type="submit" className="btn btn-primary" disabled={creating || !newTitle.trim()}>
            <Plus size={16} />
            <span>{creating ? "Creating..." : "Create Session"}</span>
          </button>
        </form>

        <div style={{ position: "relative" }}>
          <Search
            size={16}
            style={{
              position: "absolute",
              left: "12px",
              top: "50%",
              transform: "translateY(-50%)",
              color: "var(--text-secondary)"
            }}
          />
          <input
            type="search"
            className="input-control"
            style={{ paddingLeft: "36px" }}
            placeholder="Search conversation sessions..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            aria-label="Search conversation sessions"
          />
        </div>
      </section>

      <section className="grid-cards">
        {filteredProjects.length === 0 ? (
          <div className="glass-card" style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <History size={20} style={{ color: "var(--text-secondary)" }} />
            <span style={{ color: "var(--text-secondary)" }}>
              No conversation sessions match your filter yet. Start a chat to sync automatically.
            </span>
          </div>
        ) : (
          filteredProjects.map((project) => {
            const isActive = project.id === activeProjectId;
            return (
              <article
                key={project.id}
                className={`glass-card ${isActive ? "glass-card-highlight" : ""}`}
                style={{ display: "flex", flexDirection: "column", gap: "10px" }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: "8px" }}>
                  <div>
                    <strong style={{ fontSize: "1rem" }}>{project.name}</strong>
                    <div style={{ fontSize: "0.78rem", color: "var(--text-secondary)", marginTop: "2px" }}>
                      {project.targetFramework} • {project.detectedLanguage} • Updated{" "}
                      {new Date(project.updatedAtMs).toLocaleString()}
                    </div>
                  </div>
                  <span
                    className={`badge ${
                      project.status === "ARCHIVED"
                        ? "badge-amber"
                        : isActive
                        ? "badge-teal"
                        : "badge-blue"
                    }`}
                  >
                    {isActive ? "ACTIVE" : project.status}
                  </span>
                </div>

                <p style={{ margin: 0, fontSize: "0.84rem", color: "var(--text-secondary)" }}>
                  {project.requirementsSummary || "Synced MYRAA conversation workspace."}
                </p>

                <div style={{ display: "flex", flexWrap: "wrap", gap: "8px", marginTop: "auto" }}>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => onSelectProject(project.id)}
                  >
                    <MessageSquare size={15} />
                    <span>Open in Chat</span>
                  </button>
                  {project.status !== "ARCHIVED" && (
                    <button
                      type="button"
                      className="btn"
                      onClick={() => onArchiveProject(project.id)}
                      aria-label={`Archive ${project.name}`}
                    >
                      <Archive size={15} />
                      <span>Archive</span>
                    </button>
                  )}
                </div>
              </article>
            );
          })
        )}
      </section>
    </div>
  );
}
