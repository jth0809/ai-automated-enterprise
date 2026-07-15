import type { KIndexPoint, KIndexSummary } from "./api";

interface KIndexCardProps {
  summary: KIndexSummary;
}

/** Annual civilization-scale energy gauge with no readiness or ETA effect. */
export function KIndexCard({ summary }: KIndexCardProps) {
  const ready =
    summary.status !== "INSUFFICIENT_DATA" &&
    summary.kValue !== null &&
    summary.typeOneGap !== null &&
    summary.typeOneMultiplier !== null;

  return (
    <section className="k-index-card" aria-label="카르다쇼프 K-지수">
      <div className="k-index-head">
        <div>
          <h3 id="k-index-heading">
            {ready
              ? `인류 문명 지수 K = ${summary.kValue!.toFixed(4)}`
              : "인류 문명 지수 K"}
          </h3>
          <p className="k-index-scope">구성 게이지 · 연례 관측 · 자동 효과 없음</p>
        </div>
        {ready && summary.latestYear !== null && (
          <p className="k-index-year">{summary.latestYear}년 관측</p>
        )}
      </div>

      {!ready ? (
        <p className="k-index-empty">관측 데이터 준비 중</p>
      ) : (
        <>
          <div className="k-index-gauge" aria-hidden="true">
            <span style={{ width: `${clamp(summary.kValue!) * 100}%` }} />
          </div>
          <div className="k-index-axis" aria-hidden="true">
            <span>K 0</span>
            <span>Type I · K 1</span>
          </div>

          <p className="k-index-distance">
            Type I까지 ΔK {summary.typeOneGap!.toFixed(4)} · 현재 에너지의 약{" "}
            {Math.round(summary.typeOneMultiplier!)}배
          </p>

          <div className="k-index-detail-grid">
            <div>
              <p className="k-index-basis">{basisLabel(summary.accountingBasis)}</p>
              {summary.annualDelta !== null && (
                <p>연간 변화 {signed(summary.annualDelta)}</p>
              )}
              {summary.primaryEnergyTwh !== null && (
                <p>{summary.primaryEnergyTwh.toLocaleString("ko-KR")} TWh/년</p>
              )}
            </div>
            {summary.series.length > 0 && <KIndexSparkline series={summary.series} />}
          </div>

          <p className="k-index-honesty">
            K 축은 로그 전력 정의이며 선형 에너지 진행률이나 도착 예측이 아닙니다.
          </p>

          {summary.sourceName && summary.sourceUrl && (
            <p className="k-index-source">
              출처{" "}
              <a href={summary.sourceUrl} target="_blank" rel="noreferrer">
                {summary.sourceName}
              </a>
              {summary.accessedOn && <span> · 확인일 {summary.accessedOn}</span>}
            </p>
          )}

          {summary.status === "STALE" && summary.latestYear !== null && (
            <p className="k-index-stale" role="status">
              최신 관측은 {summary.latestYear}년으로 갱신이 필요합니다.
            </p>
          )}
        </>
      )}
    </section>
  );
}

function KIndexSparkline({ series }: { series: KIndexPoint[] }) {
  const recent = series.slice(-20);
  const width = 240;
  const height = 64;
  const padding = 5;
  const values = recent.map((point) => point.kValue);
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);
  const spread = maximum - minimum;
  const points = recent
    .map((point, index) => {
      const x =
        recent.length === 1
          ? width / 2
          : padding + (index / (recent.length - 1)) * (width - padding * 2);
      const normalized = spread === 0 ? 0.5 : (point.kValue - minimum) / spread;
      const y = height - padding - normalized * (height - padding * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
  const label = `최근 ${recent.length}개 연례 K-지수 추이`;

  return (
    <svg
      className="k-index-sparkline"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={label}
    >
      <title>{label}</title>
      <desc>
        {recent[0].year}년부터 {recent.at(-1)!.year}년까지의 로그 전력 기반 K-지수
      </desc>
      <polyline points={points} fill="none" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

function clamp(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function basisLabel(basis: KIndexSummary["accountingBasis"]): string {
  return basis === "USEFUL" ? "유효 에너지 기준" : "대체법 기준";
}

function signed(value: number): string {
  const prefix = value > 0 ? "+" : "";
  return `${prefix}${value.toFixed(4)}`;
}
