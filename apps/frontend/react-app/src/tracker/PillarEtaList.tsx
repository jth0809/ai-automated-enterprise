import type { PillarSummary } from "./api";

interface PillarEtaListProps {
  pillars: PillarSummary[];
  bottleneck: number | null;
}

/**
 * Per-pillar arrival estimates. An unresolved pillar (no forward trend inside
 * the model horizon) shows "2175+", never a fabricated year — this is what
 * keeps the honest overall estimate readable instead of a bare countdown.
 */
export function PillarEtaList({ pillars, bottleneck }: PillarEtaListProps) {
  const ordered = [...pillars].sort((a, b) => a.pillar - b.pillar);
  return (
    <ol className="pillar-eta" aria-label="Per-pillar arrival estimates">
      {ordered.map((pillar) => (
        <li
          key={pillar.pillar}
          className={pillar.pillar === bottleneck ? "pillar-eta-row pillar-eta-bottleneck" : "pillar-eta-row"}
        >
          <span className="pillar-eta-name">{pillar.name}</span>
          <span className="pillar-eta-readiness">
            {pillar.readiness === null ? "—" : `${Math.round(pillar.readiness * 100)}%`}
          </span>
          <span className="pillar-eta-year">
            {pillar.etaYear === null ? "2175+" : Math.round(pillar.etaYear)}
          </span>
        </li>
      ))}
    </ol>
  );
}
