import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TrackerPage } from "./TrackerPage";
import { EventTimeline } from "./EventTimeline";

afterEach(() => {
  vi.restoreAllMocks();
});

function stubTrackerRoutes(options: { failForecast?: boolean } = {}) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (
        options.failForecast === true &&
        url.includes("/api/tracker/forecast-comparison")
      ) {
        return { ok: false, status: 503, json: async () => ({}) } as Response;
      }
      if (url.includes("/api/tracker/methodology")) {
        return ok({
          methodologyVersion: "tracker-methodology-v1",
          asOf: "2026-07-16",
          modelParameters: { params: { version: "params-v2" }, uncertainty: {} },
          hazardParameters: {
            version: "hazard-v1",
            kappaNodeYears: 4,
            probabilityFloor: 0.02,
            probabilityCeiling: 0.98,
            horizonsMonths: [6, 12, 18, 24],
            cohortLimit: 12,
            pillarLimit: 2,
            calibrationMinOutcomes: 30,
            calibrationMinQuarters: 4,
          },
          graph: {
            version: "graph-v1.0",
            nodeSetVersion: "nodes-v1.0",
            edgeSha256: "a".repeat(64),
            edgeCount: 29,
          },
          dataset: null,
          currentCalibration: null,
          predictionOperations: {
            completedCohorts: 0,
            pendingPredictions: 0,
            duePredictions: 0,
            resolvedPredictions: 0,
            voidPredictions: 0,
            openResolutionConflicts: 0,
            openDriftAlerts: 0,
            currentCalibrationVersion: null,
            currentCalibrationStatus: null,
            issuanceFrozen: false,
          },
          formulas: {},
          honestyLabels: [
            "ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델 내부의 80%다. 모형족 오류와 미지의 구조 단절 확률은 포함하지 않는다.",
            "수송 $ / kg은 실제 원가가 아니라 공개된 가격을 바탕으로 한 추정치다.",
            "관측 사건은 측정값이고 TRL/EGL 사상·가중치·DAG 집계는 구성 지수다.",
            "수송 경제성 임계값은 자연상수가 아니라 공개된 모델 가정이다.",
          ],
          automaticFeatures: {},
          transportAssumptions: {
            centralUsdPerKg: 200,
            easyUsdPerKg: 500,
            hardUsdPerKg: 100,
            priceBasis: "PUBLISHED_PRICE",
            intervalKind: "ASSUMPTION_SENSITIVITY",
          },
        });
      }
      if (url.includes("/api/tracker/dag")) {
        return ok({
          graphVersion: "graph-v1.0",
          nodeSetVersion: "nodes-v1.0",
          edgeSha256: "a".repeat(64),
          edgeCount: 29,
          asOf: "2026-07-16",
          edges: [],
          nodes: [],
        });
      }
      if (url.includes("/api/tracker/projections/current")) {
        return ok({ status: "NOT_RUN" });
      }
      if (url.includes("/api/tracker/backtests/latest")) {
        return ok({ status: "NOT_RUN" });
      }
      if (url.includes("/api/tracker/predictions/scorecard")) {
        return ok({
          groups: [
            {
              type: "OVERALL",
              key: "ALL",
              sampleCount: 0,
              meanBrier: null,
              status: "INSUFFICIENT_DATA",
            },
          ],
        });
      }
      if (url.includes("/api/tracker/predictions")) {
        return ok({ status: "EMPTY", cohorts: [] });
      }
      const body = url.includes("/api/tracker/summary")
        ? {
            displayedEtaYear: 2048.3,
            etaLow: 2042,
            etaHigh: 2056,
            label: "현 추세 지속 시나리오 기준 · 모델 내 80% 구간",
            overallReadiness: 0.12,
            bottleneckPillar: 3,
            frozen: false,
          }
        : url.includes("/api/tracker/pillars")
          ? [1, 2, 3, 4, 5, 6].map((pillar) => ({
              pillar,
              name: `pillar-${pillar}`,
              readiness: pillar / 10,
              etaYear: null,
              momentum: null,
              baseEtaLow: null,
              baseEtaHigh: null,
              etaLow: null,
              etaHigh: null,
              coherenceAdjusted: false,
              coherenceReportPeriod: null,
            }))
          : url.includes("/api/tracker/events")
            ? [
                {
                  occurredOn: "2021-04-20",
                  occurredOnPrecision: "DAY",
                  nodeName: "ISRU: 추진제·물·산소 현지 생산",
                  eventType: "FLIGHT_TEST",
                  levelFrom: 0,
                  levelTo: 6,
                  impactScore: null,
                  verificationLevel: "OFFICIAL",
                  sourceCount: 0,
                  evidenceQuote: null,
                  primaryEvidence: null,
                },
              ]
            : url.includes("/api/tracker/layer-b")
              ? []
              : url.includes("/api/tracker/transport-economics")
                ? {
                    status: "PROVISIONAL",
                    sufficiencyTier: "PROVISIONAL",
                    qualificationFlags: ["WEAK_FIT"],
                    observationCount: 3,
                    centralTargetUsdPerKg: 200,
                    easyTargetUsdPerKg: 500,
                    hardTargetUsdPerKg: 100,
                    centralEtaYear: 2098.4,
                    earliestEtaYear: 2074.2,
                    latestEtaYear: null,
                    centralBeyondHorizon: false,
                    earliestBeyondHorizon: false,
                    latestBeyondHorizon: true,
                    priceBasisYear: 2025,
                    basis: "PUBLISHED_PRICE",
                    priceMeaning:
                      "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
                    projectionLabel:
                      "Declared-assumption scenario; not provider internal cost",
                    intervalKind: "ASSUMPTION_SENSITIVITY",
                    coherenceState: "COHERENT",
                    coherenceAlertActive: false,
                  }
                : url.includes("/api/tracker/forecast-comparison")
                  ? {
                      status: "PARTIAL",
                      asOfDate: "2026-07-15",
                      smoothingWindowDays: 90,
                      crowdLiveStatus: "AUTHORIZATION_REQUIRED",
                      rows: ["LANDING", "RETURN", "SETTLEMENT"].map(
                        (trackCode) => ({
                          trackCode,
                          trackLabel: `${trackCode} 목표`,
                          definition: `${trackCode} 비교 정의`,
                          model: unavailableEstimate(),
                          transport: unavailableEstimate(),
                          crowd: unavailableEstimate(),
                          institutional: [],
                        }),
                      ),
                    }
                  : url.includes("/api/tracker/k-index")
                  ? {
                      status: "CURRENT",
                      latestYear: 2024,
                      primaryEnergyTwh: 176737.1,
                      powerWatts: 20175468036530,
                      kValue: 0.7305,
                      annualDelta: 0.0011,
                      typeOneGap: 0.2695,
                      typeOneMultiplier: 495.7,
                      accountingBasis: "SUBSTITUTION",
                      sourceName: "Reviewed energy source",
                      sourceUrl: "https://example.test/energy",
                      accessedOn: "2026-07-15",
                      series: [
                        { year: 2023, kValue: 0.7294 },
                        { year: 2024, kValue: 0.7305 },
                      ],
                    }
              : { error: "unexpected" };
      return { ok: true, status: 200, json: async () => body } as Response;
    }),
  );
}

