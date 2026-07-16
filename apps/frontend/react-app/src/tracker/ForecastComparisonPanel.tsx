import type {
  ForecastComparison,
  ForecastEstimate,
} from "./api";

interface ForecastComparisonPanelProps {
  comparison: ForecastComparison;
}

/** Four sources remain visibly non-equivalent even when they share a year axis. */
export function ForecastComparisonPanel({
  comparison,
}: ForecastComparisonPanelProps) {
  return (
    <section
      className="forecast-comparison"
      aria-labelledby="forecast-comparison-heading"
    >
      <div className="forecast-comparison-head">
        <div>
          <h3 id="forecast-comparison-heading">화성 예측 비교</h3>
          <p>
            착륙·귀환·자립 정착은 서로 다른 목표입니다. 빈 값과 미승인 상태도
            그대로 표시합니다.
          </p>
        </div>
        <span className={`forecast-status forecast-status-${comparison.status.toLowerCase()}`}>
          {comparisonStatusLabel(comparison.status)}
        </span>
      </div>

      <ul className="forecast-comparison-notes">
        <li>트래커 ETA는 자립 정착 준비도이며 첫 착륙 날짜가 아닙니다.</li>
        <li>
          수송경제 ETA는 중앙 $200/kg, 민감도 $100~$500/kg의 공표가 기반
          가능 조건입니다.
        </li>
        <li>화성 인구 100명 군중예측은 자립 정착보다 약한 프록시입니다.</li>
      </ul>

      {comparison.crowdLiveStatus === "AUTHORIZATION_REQUIRED" && (
        <p className="forecast-comparison-authorization">
          군중예측 실시간 수집은 API·데이터 이용 승인이 확인될 때까지 비활성입니다.
        </p>
      )}

      <div className="forecast-table-wrap">
        <table aria-label="화성 예측 비교">
          <caption>
            기준일 {comparison.asOfDate} · 군중예측 {comparison.smoothingWindowDays}일
            이동평균
          </caption>
          <thead>
            <tr>
              <th scope="col">목표</th>
              <th scope="col">트래커 모델</th>
              <th scope="col">수송경제</th>
              <th scope="col">군중예측</th>
              <th scope="col">기관 목표</th>
            </tr>
          </thead>
          <tbody>
            {comparison.rows.map((row) => (
              <tr key={row.trackCode}>
                <th scope="row">
                  <strong>{row.trackLabel}</strong>
                  <span>{row.definition}</span>
                </th>
                <td><EstimateCell estimate={row.model} /></td>
                <td><EstimateCell estimate={row.transport} /></td>
                <td><EstimateCell estimate={row.crowd} /></td>
                <td>
                  {row.institutional.length === 0 ? (
                    <p className="forecast-empty">검토된 기관 목표 없음</p>
                  ) : (
                    <ul className="forecast-institution-list">
                      {row.institutional.map((estimate, index) => (
                        <li key={`${estimate.sourceName ?? "source"}-${index}`}>
                          <EstimateCell estimate={estimate} />
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function EstimateCell({ estimate }: { estimate: ForecastEstimate }) {
  const status = statusLabel(estimate.status);
  const value = estimateValue(estimate);
  const range = estimate.year !== null ? estimateRange(estimate) : null;
  return (
    <div className="forecast-estimate">
      <div className="forecast-estimate-status-line">
        <span className={`forecast-estimate-status forecast-estimate-${estimate.status.toLowerCase()}`}>
          {status}
        </span>
        {estimate.legacy && <span className="forecast-legacy">과거 목표</span>}
      </div>
      <strong className="forecast-estimate-value">{value}</strong>
      {range !== null && <span className="forecast-estimate-range">범위 {range}</span>}
      {estimate.label !== status && (
        <span className="forecast-estimate-label">{estimate.label}</span>
      )}
      <p>{estimate.detail}</p>
      {estimate.sourceName !== null && estimate.sourceUrl !== null && (
        <p className="forecast-source">
          <a href={estimate.sourceUrl} target="_blank" rel="noreferrer">
            {estimate.sourceName}
          </a>
          {estimate.sourceLocator !== null && <> · {estimate.sourceLocator}</>}
          {estimate.observedOn !== null && <> · 관측 {estimate.observedOn}</>}
          {estimate.accessedOn !== null && <> · 확인 {estimate.accessedOn}</>}
        </p>
      )}
    </div>
  );
}

function estimateValue(estimate: ForecastEstimate): string {
  if (estimate.year !== null) return `${formatYear(estimate.year)}년`;
  return estimateRange(estimate) ?? "—";
}

function estimateRange(estimate: ForecastEstimate): string | null {
  if (estimate.yearLow === null && estimate.yearHigh === null) return null;
  const low = estimate.yearLow === null ? "—" : formatYear(estimate.yearLow);
  const high = estimate.yearHigh === null ? "—" : formatYear(estimate.yearHigh);
  return `${low}–${high}년`;
}

function formatYear(value: number): string {
  return Number.isInteger(value) ? value.toFixed(0) : value.toFixed(1);
}

function comparisonStatusLabel(status: ForecastComparison["status"]): string {
  switch (status) {
    case "CURRENT": return "현재 자료";
    case "STALE": return "관측 갱신 필요";
    case "PARTIAL": return "부분 자료";
    case "INSUFFICIENT_DATA": return "자료 부족";
  }
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    CURRENT: "현재 관측",
    STALE: "오래된 관측",
    AWAITING_AUTHORIZATION: "API 승인 대기",
    UNDATED: "연도 미제시",
    PRECURSOR: "선행 목표",
    LEGACY: "과거 기록",
    NOT_APPLICABLE: "해당 없음",
    INSUFFICIENT_DATA: "자료 부족",
    SUPPORTING: "보조 지표",
    DIRECT_PROXY: "정착 모델 프록시",
    QUESTION_NOT_SELECTED: "질문 미선정",
  };
  return labels[status] ?? status;
}
