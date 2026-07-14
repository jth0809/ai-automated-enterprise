import { useEffect, useState, type FormEvent } from "react";
import { getOpsOverview, releaseOps, ReviewApiError } from "./api";
import type { OpsOverview } from "./api";

const MAX_RELEASE_REASON = 2000;

interface OpsPanelProps {
  /** When provided, the panel is controlled and loads immediately; otherwise it
   *  renders its own in-memory token field. */
  token?: string;
  onReleased?: () => void;
}

/**
 * Token-gated circuit-breaker console: freeze status, latest golden run,
 * control-chart metrics, feed deadman summary, and the human release control.
 * The admin token stays in the fetch header and React memory only — never in
 * storage, URLs, logs, or the DOM.
 */
export function OpsPanel({ token: controlledToken, onReleased }: OpsPanelProps) {
  const controlled = controlledToken !== undefined;
  const [ownToken, setOwnToken] = useState("");
  const token = controlled ? (controlledToken as string) : ownToken;

  const [ops, setOps] = useState<OpsOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [releasing, setReleasing] = useState(false);
  const [releaseError, setReleaseError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function load() {
    if (token.length === 0) return;
    setLoading(true);
    setError(null);
    try {
      setOps(await getOpsOverview(token));
    } catch (caught) {
      setOps(null);
      setError(
        caught instanceof ReviewApiError && caught.status === 401
          ? "Unauthorized: check the admin token."
          : "Failed to load the operations overview.",
      );
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (controlled) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [controlledToken]);

  async function submitRelease(event: FormEvent) {
    event.preventDefault();
    const trimmed = reason.trim();
    if (trimmed.length === 0 || trimmed.length > MAX_RELEASE_REASON) return;
    setReleasing(true);
    setReleaseError(null);
    setNotice(null);
    try {
      await releaseOps(token, trimmed);
      setReason("");
      setNotice("Freeze released; state updates resumed.");
      await load();
      onReleased?.();
    } catch (caught) {
      setReleaseError(
        caught instanceof ReviewApiError && caught.status === 409
          ? "State is not frozen, or it changed. Reload before releasing."
          : caught instanceof ReviewApiError && caught.status === 401
            ? "Unauthorized: check the admin token."
            : caught instanceof ReviewApiError && caught.status === 400
              ? "A release reason of 1..2000 characters is required."
              : "Release failed. Try again.",
      );
    } finally {
      setReleasing(false);
    }
  }

  return (
    <section className="ops-panel" aria-label="Operations">
      {!controlled && (
        <form
          className="ops-token"
          onSubmit={event => {
            event.preventDefault();
            void load();
          }}
        >
          <label>
            Ops admin token
            <input
              type="password"
              value={ownToken}
              onChange={event => setOwnToken(event.target.value)}
              autoComplete="off"
            />
          </label>
          <button type="submit" disabled={loading || ownToken.length === 0}>
            Load operations
          </button>
        </form>
      )}

      {loading && ops === null && <p className="ops-loading">Loading…</p>}
      {error && <p className="ops-error">{error}</p>}

      {ops !== null && (
        <>
          <header className="ops-status">
            <span
              className={
                ops.frozen ? "ops-badge ops-badge-frozen" : "ops-badge ops-badge-active"
              }
            >
              {ops.frozen ? "Frozen" : "Active"}
            </span>
            {ops.frozen && ops.freezeReason && (
              <span className="ops-freeze-reason">{ops.freezeReason}</span>
            )}
            {ops.frozen && ops.freezeTrigger && (
              <span className="ops-freeze-trigger">{ops.freezeTrigger}</span>
            )}
            {ops.frozen && ops.freezeAt && (
              <time className="ops-freeze-at" dateTime={ops.freezeAt}>
                {ops.freezeAt}
              </time>
            )}
          </header>

          {notice && <p className="ops-notice">{notice}</p>}

          {ops.latestGolden && (
            <div className="ops-golden">
              <span className="ops-golden-label">Latest golden run</span>
              <span
                className={`ops-mode ops-mode-${ops.latestGolden.mode.toLowerCase()}`}
              >
                {ops.latestGolden.mode}
              </span>
              <span className="ops-golden-status">{ops.latestGolden.status}</span>
              {ops.latestGolden.agreement !== null && (
                <span className="ops-agreement">
                  {Math.round(ops.latestGolden.agreement * 100)}% agreement
                </span>
              )}
              <span className="ops-golden-counts">
                {ops.latestGolden.matchedCount}/{ops.latestGolden.totalCount} matched
              </span>
            </div>
          )}

          {ops.controlMetrics.length > 0 && (
            <ul className="ops-metrics" aria-label="Control metrics">
              {ops.controlMetrics.map(metric => (
                <li
                  key={`${metric.metricCode}-${metric.metricDate}`}
                  className={
                    metric.violation ? "ops-metric ops-metric-violation" : "ops-metric"
                  }
                >
                  <span className="ops-metric-code">{metric.metricCode}</span>
                  <span className="ops-metric-status">{metric.status}</span>
                  <span className="ops-metric-value">{metric.value.toFixed(2)}</span>
                </li>
              ))}
            </ul>
          )}

          {ops.deadman && (
            <p className={`ops-deadman ops-deadman-${ops.deadman.status.toLowerCase()}`}>
              Feed watch: {ops.deadman.status} · {ops.deadman.alertCount}/
              {ops.deadman.feedCount} alerting
            </p>
          )}

          {ops.frozen && (
            <form className="ops-release" onSubmit={submitRelease}>
              <label>
                Release reason
                <input
                  type="text"
                  value={reason}
                  maxLength={MAX_RELEASE_REASON}
                  onChange={event => setReason(event.target.value)}
                  autoComplete="off"
                />
              </label>
              <button type="submit" disabled={releasing || reason.trim().length === 0}>
                Release freeze
              </button>
              {releaseError && <p className="ops-error">{releaseError}</p>}
            </form>
          )}
        </>
      )}
    </section>
  );
}
