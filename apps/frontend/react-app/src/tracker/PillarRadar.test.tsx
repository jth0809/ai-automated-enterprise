import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PillarRadar } from "./PillarRadar";

const NAMES = ["수송", "생명 유지", "거주 인프라", "자원·에너지", "로봇·자율 운영", "경제·거버넌스"];

function pillars(readiness: number[]) {
  return readiness.map((value, i) => ({
    pillar: i + 1,
    name: NAMES[i],
    readiness: value,
    etaYear: null,
    momentum: null,
    baseEtaLow: null,
    baseEtaHigh: null,
    etaLow: null,
    etaHigh: null,
    coherenceAdjusted: false,
    coherenceReportPeriod: null,
  }));
}

describe("PillarRadar", () => {
  it("computes hexagon polygon points as readiness x radius from the center", () => {
    const { container } = render(
      <PillarRadar
        pillars={pillars([1, 0.5, 0, 1, 0.5, 0])}
        bottlenecks={[3, 6]}
      />,
    );

    const polygon = container.querySelector("polygon.radar-value");
    expect(polygon).not.toBeNull();
    const points = (polygon!.getAttribute("points") ?? "")
      .trim()
      .split(/\s+/)
      .map((pair) => pair.split(",").map(Number));

    // Center (100,100), radius 80, vertex 0 at 12 o'clock, clockwise by 60°.
    const expected = [
      [100, 20],
      [134.64, 80],
      [100, 100],
      [100, 180],
      [65.36, 120],
      [100, 100],
    ];
    expect(points).toHaveLength(6);
    for (let i = 0; i < 6; i++) {
      expect(points[i][0]).toBeCloseTo(expected[i][0], 1);
      expect(points[i][1]).toBeCloseTo(expected[i][1], 1);
    }
  });

  it("highlights every readiness bottleneck pillar label", () => {
    const { container } = render(
      <PillarRadar pillars={pillars([1, 1, 0, 1, 1, 0])} bottlenecks={[3, 6]} />,
    );

    const highlighted = container.querySelectorAll("text.radar-label-bottleneck");
    expect(highlighted).toHaveLength(2);
    expect(highlighted[0].textContent).toBe("거주 인프라");
    expect(highlighted[1].textContent).toBe("경제·거버넌스");
  });
});
