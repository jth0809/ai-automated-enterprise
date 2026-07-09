import { useCallback, useEffect, useState } from "react";
import { StatusCard } from "./StatusCard";
import type { BackendStatus } from "./StatusCard";

type FetchState =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; data: BackendStatus };

export function StatusPanel() {
  const [state, setState] = useState<FetchState>({ phase: "loading" });

  const load = useCallback(async () => {
    setState({ phase: "loading" });
    try {
      const res = await fetch("/api/status");
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as BackendStatus;
      setState({ phase: "ready", data });
    } catch (e) {
      setState({
        phase: "error",
        message: e instanceof Error ? e.message : "unknown error",
      });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="status">
      {state.phase === "loading" && <p className="muted">Loading…</p>}
      {state.phase === "error" && (
        <p className="error">Backend unreachable: {state.message}</p>
      )}
      {state.phase === "ready" && <StatusCard status={state.data} />}
      <button className="button-ghost" onClick={() => void load()}>
        Refresh
      </button>
    </section>
  );
}
