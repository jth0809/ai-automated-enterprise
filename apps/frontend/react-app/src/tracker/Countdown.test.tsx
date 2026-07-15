import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Countdown } from "./Countdown";

describe("Countdown", () => {
  it("renders the big year, the 80% interval, and the fixed honesty label", () => {
    render(
      <Countdown
        etaYear={2048.3}
        etaLow={2042}
        etaHigh={2056}
        label="현 추세 지속 시나리오 기준 · 모델 내 80% 구간"
      />,
    );

    expect(screen.getByText("2048")).toBeInTheDocument();
    expect(screen.getByText(/2042\s*–\s*2056/)).toBeInTheDocument();
    expect(
      screen.getByText("현 추세 지속 시나리오 기준 · 모델 내 80% 구간"),
    ).toBeInTheDocument();
  });

  it("renders the beyond-horizon marker when the eta is unresolved", () => {
    render(
      <Countdown
        etaYear={null}
        etaLow={null}
        etaHigh={null}
        label="현 추세 지속 시나리오 기준 · 모델 내 80% 구간"
      />,
    );

    expect(screen.getByText("2175+")).toBeInTheDocument();
  });

  it("adds per-pillar context when the overall eta is unresolved", () => {
    render(
      <Countdown
        etaYear={null}
        etaLow={null}
        etaHigh={null}
        label="현 추세 지속 시나리오 기준 · 모델 내 80% 구간"
        pillars={[
          { pillar: 1, name: "수송", readiness: 0.32, etaYear: null, momentum: -0.0015,
            baseEtaLow: null, baseEtaHigh: null, etaLow: null, etaHigh: null,
            coherenceAdjusted: false, coherenceReportPeriod: null },
          { pillar: 2, name: "생명 유지", readiness: 0.13, etaYear: 2150.7, momentum: 0.029,
            baseEtaLow: 2140, baseEtaHigh: 2160, etaLow: 2140, etaHigh: 2160,
            coherenceAdjusted: false, coherenceReportPeriod: null },
          { pillar: 4, name: "자원·에너지", readiness: 0.19, etaYear: 2087.1, momentum: 0.052,
            baseEtaLow: 2080, baseEtaHigh: 2094, etaLow: 2080, etaHigh: 2094,
            coherenceAdjusted: false, coherenceReportPeriod: null },
        ]}
      />,
    );

    expect(screen.getByText("2175+")).toBeInTheDocument();
    // earliest resolved pillar and the count of unresolved pillars
    expect(screen.getByText(/가장 이른 필라 2087/)).toBeInTheDocument();
    expect(screen.getByText(/미해결 1/)).toBeInTheDocument();
  });
});
