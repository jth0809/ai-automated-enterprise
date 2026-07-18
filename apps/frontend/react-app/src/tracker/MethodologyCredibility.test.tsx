import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  MethodologyCredibility,
  TRACKER_HONESTY_LABELS,
} from "./MethodologyCredibility";
import type { Summary } from "./api";

const never = () => new Promise<never>(() => undefined);

const ALIGNED_SUMMARY: Summary = {
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

function fullLoaders() {
  return {
    methodology: async () => ({
      methodologyVersion: "tracker-methodology-v1",
      asOf: "2026-07-16",
      modelParameters: {
        params: {
          version: "params-v2",
          epsilon: 0.01,
          kShrink: 4,
          windowM: 6,
          windowMinYears: 4,
          windowMaxYears: 15,
          defaultDeltaE: 0.15,
        },
        uncertainty: {},
      },
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
      dataset: {
        version: "backfill-v1",
        sha256: "b".repeat(64),
        nodeSetVersion: "nodes-v1.0",
        recordCount: 147,
        importedAt: "2026-07-16T13:45:41Z",
      },
      evidenceCoverage: {
        historicalCandidateCount: 210,
        approvedClaimCount: 147,
        distinctCandidatesUsed: 147,
        activeNodeCount: 35,
        directlyMappedActiveNodeCount: 31,
        singleEvidenceClaimCount: 147,
        verificationLevelCounts: { OFFICIAL: 147 },
      },
      currentCalibration: {
        calibrationVersion: "calibration-identity-v1-c4c989b85755",
        method: "IDENTITY",
        status: "INSUFFICIENT_CALIBRATION_DATA",
        sampleCount: 0,
        quarterCount: 0,
      },
      predictionOperations: {
        completedCohorts: 1,
        pendingPredictions: 12,
        duePredictions: 0,
        resolvedPredictions: 0,
        voidPredictions: 0,
        openResolutionConflicts: 0,
        openDriftAlerts: 0,
        currentCalibrationVersion: "calibration-identity-v1-c4c989b85755",
        currentCalibrationStatus: "INSUFFICIENT_CALIBRATION_DATA",
        issuanceFrozen: false,
      },
      formulas: {
        effectiveReadiness: "r_eff = min(r_raw, dependency_cap)",
        etaScenario: "ETA = now + (logit(0.85) - logit(r_now)) / beta_tilde",
      },
      honestyLabels: [...TRACKER_HONESTY_LABELS],
      automaticFeatures: {
        phase4Projection: false,
        phase4Backtest: false,
        predictionIssuance: false,
        predictionResolution: false,
        launchLibraryPolling: false,
        officialIndexPolling: false,
        metaculusPolling: false,
        goldenLiveEvaluation: false,
      },
      transportAssumptions: {
        centralUsdPerKg: 200,
        easyUsdPerKg: 500,
        hardUsdPerKg: 100,
        priceBasis: "PUBLISHED_PRICE",
        intervalKind: "ASSUMPTION_SENSITIVITY",
      },
    }),
    dag: async () => ({
      graphVersion: "graph-v1.0",
      nodeSetVersion: "nodes-v1.0",
      edgeSha256: "a".repeat(64),
      edgeCount: 29,
      asOf: "2026-07-16",
      edges: [],
      nodes: [
        {
          nodeCode: "P1-REUSE-LV",
          nodeName: "발사체 완전 재사용",
          pillar: 1,
          rawReadiness: 0.63,
          effectiveReadiness: 0.45,
          dependencyCap: 0.45,
          capped: true,
          limitingGroups: [1],
          limitingDependencies: ["P1-PROP"],
        },
        {
          nodeCode: "P2-MED",
          nodeName: "장기 체류 의학",
          pillar: 2,
          rawReadiness: 0.55,
          effectiveReadiness: 0.55,
          dependencyCap: null,
          capped: false,
          limitingGroups: [],
          limitingDependencies: [],
        },
      ],
    }),
    projection: async () => ({
      status: "COMPLETED" as const,
      runId: 1,
      inputSha256: "c".repeat(64),
      seed: "6770845816941252525",
      requestedSamples: 4000,
      validSamples: 4000,
      invalidSamples: 0,
      paramsVersion: "params-v2",
      graphVersion: "graph-v1.0",
      nodeSetVersion: "nodes-v1.0",
      datasetSha256: "b".repeat(64),
      startedAt: "2026-07-16T13:45:41Z",
      completedAt: "2026-07-16T13:45:45Z",
      output: {
        inputSha256: "c".repeat(64),
        seed: 6770845816941253000,
        requestedSamples: 4000,
        validSamples: 4000,
        invalidSamples: 0,
        diagnostics: "valid",
        results: {
          "0": {
            pillar: 0,
            readiness: 0.147,
            etaP10: 2089.1,
            etaP50: 2091.2,
            etaP90: 2093.6,
            censoredFraction: 0,
            momentum: "INSUFFICIENT_DATA",
          },
        },
      },
    }),
    backtest: async () => ({
      status: "COMPLETED" as const,
      runId: 1,
      inputSha256: "d".repeat(64),
      reportSha256: "e".repeat(64),
      seed: "6770845816941252525",
      startedAt: "2026-07-16T13:45:41Z",
      completedAt: "2026-07-16T13:48:12Z",
      report: {
        reportVersion: "backtest-report-v2",
        candidateRegistryVersion: "backtest-candidates-v2",
        calibrationCutoffCount: 52,
        holdoutCutoffCount: 16,
        sampleCount: 1000,
        selectedCandidate: { windowM: 8, kShrink: 4, deltaScale: 0.75 },
        objectiveScore: 0.27169275617931676,
        metrics: [
          {
            code: "READINESS_MAE",
            pillar: 0,
            calibrationValue: 0.0093558,
            holdoutValue: 0.0120402,
            calibrationSamples: 312,
            holdoutSamples: 96,
            calibrationStatus: "OK",
            holdoutStatus: "OK",
          },
          {
            code: "DIRECTION_ACCURACY",
            pillar: 0,
            calibrationValue: 0.102564,
            holdoutValue: 0.3125,
            calibrationSamples: 312,
            holdoutSamples: 96,
            calibrationStatus: "OK",
            holdoutStatus: "OK",
          },
        ],
      },
      modelEvaluations: [
        {
          role: "SELECTED" as const,
          candidate: { windowM: 8, kShrink: 4, deltaScale: 0.75 },
          metrics: [
            holdoutMetric("READINESS_MAE", 0.0120402, 96),
            holdoutMetric("DIRECTION_ACCURACY", 0.3125, 96),
          ],
        },
        {
          role: "ACTIVE" as const,
          candidate: { windowM: 6, kShrink: 4, deltaScale: 1 },
          metrics: [holdoutMetric("READINESS_MAE", 0.0131, 96)],
        },
        {
          role: "PERSISTENCE" as const,
          candidate: null,
          metrics: [holdoutMetric("READINESS_MAE", 0.01, 96)],
        },
        {
          role: "ALWAYS_NO_CHANGE" as const,
          candidate: null,
          metrics: [holdoutMetric("DIRECTION_ACCURACY", 0.6875, 96)],
        },
      ],
      skillStatus: "NO_SKILL_VS_PERSISTENCE" as const,
      readinessMaeRatioVsPersistence: 1.20402,
      selectedMatchesActive: false,
    }),
    predictions: async () => ({
      status: "PUBLISHED" as const,
      cohorts: [
        {
          id: 1,
          cohortKey: "micro-v1-2026-07-16-9f9612f9a484",
          inputSha256: "f".repeat(64),
          datasetSha256: "b".repeat(64),
          nodeSetVersion: "nodes-v1.0",
          rubricVersion: "r2.0",
          hazardParamsVersion: "hazard-v1",
          calibrationVersion: "calibration-identity-v1-c4c989b85755",
          asOf: "2026-07-13",
          issuedOn: "2026-07-16",
          createdAt: "2026-07-16T13:46:39Z",
          completedAt: "2026-07-16T13:46:39Z",
          predictions: [
            {
              id: 1,
              cohortRank: 1,
              statement:
                "장주기 자율 운영이 2028-07-16까지 검증된 수준 L8 이상에 도달한다.",
              nodeCode: "P5-AUTONOMY",
              pillar: 5,
              targetLevel: 8,
              dueOn: "2028-07-16",
              horizonMonths: 24,
              issuedProbability: 0.080538,
              informationStatus: "LOW_INFORMATION",
              outcome: "PENDING",
              brier: null,
              resolutionStatus: "PENDING",
              statementSha256: "1".repeat(64),
            },
          ],
        },
      ],
    }),
    scorecard: async () => ({
      groups: [
        {
          type: "OVERALL",
          key: "ALL",
          sampleCount: 0,
          meanBrier: null,
          status: "INSUFFICIENT_DATA",
        },
      ],
    }),
  };
}

describe("MethodologyCredibility", () => {
  it("keeps all four honesty labels visible while every contract is loading", () => {
    render(
      <MethodologyCredibility
        loaders={{
          methodology: never,
          dag: never,
          projection: never,
          backtest: never,
          predictions: never,
          scorecard: never,
        }}
      />,
    );

    expect(
      screen.getByRole("heading", { level: 2, name: "방법론과 신뢰도" }),
    ).toBeInTheDocument();
    for (const label of TRACKER_HONESTY_LABELS) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getAllByText("불러오는 중")).toHaveLength(6);
  });

  it("publishes model, DAG, projection, backtest, and prediction evidence", async () => {
    render(
      <MethodologyCredibility
        loaders={fullLoaders()}
        summary={ALIGNED_SUMMARY}
      />,
    );

    expect(await screen.findByText("params-v2")).toBeInTheDocument();
    expect(screen.getByText("hazard-v1")).toBeInTheDocument();
    expect(screen.getByText("graph-v1.0")).toBeInTheDocument();
    expect(screen.getByText(/backfill-v1 · 147건/)).toBeInTheDocument();
    expect(screen.getByText(/후보 사용 147 \/ 210 \(70.0%\)/)).toBeInTheDocument();
    expect(screen.getByText(/직접 매핑 활성 노드 31 \/ 35 \(88.6%\)/)).toBeInTheDocument();
    expect(screen.getByText(/단일 근거 청구 147 \/ 147 \(100.0%\)/)).toBeInTheDocument();
    expect(screen.getByText(/중앙 \$200\/kg/)).toBeInTheDocument();
    expect(screen.getByText(/r_eff = min/)).toBeInTheDocument();
    expect(screen.getAllByText("비활성").length).toBeGreaterThanOrEqual(8);

    expect(screen.getByText("2091.2년")).toBeInTheDocument();
    expect(screen.getByText("2089.1–2093.6년")).toBeInTheDocument();
    expect(screen.getByText("스냅샷과 투영 정렬")).toBeInTheDocument();
    expect(screen.getByText("검열 0.0%")).toBeInTheDocument();
    expect(screen.getAllByText("6770845816941252525")).toHaveLength(2);

    const dag = screen.getByRole("table", { name: "DAG 의존성 제한 노드" });
    expect(within(dag).getByText("P1-REUSE-LV")).toBeInTheDocument();
    expect(within(dag).getByText("P1-PROP")).toBeInTheDocument();

    expect(screen.getByText(/m=8 · k=4 · δ=0.75/)).toBeInTheDocument();
    const metrics = screen.getByRole("table", { name: "백테스트 전체 필라 지표" });
    expect(within(metrics).getByText("READINESS_MAE")).toBeInTheDocument();
    expect(within(metrics).getByText("0.0120")).toBeInTheDocument();
    const baselines = screen.getByRole("table", {
      name: "백테스트 모델 및 기준선 비교",
    });
    expect(within(baselines).getByText("선택 추천")).toBeInTheDocument();
    expect(within(baselines).getByText("현재 활성")).toBeInTheDocument();
    expect(within(baselines).getByText("지속성 기준")).toBeInTheDocument();
    expect(within(baselines).getByText("항상 무변화")).toBeInTheDocument();
    expect(screen.getByText(/지속성 기준보다 개선되지 않음/)).toBeInTheDocument();
    expect(screen.getByText(/자동 승격되지 않았/)).toBeInTheDocument();
    expect(screen.queryByText(/검증 통과/)).not.toBeInTheDocument();

    const predictions = screen.getByRole("table", { name: "공표 마이크로 예측" });
    expect(within(predictions).getByText(/장주기 자율 운영/)).toBeInTheDocument();
    expect(within(predictions).getByText("8.1%")).toBeInTheDocument();
    expect(screen.getByText("결과 부족 · n=0")).toBeInTheDocument();
    expect(screen.getByText(/통합 노드는 구성 노드의 최고값을 상속하지 않는다/)).toBeInTheDocument();
  });

  it("warns when the weekly snapshot and audit projection use different versions", async () => {
    render(
      <MethodologyCredibility
        loaders={fullLoaders()}
        summary={{ ...ALIGNED_SUMMARY, graphVersion: "graph-v2.0" }}
      />,
    );

    expect(await screen.findByText(/버전 불일치/)).toBeInTheDocument();
  });

  it("shows explicit independent error and empty states without hiding honesty", async () => {
    const full = fullLoaders();
    render(
      <MethodologyCredibility
        loaders={{
          ...full,
          dag: async () => {
            throw new Error("dag unavailable");
          },
          projection: async () => ({ status: "NOT_RUN" as const }),
          backtest: async () => {
            throw new Error("backtest unavailable");
          },
          predictions: async () => ({ status: "EMPTY" as const, cohorts: [] }),
          scorecard: async () => ({
            groups: [
              {
                type: "OVERALL",
                key: "ALL",
                sampleCount: 0,
                meanBrier: null,
                status: "INSUFFICIENT_DATA",
              },
            ],
          }),
        }}
      />,
    );

    expect(await screen.findByText("투영 실행 기록 없음")).toBeInTheDocument();
    expect(screen.getAllByText("불러오지 못했습니다")).toHaveLength(2);
    expect(screen.getByText("공표 코호트 없음")).toBeInTheDocument();
    expect(screen.getByText("결과 부족 · n=0")).toBeInTheDocument();
    for (const label of TRACKER_HONESTY_LABELS) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });
});

function holdoutMetric(code: string, value: number, samples: number) {
  return {
    code,
    pillar: 0,
    calibrationValue: value,
    holdoutValue: value,
    calibrationSamples: samples,
    holdoutSamples: samples,
    calibrationStatus: "OK",
    holdoutStatus: "OK",
  };
}
