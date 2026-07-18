import type { PillarSummary } from "./api";

interface PillarRadarProps {
  pillars: PillarSummary[];
  bottlenecks: number[];
}

const CENTER = 100;
const RADIUS = 80;
const LABEL_RADIUS = 95;

function vertex(index: number, scale: number): [number, number] {
  const angle = -Math.PI / 2 + (index * Math.PI) / 3;
  return [
    CENTER + scale * RADIUS * Math.cos(angle),
    CENTER + scale * RADIUS * Math.sin(angle),
  ];
}

function points(scales: number[]): string {
  return scales
    .map((scale, i) => vertex(i, scale).map((v) => Number(v.toFixed(2))).join(","))
    .join(" ");
}

/**
 * Library-free SVG hexagon radar: each vertex sits at readiness x radius from
 * the center; the bottleneck pillar's label is highlighted.
 */
export function PillarRadar({ pillars, bottlenecks }: PillarRadarProps) {
  const ordered = [...pillars].sort((a, b) => a.pillar - b.pillar);
  const scales = ordered.map((p) => p.readiness ?? 0);
  const highlightedPillars = new Set(bottlenecks);
  const bottleneckLabel = ordered
    .filter((pillar) => highlightedPillars.has(pillar.pillar))
    .map((pillar) => `P${pillar.pillar} ${pillar.name}`)
    .join(", ");
  return (
    <svg
      className="radar"
      viewBox="0 0 200 200"
      role="img"
      aria-label={`Pillar readiness radar · 현재 준비도 병목 ${bottleneckLabel || "없음"}`}
    >
      <title>현재 준비도 병목 {bottleneckLabel || "없음"}</title>
      <polygon className="radar-grid" points={points([1, 1, 1, 1, 1, 1])} />
      <polygon className="radar-grid" points={points([0.5, 0.5, 0.5, 0.5, 0.5, 0.5])} />
      <polygon className="radar-value" points={points(scales)} />
      {ordered.map((pillar, i) => {
        const [x, y] = vertex(i, LABEL_RADIUS / RADIUS);
        const highlighted = highlightedPillars.has(pillar.pillar);
        return (
          <text
            key={pillar.pillar}
            className={highlighted ? "radar-label radar-label-bottleneck" : "radar-label"}
            x={Number(x.toFixed(2))}
            y={Number(y.toFixed(2))}
            textAnchor="middle"
            dominantBaseline="middle"
          >
            {pillar.name}
          </text>
        );
      })}
    </svg>
  );
}
