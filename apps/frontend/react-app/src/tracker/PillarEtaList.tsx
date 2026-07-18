import type { PillarSummary } from "./api";

interface PillarEtaListProps {
  pillars: PillarSummary[];
  etaBottlenecks: number[];
  unresolvedEtaPillars: number[];
}

/**
 * Per-pillar arrival estimates. An unresolved pillar (no forward trend inside
 * the model horizon) shows "2175+", never a fabricated year — this is what
 * keeps the honest overall estimate readable instead of a bare countdown.
 */
export function PillarEtaList({
  pillars,
  etaBottlenecks,
  unresolvedEtaPillars,
}: PillarEtaListProps) {
  const ordered = [...pillars].sort((a, b) => a.pillar - b.pillar);
  const bottlenecks = new Set(etaBottlenecks);
  const unresolved = new Set(unresolvedEtaPillars);
  return (
    <ol className="pillar-eta" aria-label="Per-pillar arrival estimates">
      {ordered.map((pillar) => {
        const isBottleneck = bottlenecks.has(pillar.pillar);
        const isUnresolved = unresolved.has(pillar.pillar);
        const className = [
          "pillar-eta-row",
          isBottleneck ? "pillar-eta-bottleneck" : "",
          isUnresolved ? "pillar-eta-unresolved" : "",
        ].filter(Boolean).join(" ");
        return (
          <li key={pillar.pillar} className={className}>
            <span className="pillar-eta-label">
              <span className="pillar-eta-name">{pillar.name}</span>
              {(isBottleneck || isUnresolved) && (
                <span className="pillar-eta-state">
                  {isBottleneck ? "전체 ETA 병목" : ""}
                  {isBottleneck && isUnresolved ? " · " : ""}
                  {isUnresolved ? "추세 미해결" : ""}
                </span>
              )}
            </span>
            <span className="pillar-eta-readiness">
              {pillar.readiness === null ? "—" : `${Math.round(pillar.readiness * 100)}%`}
            </span>
            <span className="pillar-eta-year">
              {pillar.etaYear === null ? "2175+" : Math.round(pillar.etaYear)}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
