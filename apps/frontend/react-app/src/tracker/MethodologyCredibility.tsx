import { useEffect, useState } from "react";
import {
  getBacktest,
  getDag,
  getMethodology,
  getPredictions,
  getPredictionScorecard,
  getProjection,
} from "./api";
import type {
  BacktestResponse,
  DagResponse,
  MethodologyResponse,
  PredictionsResponse,
  PredictionScorecardResponse,
  ProjectionResponse,
} from "./api";

export const TRACKER_HONESTY_LABELS = [
  "ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델 내부의 80%다. 모형족 오류와 미지의 구조 단절 확률은 포함하지 않는다.",
  "수송 $ / kg은 실제 원가가 아니라 공개된 가격을 바탕으로 한 추정치다.",
  "관측 사건은 측정값이고 TRL/EGL 사상·가중치·DAG 집계는 구성 지수다.",
  "수송 경제성 임계값은 자연상수가 아니라 공개된 모델 가정이다.",
] as const;

export interface CredibilityLoaders {
  methodology: () => Promise<MethodologyResponse>;
  dag: () => Promise<DagResponse>;
  projection: () => Promise<ProjectionResponse>;
  backtest: () => Promise<BacktestResponse>;
  predictions: () => Promise<PredictionsResponse>;
  scorecard: () => Promise<PredictionScorecardResponse>;
}

const DEFAULT_LOADERS: CredibilityLoaders = {
  methodology: getMethodology,
  dag: getDag,
  projection: getProjection,
  backtest: getBacktest,
  predictions: getPredictions,
  scorecard: getPredictionScorecard,
};

const FEATURE_LABELS: Array<[string, string]> = [
  ["phase4Projection", "자동 몬테카를로 투영"],
  ["phase4Backtest", "자동 백테스트"],
  ["predictionIssuance", "자동 예측 발행"],
  ["predictionResolution", "자동 예측 만기 처리"],
  ["launchLibraryPolling", "Launch Library 수집"],
  ["officialIndexPolling", "기관 지표 수집"],
  ["metaculusPolling", "Metaculus 수집"],
  ["goldenLiveEvaluation", "라이브 모델 평가"],
];

type Remote<T> =
  | { state: "loading" }
  | { state: "ready"; data: T }
  | { state: "error" };

export function MethodologyCredibility({
  loaders = DEFAULT_LOADERS,
}: {
  loaders?: CredibilityLoaders;
}) {
  const methodology = useRemote(loaders.methodology);
  const dag = useRemote(loaders.dag);
  const projection = useRemote(loaders.projection);
  const backtest = useRemote(loaders.backtest);
  const predictions = useRemote(loaders.predictions);
  const scorecard = useRemote(loaders.scorecard);

  return (
    <section className="methodology-credibility" aria-labelledby="credibility-title">
      <header className="credibility-head">
        <p className="credibility-eyebrow">PHASE 4 · 공개 감사 계약</p>
        <h2 id="credibility-title">방법론과 신뢰도</h2>
        <p>
          모델의 가정, 의존성 제한, 과거검증, 확률 예측과 현재 비활성 경계를
          같은 화면에서 확인합니다.
        </p>
      </header>

      <ul className="credibility-honesty" aria-label="필수 정직성 표기">
        {TRACKER_HONESTY_LABELS.map((label, index) => (
          <li key={label}>
            <span aria-hidden="true">{index + 1}</span>
            <p>{label}</p>
          </li>
        ))}
      </ul>

      <div className="credibility-grid">
        <MethodologyCard remote={methodology} />
        <ProjectionCard remote={projection} />
        <DagCard remote={dag} />
        <BacktestCard remote={backtest} />
        <PredictionsCard remote={predictions} />
        <ScorecardCard remote={scorecard} />
      </div>

      <article className="credibility-card credibility-rules">
        <h3>도달 판정과 이정표 승계 규칙</h3>
        <ul>
          <li>관측 사건은 먼저 노드 경계를 통과해야 하며 직접 입증된 최고 수준만 기록한다.</li>
          <li>부분 실증은 배제하지 않고 해당 TRL/EGL 앵커까지 제한된 부분점수를 준다.</li>
          <li>통합 노드는 구성 노드의 최고값을 상속하지 않는다.</li>
          <li>롤백·휴면·프로그램 종료는 날짜순 상태 이력으로 재생하며 불리한 결과도 보존한다.</li>
          <li>Layer B 관측값은 측정 레이어이며 준비도나 ETA에 자동 가산되지 않는다.</li>
        </ul>
      </article>
    </section>
  );
}

