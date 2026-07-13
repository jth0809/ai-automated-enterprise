import type { PillarSummary } from "./api";

interface CountdownProps {
  etaYear: number | null;
  etaLow: number | null;
  etaHigh: number | null;
  label: string;
  pillars?: PillarSummary[];
}

/**
 * Big-number arrival year with its 80% interval and the fixed honesty label.
 * A null overall ETA means the trend does not resolve inside the model's
 * 150-year clamp horizon, rendered as "2175+". When some pillars have resolved
 * ETAs, a context line shows the earliest and how many pillars are unresolved,
 * so the honest "several pillars are far off" story stays readable.
 */
export function Countdown({ etaYear, etaLow, etaHigh, label, pillars }: CountdownProps) {
  const resolved = (pillars ?? [])
    .filter((p) => p.etaYear !== null)
    .sort((a, b) => (a.etaYear as number) - (b.etaYear as number));
  const unresolved = (pillars ?? []).filter((p) => p.etaYear === null).length;

  return (
    <section className="countdown" aria-label="Arrival estimate">
      <p className="countdown-year">{etaYear === null ? "2175+" : Math.round(etaYear)}</p>
      {etaLow !== null && etaHigh !== null && (
        <p className="countdown-interval">
          {Math.round(etaLow)} – {Math.round(etaHigh)}
        </p>
      )}
      {etaYear === null && resolved.length > 0 && (
        <p className="countdown-context">
          가장 이른 필라 {Math.round(resolved[0].etaYear as number)} · 미해결 {unresolved}/
          {(pillars ?? []).length}
        </p>
      )}
      <p className="countdown-label">{label}</p>
    </section>
  );
}
