import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ForecastStatusBar } from "./ForecastStatusBar";
import type { PillarSummary, Summary } from "./api";

const NAMES = ["수송", "생명 유지", "거주 인프라", "자원·에너지", "로봇·자율 운영", "경제·거버넌스"];

const PILLARS: PillarSummary[] = NAMES.map((name, index) => ({
  pillar: index + 1,
  name,
  readiness: (index + 1) / 10,
  etaYear: 2070 + index,
  momentum: null,
  baseEtaLow: null,
  baseEtaHigh: null,
  etaLow: null,
  etaHigh: null,
  coherenceAdjusted: false,
  coherenceReportPeriod: null,
}));

const SUMMARY: Summary = {
  displayedEtaYear: 2090,
  etaLow: 2088,
  etaHigh: 2092,
  label: "현 추세 지속 시나리오 · 모델 내부 민감도 80% 구간",
  overallReadiness: 0.147,
  bottleneckPillar: 3,
  indicatorStatus: "COMPLETE",
  readinessBottleneckPillars: [3],
  etaBottleneckPillars: [4],
  unresolvedEtaPillars: [],
  missingPillars: [],
  snapshotDate: "2026-07-18",
  paramsVersion: "params-v2",
  graphVersion: "graph-v1.0",
  frozen: false,
};

describe("ForecastStatusBar", () => {
  it("distinguishes current-readiness and ETA bottlenecks", () => {
    render(<ForecastStatusBar summary={SUMMARY} pillars={PILLARS} />);

    expect(screen.getByText(/현재 준비도 병목 P3 거주 인프라/)).toBeInTheDocument();
    expect(screen.getByText(/전체 ETA 병목 P4 자원·에너지/)).toBeInTheDocument();
    expect(screen.getByText(/스냅샷 2026-07-18/)).toHaveTextContent(
      "params-v2 · graph-v1.0",
    );
  });

  it("renders ties and unresolved ETA blockers from arrays", () => {
    render(
      <ForecastStatusBar
        summary={{
          ...SUMMARY,
          readinessBottleneckPillars: [2, 3],
          etaBottleneckPillars: [1, 6],
          unresolvedEtaPillars: [1, 6],
        }}
        pillars={PILLARS}
      />,
    );

    expect(screen.getByText(/P2 생명 유지, P3 거주 인프라/)).toBeInTheDocument();
    expect(screen.getByText(/P1 수송, P6 경제·거버넌스/)).toHaveTextContent(
      "추세 미해결",
    );
  });

  it("does not invent a bottleneck for an incomplete snapshot", () => {
    render(
      <ForecastStatusBar
        summary={{
          ...SUMMARY,
          indicatorStatus: "INCOMPLETE_SNAPSHOT",
          readinessBottleneckPillars: [],
          etaBottleneckPillars: [],
          missingPillars: [5],
          snapshotDate: null,
          paramsVersion: null,
          graphVersion: null,
        }}
        pillars={PILLARS}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("스냅샷 불완전 · 누락 P5");
    expect(screen.queryByText(/현재 준비도 병목/)).not.toBeInTheDocument();
  });
});
