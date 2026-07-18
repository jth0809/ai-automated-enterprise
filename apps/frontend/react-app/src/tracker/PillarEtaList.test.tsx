import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PillarEtaList } from "./PillarEtaList";
import type { PillarSummary } from "./api";

const PILLARS: PillarSummary[] = [
  { pillar: 1, name: "수송", readiness: 0.32, etaYear: null, momentum: -0.0015,
    baseEtaLow: null, baseEtaHigh: null, etaLow: null, etaHigh: null,
    coherenceAdjusted: false, coherenceReportPeriod: null },
  { pillar: 2, name: "생명 유지", readiness: 0.13, etaYear: 2150.7, momentum: 0.029,
    baseEtaLow: 2140, baseEtaHigh: 2160, etaLow: 2140, etaHigh: 2160,
    coherenceAdjusted: false, coherenceReportPeriod: null },
  { pillar: 4, name: "자원·에너지", readiness: 0.19, etaYear: 2087.1, momentum: 0.052,
    baseEtaLow: 2080, baseEtaHigh: 2094, etaLow: 2080, etaHigh: 2094,
    coherenceAdjusted: false, coherenceReportPeriod: null },
];

describe("PillarEtaList", () => {
  it("shows each pillar's resolved ETA or an unresolved marker", () => {
    render(
      <PillarEtaList
        pillars={PILLARS}
        etaBottlenecks={[1]}
        unresolvedEtaPillars={[1]}
      />,
    );

    expect(screen.getByText("수송")).toBeInTheDocument();
    expect(screen.getByText("2151")).toBeInTheDocument(); // rounded 2150.7
    expect(screen.getByText("2087")).toBeInTheDocument();
    // unresolved pillars render the beyond-horizon marker, not a fabricated year
    expect(screen.getByText("2175+")).toBeInTheDocument();
  });

  it("marks ETA bottlenecks independently from unresolved state", () => {
    const { container } = render(
      <PillarEtaList
        pillars={PILLARS}
        etaBottlenecks={[4]}
        unresolvedEtaPillars={[1]}
      />,
    );
    const rows = container.querySelectorAll("li.pillar-eta-bottleneck");
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain("자원·에너지");
    expect(rows[0].textContent).toContain("전체 ETA 병목");
    const unresolved = container.querySelectorAll("li.pillar-eta-unresolved");
    expect(unresolved).toHaveLength(1);
    expect(unresolved[0].textContent).toContain("수송");
    expect(unresolved[0].textContent).toContain("추세 미해결");
  });
});