function MethodologyCard({ remote }: { remote: Remote<MethodologyResponse> }) {
  return (
    <article className="credibility-card">
      <h3>활성 모형과 데이터 경계</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && <MethodologyBody value={remote.data} />}
    </article>
  );
}

function MethodologyBody({ value }: { value: MethodologyResponse }) {
  const labelsMatch =
    value.honestyLabels.length === TRACKER_HONESTY_LABELS.length &&
    TRACKER_HONESTY_LABELS.every((label, index) => value.honestyLabels[index] === label);
  return (
    <>
      {!labelsMatch && (
        <p className="credibility-warning" role="status">
          서버 정직성 문구 계약 불일치 — 화면은 승인된 고정 문구를 유지합니다.
        </p>
      )}
      <dl className="credibility-facts">
        <div>
          <dt>모델</dt>
          <dd>{value.modelParameters.params.version}</dd>
        </div>
        <div>
          <dt>위험률</dt>
          <dd>{value.hazardParameters.version}</dd>
        </div>
        <div>
          <dt>DAG</dt>
          <dd>{value.graph.version}</dd>
        </div>
        <div>
          <dt>기준일</dt>
          <dd>{value.asOf}</dd>
        </div>
      </dl>
      <p className="credibility-note">
        {value.dataset === null
          ? "감사 가능한 데이터셋 가져오기 기록 없음"
          : `${value.dataset.version} · ${value.dataset.recordCount}건 · 가져오기 ${formatTime(value.dataset.importedAt)}`}
      </p>
      {value.dataset !== null && <HashLine label="데이터 해시" value={value.dataset.sha256} />}
      <p className="credibility-note">
        중앙 ${value.transportAssumptions.centralUsdPerKg}/kg · 민감도 $
        {value.transportAssumptions.hardUsdPerKg}–${value.transportAssumptions.easyUsdPerKg}
        /kg · 공표 가격 기반
      </p>
      <div className="credibility-formulas" aria-label="활성 수식">
        {Object.entries(value.formulas).map(([name, formula]) => (
          <p key={name}>
            <span>{name}</span>
            <code>{formula}</code>
          </p>
        ))}
      </div>
      <p className="credibility-layer-note">
        Layer B: 분리 관측 · 외부 비교/수집은 아래 기능 상태를 따름
      </p>
      <ul className="credibility-flags" aria-label="자동 및 라이브 기능 상태">
        {FEATURE_LABELS.map(([key, label]) => (
          <li key={key}>
            <span>{label}</span>
            <strong className={value.automaticFeatures[key] ? "is-on" : "is-off"}>
              {value.automaticFeatures[key] ? "활성" : "비활성"}
            </strong>
          </li>
        ))}
      </ul>
      <p className="credibility-note">
        보정: {value.currentCalibration?.status ?? "아직 없음"} · 결과 {" "}
        {value.currentCalibration?.sampleCount ?? 0}건 / {value.currentCalibration?.quarterCount ?? 0}분기
      </p>
    </>
  );
}

function ProjectionCard({ remote }: { remote: Remote<ProjectionResponse> }) {
  return (
    <article className="credibility-card">
      <h3>현재 시나리오 투영</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && remote.data.status === "NOT_RUN" && (
        <RemoteMessage text="투영 실행 기록 없음" />
      )}
      {remote.state === "ready" && remote.data.status === "COMPLETED" && (
        <ProjectionBody value={remote.data} />
      )}
    </article>
  );
}

function ProjectionBody({ value }: { value: ProjectionResponse }) {
  const overall = value.output?.results["0"];
  return (
    <>
      <p className="credibility-primary">
        {formatEta(overall?.etaP50)}
        <span>{formatInterval(overall?.etaP10, overall?.etaP90)}</span>
      </p>
      <p className="credibility-note">
        <span>준비도 {formatPercent(overall?.readiness, 1)}</span>
        {" · "}
        <span>검열 {formatPercent(overall?.censoredFraction, 1)}</span>
      </p>
      <p className="credibility-note">
        유효 {value.validSamples?.toLocaleString("ko-KR") ?? "-"} / 요청 {value.requestedSamples?.toLocaleString("ko-KR") ?? "-"} · 무효 {value.invalidSamples ?? "-"}
      </p>
      {value.seed && <p className="credibility-seed">시드 <code>{value.seed}</code></p>}
      {value.inputSha256 && <HashLine label="입력 해시" value={value.inputSha256} />}
    </>
  );
}

