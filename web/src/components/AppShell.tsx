import { useState } from "react";
import {
  Activity,
  Bot,
  ChevronLeft,
  ChevronRight,
  Cpu,
  History,
  Home,
  Info,
  LogOut,
  Menu,
  Mic,
  Server,
  Settings,
  User,
  X
} from "lucide-react";
import type { AuthenticatedUserView, NavPageId } from "../types/myraa";

export interface NavItem {
  id: NavPageId;
  label: string;
  icon: React.ComponentType<{ size?: number }>;
}

export const NAV_ITEMS: NavItem[] = [
  { id: "dashboard", label: "Home Dashboard", icon: Home },
  { id: "chat", label: "AI Chat", icon: Bot },
  { id: "voice", label: "Voice Assistant", icon: Mic },
  { id: "providers", label: "AI Providers", icon: Server },
  { id: "models", label: "Model Management", icon: Cpu },
  { id: "history", label: "Conversation History", icon: History },
  { id: "profile", label: "User Profile", icon: User },
  { id: "settings", label: "Settings", icon: Settings },
  { id: "diagnostics", label: "Backend Diagnostics", icon: Activity },
  { id: "about", label: "About MYRAA", icon: Info }
];

interface AppShellProps {
  activePage: NavPageId;
  onSelectPage: (page: NavPageId) => void;
  user: AuthenticatedUserView;
  backendHealthy: boolean | null;
  connectedProviderCount: number;
  onSignOut: () => void;
  children: React.ReactNode;
}

export function AppShell({
  activePage,
  onSelectPage,
  user,
  backendHealthy,
  connectedProviderCount,
  onSignOut,
  children
}: AppShellProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  const activeNav = NAV_ITEMS.find((n) => n.id === activePage) || NAV_ITEMS[0];

  const handleNavClick = (id: NavPageId) => {
    onSelectPage(id);
    setMobileOpen(false);
  };

  return (
    <div className="myraa-shell">
      {mobileOpen && (
        <div
          className="myraa-sidebar-backdrop"
          onClick={() => setMobileOpen(false)}
          aria-hidden="true"
        />
      )}

      <aside
        className={`myraa-sidebar ${collapsed ? "collapsed" : ""} ${
          mobileOpen ? "mobile-open" : ""
        }`}
        aria-label="Primary Navigation"
      >
        <div className="myraa-brand-header">
          <div style={{ display: "flex", alignItems: "center", gap: "10px", minWidth: 0 }}>
            <img
              src="/icon.svg"
              alt="MYRAA AI Logo"
              width={36}
              height={36}
              style={{ borderRadius: "10px", flexShrink: 0 }}
            />
            <div className="myraa-brand-title">
              <strong>MYRAA AI</strong>
              <span>Your Personal AI Intelligence</span>
            </div>
          </div>
          <button
            type="button"
            className="btn btn-icon desktop-only"
            onClick={() => setCollapsed((v) => !v)}
            aria-label={collapsed ? "Expand navigation sidebar" : "Collapse navigation sidebar"}
            title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          >
            {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
          </button>
          <button
            type="button"
            className="btn btn-icon mobile-only"
            onClick={() => setMobileOpen(false)}
            aria-label="Close navigation menu"
          >
            <X size={18} />
          </button>
        </div>

        <nav className="myraa-nav-list">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const isActive = item.id === activePage;
            return (
              <button
                key={item.id}
                type="button"
                className={`myraa-nav-btn ${isActive ? "active" : ""}`}
                onClick={() => handleNavClick(item.id)}
                aria-current={isActive ? "page" : undefined}
                title={item.label}
              >
                <Icon size={18} />
                <span className="myraa-nav-label">{item.label}</span>
              </button>
            );
          })}
        </nav>

        <div
          style={{
            borderTop: "1px solid var(--border-subtle)",
            paddingTop: "12px",
            display: "flex",
            flexDirection: "column",
            gap: "8px"
          }}
        >
          <button
            type="button"
            className="myraa-nav-btn"
            onClick={onSignOut}
            aria-label="Sign out of MYRAA AI"
          >
            <LogOut size={18} />
            <span className="myraa-nav-label">Sign Out</span>
          </button>
        </div>
      </aside>

      <div className="myraa-main-area">
        <header className="myraa-topbar">
          <div className="myraa-topbar-left">
            <button
              type="button"
              className="btn btn-icon mobile-only"
              onClick={() => setMobileOpen(true)}
              aria-label="Open navigation menu"
            >
              <Menu size={20} />
            </button>
            <h1 style={{ margin: 0, fontSize: "1.05rem", fontWeight: 700 }}>
              {activeNav.label}
            </h1>
          </div>

          <div className="myraa-topbar-right">
            <span
              className={`badge ${
                backendHealthy === true
                  ? "badge-teal"
                  : backendHealthy === false
                  ? "badge-crimson"
                  : "badge-amber"
              }`}
            >
              {backendHealthy === true
                ? "BACKEND ONLINE"
                : backendHealthy === false
                ? "BACKEND OFFLINE"
                : "CHECKING BACKEND"}
            </span>
            <span className="badge badge-blue">
              {connectedProviderCount} PROVIDER{connectedProviderCount === 1 ? "" : "S"} LIVE
            </span>
            <button
              type="button"
              className="btn"
              style={{ minHeight: "36px", padding: "6px 12px" }}
              onClick={() => handleNavClick("profile")}
              aria-label="Open User Profile"
            >
              <User size={15} />
              <span>{user.displayName}</span>
            </button>
          </div>
        </header>

        <main className="myraa-content">{children}</main>
      </div>
    </div>
  );
}