describe("TrackerPage", () => {
  it("renders countdown, radar, timeline, and transport economics", async () => {
    stubTrackerRoutes();
    const { container } = render(<TrackerPage />);

    expect(await screen.findByText("2048")).toBeInTheDocument();
    expect(
      screen.getByText("현 추세 지속 시나리오 기준 · 모델 내 80% 구간"),
    ).toBeInTheDocument();
    expect(container.querySelector("polygon.radar-value")).not.toBeNull();
    expect(
      await screen.findByText(/ISRU: 추진제·물·산소 현지 생산/),
    ).toBeInTheDocument();
    expect(screen.getByText("FLIGHT_TEST")).toBeInTheDocument();
    expect(screen.getByText("수송 경제성 시나리오")).toBeInTheDocument();
    expect(screen.getByText(/중앙 가정 \$200\/kg/)).toBeInTheDocument();
    expect(screen.getByText("인류 문명 지수 K = 0.7305")).toBeInTheDocument();
    expect(await screen.findByText("화성 예측 비교")).toBeInTheDocument();
    expect(await screen.findByText("방법론과 신뢰도")).toBeInTheDocument();
    // The admin review queue is collapsed below the public dashboard and
    // fetches nothing until a token is submitted.
    expect(screen.getByText(/검수 큐/)).toBeInTheDocument();
  });

  it("keeps the core dashboard available when forecast comparison fails", async () => {
    stubTrackerRoutes({ failForecast: true });
    render(<TrackerPage />);

    expect(await screen.findByText("2048")).toBeInTheDocument();
    expect(
      await screen.findByText("예측 비교 데이터를 불러오지 못했습니다."),
    ).toBeInTheDocument();
    expect(screen.getByText("수송 경제성 시나리오")).toBeInTheDocument();
  });
});

