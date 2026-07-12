interface CountdownProps {
  etaYear: number | null;
  etaLow: number | null;
  etaHigh: number | null;
  label: string;
}

/**
 * Big-number arrival year with its 80% interval and the fixed honesty label.
 * A null ETA means the trend does not resolve inside the model's 150-year
 * clamp horizon, rendered as "2175+".
 */
export function Countdown({ etaYear, etaLow, etaHigh, label }: CountdownProps) {
  return (
    <section className="countdown" aria-label="Arrival estimate">
      <p className="countdown-year">{etaYear === null ? "2175+" : Math.round(etaYear)}</p>
      {etaLow !== null && etaHigh !== null && (
        <p className="countdown-interval">
          {Math.round(etaLow)} – {Math.round(etaHigh)}
        </p>
      )}
      <p className="countdown-label">{label}</p>
    </section>
  );
}
