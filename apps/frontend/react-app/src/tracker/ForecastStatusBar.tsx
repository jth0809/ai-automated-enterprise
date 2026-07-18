import type { PillarSummary, Summary } from "./api";

interface ForecastStatusBarProps {
  summary: Summary;
  pillars: PillarSummary[];
}

/** Compact provenance and dual-bottleneck context for the headline forecast. */
export function ForecastStatusBar({ summary, pillars }: ForecastStatusBarProps) {
  if (summary.indicatorStatus === "INCOMPLETE_SNAPSHOT") {
    const missing = summary.missingPillars.length === 0
      ? "메타데이터 불일치"
      : `누락 ${summary.missingPillars.map((pillar) => `P${pillar}`).join(", ")}`;
    return (
      <p className="forecast-status forecast-status-incomplete" role="status">
        스냅샷 불완전 · {missing}
      </p>
    );
  }

  const readiness = pillarNames(summary.readinessBottleneckPillars, pillars);
  const eta = pillarNames(summary.etaBottleneckPillars, pillars);
  const unresolved = summary.unresolvedEtaPillars.length > 0;
  return (
    <section className="forecast-status" aria-label="예측 상태와 자동 병목">
      <p className="forecast-status-item">
        현재 준비도 병목 {readiness || "없음"}
      </p>
      <p className="forecast-status-item">
        전체 ETA 병목 {eta || "없음"}{unresolved ? " · 추세 미해결" : ""}
      </p>
      <p className="forecast-status-meta">
        스냅샷 {summary.snapshotDate ?? "-"} · {summary.paramsVersion ?? "-"} · {summary.graphVersion ?? "-"}
      </p>
    </section>
  );
}

function pillarNames(ids: number[], pillars: PillarSummary[]): string {
  const names = new Map(pillars.map((pillar) => [pillar.pillar, pillar.name]));
  return ids
    .map((pillar) => `P${pillar} ${names.get(pillar) ?? "이름 미확인"}`)
    .join(", ");
}
