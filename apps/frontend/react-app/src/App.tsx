import { useState } from "react";
import { GatedResume } from "./components/GatedResume";
import { NewsFeed } from "./components/NewsFeed";
import { StatusPanel } from "./components/StatusPanel";

const TABS = [
  { id: "resume", label: "Résumé" },
  { id: "news", label: "News" },
  { id: "status", label: "Status" },
] as const;

type TabId = (typeof TABS)[number]["id"];

export default function App() {
  const [tab, setTab] = useState<TabId>("resume");

  return (
    <div className="shell">
      <header className="masthead">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <div>
            <p className="brand-name">AI Automated Enterprise</p>
            <p className="brand-tagline">Résumé · AI news digest · platform status</p>
          </div>
        </div>
        <nav className="tabs" aria-label="Sections">
          {TABS.map(({ id, label }) => (
            <button
              key={id}
              className={id === tab ? "tab tab-active" : "tab"}
              aria-pressed={id === tab}
              onClick={() => setTab(id)}
            >
              {label}
            </button>
          ))}
        </nav>
      </header>

      <main className="content">
        {tab === "resume" && <GatedResume />}
        {tab === "news" && <NewsFeed />}
        {tab === "status" && <StatusPanel />}
      </main>

      <footer className="footer">
        <p>Deployed via GitOps · canary releases · security-gated pipeline</p>
      </footer>
    </div>
  );
}
