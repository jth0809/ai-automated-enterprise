import type { TransportProjection } from "./api";

interface TransportEconomicsCardProps {
  projection: TransportProjection;
}

/**
 * Independent Pillar 1 price/cadence scenario. It intentionally keeps sparse,
 * weak-fit, and beyond-horizon results visible instead of fabricating a year.
 */
export function TransportEconomicsCard({ projection }: TransportEconomicsCardProps) {
  const weakFit = projection.qualificationFlags.includes("WEAK_FIT");
  const sensitivity = `${formatEta(
    projection.earliestEtaYear,
    projection.earliestBeyondHorizon,
  )}–${formatEta(projection.latestEtaYear, projection.latestBeyondHorizon)}`;

  return (
    <section
      className="transport-economics"
      aria-labelledby="transport-economics-heading"
    >
      <div className="transport-economics-head">
        <div>
          <h3 id="transport-economics-heading">수송 경제성 시나리오</h3>
          <p className="transport-economics-scope">P1 보조 추정 · 정착 도착연도 예측 아님</p>
        </div>
        <p className="transport-economics-evidence">
          {evidenceLabel(projection)}
          {weakFit && <span> · 적합도 낮음</span>}
        </p>
      </div>

      <p className="transport-economics-assumption">
        중앙 가정 {money(projection.centralTargetUsdPerKg)}/kg · 민감도{" "}
        {money(projection.hardTargetUsdPerKg)}–{money(projection.easyTargetUsdPerKg)}/kg
        {` (${projection.priceBasisYear} USD)`}
      </p>

      <dl className="transport-economics-values">
        <div>
          <dt>중앙 도달 시나리오</dt>
          <dd className="transport-economics-central">{centralEta(projection)}</dd>
        </div>
        <div>
          <dt>가정 민감도 연도</dt>
          <dd>{sensitivity}</dd>
        </div>
      </dl>

      <p
        className="transport-economics-basis"
        title={projection.projectionLabel}
      >
        공표가÷동일 구성 최대 LEO 탑재량 — 실제 원가 아님
      </p>

      {projection.coherenceAlertActive && projection.coherenceState === "DIVERGENT" && (
        <p className="transport-economics-warning" role="alert">
          Layer B와 기술 성숙도 추세 불일치가 2개 분기 연속 확인되어 P1 ETA 구간 확대가
          적용됐습니다.
        </p>
      )}
    </section>
  );
}

function evidenceLabel(projection: TransportProjection): string {
  if (projection.sufficiencyTier === "INSUFFICIENT_DATA") {
    return `자료 부족 · ${projection.observationCount}개 관측`;
  }
  if (projection.sufficiencyTier === "PROVISIONAL") {
    return `잠정 ${projection.observationCount}개 관측`;
  }
  return `확립 ${projection.observationCount}개 관측`;
}

function centralEta(projection: TransportProjection): string {
  if (projection.status === "INSUFFICIENT_DATA") return "자료 부족";
  if (projection.status === "NON_DECLINING") return "하락 추세 미확인";
  if (projection.status === "BEYOND_HORIZON" || projection.centralBeyondHorizon) {
    return "2175+";
  }
  if (projection.centralEtaYear === null) return "자료 부족";
  return String(Math.round(projection.centralEtaYear));
}

function formatEta(value: number | null, beyondHorizon: boolean): string {
  if (beyondHorizon) return "2175+";
  return value === null ? "—" : String(Math.round(value));
}

function money(value: number): string {
  return `$${value.toLocaleString("en-US")}`;
}