function unavailableEstimate() {
  return {
    status: "NOT_APPLICABLE",
    year: null,
    rawYear: null,
    yearLow: null,
    yearHigh: null,
    relationKind: "NONE",
    label: "적용 범위 밖",
    detail: "직접 비교할 수 없습니다.",
    sourceName: null,
    sourceUrl: null,
    sourceLocator: null,
    observedOn: null,
    accessedOn: null,
    legacy: false,
  };
}

function ok(body: unknown): Response {
  return { ok: true, status: 200, json: async () => body } as Response;
}

describe("EventTimeline evidence", () => {
  it("renders reviewed historical facts without quotation styling", () => {
    const { container } = render(
      <EventTimeline
        events={[
          {
            occurredOn: "1969-01-01",
            occurredOnPrecision: "YEAR",
            nodeName: "Crewed lunar landing",
            eventType: "MILESTONE",
            levelFrom: 5,
            levelTo: 6,
            impactScore: null,
            verificationLevel: "OFFICIAL",
            sourceCount: 1,
            evidenceQuote: null,
            primaryEvidence: {
              kind: "HISTORICAL_REFERENCE",
              sourceLabel: "NASA",
              url: "https://www.nasa.gov/history/apollo-11-mission-overview/",
              evidenceQuote: null,
              factSummary: "Apollo 11 completed the first crewed lunar landing in 1969.",
              locator: "Mission overview",
              accessedOn: "2026-07-13",
            },
          },
        ]}
      />,
    );

    expect(screen.getByText("1969")).toBeInTheDocument();
    expect(screen.getByText("인간 검수 사실 요약")).toBeInTheDocument();
    const summary = screen.getByText(/first crewed lunar landing/i);
    expect(summary.tagName).toBe("P");
    expect(summary.closest("blockquote")).toBeNull();
    expect(container.querySelector("blockquote")).toBeNull();
    expect(screen.getByText("Mission overview")).toBeInTheDocument();
    expect(screen.getByText("확인일 2026-07-13")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "NASA" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
  });

  it("keeps verbatim evidence in quotation styling", () => {
    const { container } = render(
      <EventTimeline
        events={[
          {
            occurredOn: "2026-01-30",
            occurredOnPrecision: "DAY",
            nodeName: "Orbital refueling",
            eventType: "FLIGHT_TEST",
            levelFrom: 5,
            levelTo: 6,
            impactScore: 7.2,
            verificationLevel: "OFFICIAL",
            sourceCount: 1,
            evidenceQuote: "The vehicle completed the test.",
            primaryEvidence: {
              kind: "VERBATIM",
              sourceLabel: "NASA",
              url: "https://www.nasa.gov/test",
              evidenceQuote: "The vehicle completed the test.",
              factSummary: null,
              locator: null,
              accessedOn: null,
            },
          },
        ]}
      />,
    );

    expect(screen.getByText("원문 인용")).toBeInTheDocument();
    expect(container.querySelector("blockquote")).toHaveTextContent(
      "The vehicle completed the test.",
    );
  });
});
