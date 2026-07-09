import { useState } from "react";
import type { FormEvent } from "react";
import { fetchResume, redeemCode } from "../api";
import type { Resume } from "../api";
import { ResumeView } from "./ResumeView";

type GateState =
  | { phase: "locked"; error?: string }
  | { phase: "unlocking" }
  | { phase: "open"; resume: Resume };

/**
 * Code-gated résumé. The content only ever arrives through the token-gated
 * API after a successful redeem — it is never part of the bundle.
 */
export function GatedResume() {
  const [code, setCode] = useState("");
  const [state, setState] = useState<GateState>({ phase: "locked" });

  async function unlock(event: FormEvent) {
    event.preventDefault();
    const trimmed = code.trim();
    if (!trimmed) return;
    setState({ phase: "unlocking" });
    try {
      const redeemed = await redeemCode(trimmed);
      if (!redeemed.ok || !redeemed.token) {
        setState({
          phase: "locked",
          error: "That code didn’t work — check it and try again.",
        });
        return;
      }
      setState({ phase: "open", resume: await fetchResume(redeemed.token) });
    } catch {
      setState({
        phase: "locked",
        error: "Something went wrong — please try again.",
      });
    }
  }

  if (state.phase === "open") {
    return <ResumeView resume={state.resume} />;
  }

  const busy = state.phase === "unlocking";
  return (
    <section className="gate">
      <div className="gate-card">
        <div className="gate-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="4" y="10.5" width="16" height="10" rx="2.5" />
            <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3" />
            <circle cx="12" cy="15.5" r="1.3" fill="currentColor" stroke="none" />
          </svg>
        </div>
        <h2 className="gate-title">This résumé is private</h2>
        <p className="gate-hint">
          Enter the access code you were given to view it. Sessions expire
          after a short while.
        </p>
        <form className="gate-form" onSubmit={unlock}>
          <label className="gate-label" htmlFor="access-code">
            Access code
          </label>
          <div className="gate-row">
            <input
              id="access-code"
              className="gate-input"
              type="password"
              autoComplete="off"
              placeholder="••••••••"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              disabled={busy}
            />
            <button className="button-primary" type="submit" disabled={busy}>
              {busy ? "Unlocking…" : "Unlock"}
            </button>
          </div>
        </form>
        {state.phase === "locked" && state.error && (
          <p className="gate-error" role="alert">
            {state.error}
          </p>
        )}
      </div>
    </section>
  );
}
