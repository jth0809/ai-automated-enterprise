import { describe, expect, it } from "vitest";
import type { ProjectionResponse, Summary } from "./api";
import { forecastAlignment } from "./forecastAlignment";

const SUMMARY: Summary = {
  displayedEtaYear: 2091.2,
  etaLow: 2089.1,
  etaHigh: 2093.6,
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

function projection(etaP50: number | null): ProjectionResponse {
  return {
    status: "COMPLETED",
    paramsVersion: "params-v2",
    graphVersion: "graph-v1.0",
    output: {
      inputSha256: "a".repeat(64),
      seed: 1,
      requestedSamples: 1000,
      validSamples: 1000,
      invalidSamples: 0,
      diagnostics: {},
      results: {
        "0": {
          pillar: 0,
          readiness: 0.147,
          etaP10: null,
          etaP50,
          etaP90: null,
          censoredFraction: etaP50 === null ? 1 : 0,
          momentum: "INSUFFICIENT_DATA",
        },
      },
    },
  };
}

describe("forecastAlignment", () => {
  it("aligns matching versions and medians within tolerance", () => {
    expect(forecastAlignment(SUMMARY, projection(2091.24))).toBe("ALIGNED");
    expect(
      forecastAlignment(
        { ...SUMMARY, displayedEtaYear: null },
        projection(null),
      ),
    ).toBe("ALIGNED");
  });

  it("distinguishes separate results from version mismatches", () => {
    expect(forecastAlignment(SUMMARY, projection(2091.3))).toBe(
      "DIFFERENT_RUN",
    );
    expect(
      forecastAlignment(SUMMARY, {
        ...projection(2091.2),
        graphVersion: "graph-v2.0",
      }),
    ).toBe("VERSION_MISMATCH");
  });

  it("reports unavailable artifacts without fabricating alignment", () => {
    expect(forecastAlignment(null, projection(2091.2))).toBe("UNAVAILABLE");
    expect(forecastAlignment(SUMMARY, { status: "NOT_RUN" })).toBe(
      "UNAVAILABLE",
    );
    expect(
      forecastAlignment(
        { ...SUMMARY, indicatorStatus: "INCOMPLETE_SNAPSHOT" },
        projection(2091.2),
      ),
    ).toBe("UNAVAILABLE");
  });
});