function DagCard({ remote }: { remote: Remote<DagResponse> }) {
  const capped = remote.state === "ready" ? remote.data.nodes.filter((node) => node.capped) : [];
  return (
    <article className="credibility-card credibility-card-wide">
      <h3>DAG 의존성 제한</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && (
        <>
          <p className="credibility-note">
            {remote.data.nodes.length}개 노드 · {remote.data.edgeCount}개 간선 · 현재 제한 {capped.length}개
          </p>
          <HashLine label="간선 해시" value={remote.data.edgeSha256} />
          {capped.length === 0 ? (
            <RemoteMessage text="현재 의존성 제한 노드 없음" />
          ) : (
            <div className="credibility-table-wrap">
              <table aria-label="DAG 의존성 제한 노드">
                <caption>DAG 의존성 제한 노드</caption>
                <thead>
                  <tr>
                    <th scope="col">노드</th>
                    <th scope="col">원시</th>
                    <th scope="col">유효</th>
                    <th scope="col">제한</th>
                    <th scope="col">병목 의존성</th>
                  </tr>
                </thead>
                <tbody>
                  {capped.map((node) => (
                    <tr key={node.nodeCode}>
                      <th scope="row">
                        <code>{node.nodeCode}</code>
                        <span>{node.nodeName}</span>
                      </th>
                      <td>{formatPercent(node.rawReadiness, 1)}</td>
                      <td>{formatPercent(node.effectiveReadiness, 1)}</td>
                      <td>{formatPercent(node.dependencyCap, 1)}</td>
                      <td>{node.limitingDependencies.join(", ") || "-"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </article>
  );
}

function BacktestCard({ remote }: { remote: Remote<BacktestResponse> }) {
  return (
    <article className="credibility-card credibility-card-wide">
      <h3>시점 절단 백테스트</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && remote.data.status === "NOT_RUN" && (
        <RemoteMessage text="백테스트 실행 기록 없음" />
      )}
      {remote.state === "ready" && remote.data.status === "COMPLETED" && (
        <BacktestBody value={remote.data} />
      )}
    </article>
  );
}

function BacktestBody({ value }: { value: BacktestResponse }) {
  const report = value.report;
  if (!report) return <RemoteMessage text="완료 보고서가 비어 있습니다" error />;
  const aggregate = report.metrics.filter((metric) => metric.pillar === 0);
  return (
    <>
      <p className="credibility-primary">
        m={report.selectedCandidate.windowM} · k={report.selectedCandidate.kShrink} · δ=
        {report.selectedCandidate.deltaScale}
        <span>보정 목적함수 {report.objectiveScore.toFixed(6)}</span>
      </p>
      <p className="credibility-note">
        보정 {report.calibrationCutoffCount}개 · 잠금 홀드아웃 {report.holdoutCutoffCount}개 · 폴드당 {report.sampleCount.toLocaleString("ko-KR")}표본
      </p>
      {value.seed && <p className="credibility-seed">시드 <code>{value.seed}</code></p>}
      {value.reportSha256 && <HashLine label="보고서 해시" value={value.reportSha256} />}
      <div className="credibility-table-wrap">
        <table aria-label="백테스트 전체 필라 지표">
          <caption>백테스트 전체 필라 지표</caption>
          <thead>
            <tr>
              <th scope="col">지표</th>
              <th scope="col">보정</th>
              <th scope="col">홀드아웃</th>
              <th scope="col">표본</th>
              <th scope="col">상태</th>
            </tr>
          </thead>
          <tbody>
            {aggregate.map((metric) => (
              <tr key={metric.code}>
                <th scope="row"><code>{metric.code}</code></th>
                <td>{formatMetric(metric.calibrationValue)}</td>
                <td>{formatMetric(metric.holdoutValue)}</td>
                <td>{metric.calibrationSamples}/{metric.holdoutSamples}</td>
                <td>{metric.calibrationStatus}/{metric.holdoutStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function PredictionsCard({ remote }: { remote: Remote<PredictionsResponse> }) {
  const cohort = remote.state === "ready" ? remote.data.cohorts[0] : undefined;
  return (
    <article className="credibility-card credibility-card-wide">
      <h3>공표 마이크로 예측</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && (remote.data.status === "EMPTY" || !cohort) && (
        <RemoteMessage text="공표 코호트 없음" />
      )}
      {cohort && (
        <>
          <p className="credibility-note">
            {cohort.cohortKey} · {cohort.issuedOn} · {cohort.predictions.length}개
          </p>
          <HashLine label="코호트 입력 해시" value={cohort.inputSha256} />
          <div className="credibility-table-wrap credibility-prediction-table">
            <table aria-label="공표 마이크로 예측">
              <caption>공표 마이크로 예측</caption>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">불변 명제</th>
                  <th scope="col">기한</th>
                  <th scope="col">확률</th>
                  <th scope="col">정보</th>
                  <th scope="col">결과 / Brier</th>
                </tr>
              </thead>
              <tbody>
                {cohort.predictions.map((prediction) => (
                  <tr key={prediction.statementSha256}>
                    <td>{prediction.cohortRank}</td>
                    <th scope="row">{prediction.statement}</th>
                    <td>{prediction.dueOn}<span>{prediction.horizonMonths}개월</span></td>
                    <td>{formatPercent(prediction.issuedProbability, 1)}</td>
                    <td>{prediction.informationStatus}</td>
                    <td>
                      {prediction.outcome}
                      <span>{prediction.brier === null ? "미채점" : prediction.brier.toFixed(4)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </article>
  );
}

function ScorecardCard({ remote }: { remote: Remote<PredictionScorecardResponse> }) {
  return (
    <article className="credibility-card">
      <h3>Brier 트랙 레코드</h3>
      {remote.state === "loading" && <RemoteMessage text="불러오는 중" />}
      {remote.state === "error" && <RemoteMessage text="불러오지 못했습니다" error />}
      {remote.state === "ready" && (
        <ul className="credibility-scorecard">
          {remote.data.groups.map((group) => (
            <li key={`${group.type}:${group.key}`}>
              <span>{group.type} · {group.key}</span>
              <strong>
                {group.status === "INSUFFICIENT_DATA"
                  ? `결과 부족 · n=${group.sampleCount}`
                  : `Brier ${group.meanBrier?.toFixed(4) ?? "-"} · n=${group.sampleCount}`}
              </strong>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function RemoteMessage({ text, error = false }: { text: string; error?: boolean }) {
  return <p className={error ? "credibility-remote is-error" : "credibility-remote"} role="status">{text}</p>;
}

function HashLine({ label, value }: { label: string; value: string }) {
  return <p className="credibility-hash"><span>{label}</span><code>{value}</code></p>;
}

function useRemote<T>(loader: () => Promise<T>): Remote<T> {
  const [remote, setRemote] = useState<Remote<T>>({ state: "loading" });
  useEffect(() => {
    let current = true;
    setRemote({ state: "loading" });
    loader()
      .then((data) => {
        if (current) setRemote({ state: "ready", data });
      })
      .catch(() => {
        if (current) setRemote({ state: "error" });
      });
    return () => {
      current = false;
    };
  }, [loader]);
  return remote;
}

function formatPercent(value: number | null | undefined, digits: number) {
  return value === null || value === undefined ? "-" : `${(value * 100).toFixed(digits)}%`;
}

function formatEta(value: number | null | undefined) {
  return value === null || value === undefined ? "오른쪽 검열" : `${value.toFixed(1)}년`;
}

function formatInterval(low: number | null | undefined, high: number | null | undefined) {
  return low === null || low === undefined || high === null || high === undefined
    ? "모델 내 80% 구간에 검열 포함"
    : `${low.toFixed(1)}–${high.toFixed(1)}년`;
}

function formatMetric(value: number | null) {
  return value === null ? "자료 부족" : value.toFixed(4);
}

function formatTime(value: string) {
  return value.replace("T", " ").replace(/:\d{2}(?:\.\d+)?Z$/, "Z");
}
