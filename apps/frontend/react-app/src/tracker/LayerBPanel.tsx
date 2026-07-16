import type { LayerBMetric } from "./api";

const BASIS_LABEL: Record<string, string> = {
  MEASURED: "측정",
  PUBLISHED_PRICE: "공표 가격 기반 추정",
  CONSTRUCTED: "구성 지수",
};

const METRIC_LABEL: Record<string, string> = {
  ANNUAL_ORBITAL_HUMAN_PERSON_DAYS: "연간 궤도 인류 체류",
  MAX_SIMULTANEOUS_HUMANS_IN_ORBIT: "연중 최대 동시 궤도 인원",
};

const UNIT_LABEL: Record<string, string> = {
  PERSON_DAYS: "인일",
  PEOPLE: "명",
};

const HUMAN_PRESENCE_CODES = new Set(Object.keys(METRIC_LABEL));

function formatValue(entry: LayerBMetric): string {
  if (entry.unit === "PERSON_DAYS") {
    return entry.value.toLocaleString("en-US", {
      minimumFractionDigits: 4,
      maximumFractionDigits: 4,
    });
  }
  return entry.value.toLocaleString("en-US");
}

/**
 * Layer B measured indicators: non-AI observations kept separate from the
 * model (Layer A). Each row shows its basis so a published-price estimate is
 * never read as a measured cost (concept v2.10 honesty).
 */
export function LayerBPanel({ metrics }: { metrics: LayerBMetric[] }) {
  if (metrics.length === 0) {
    return null;
  }
  const includesHumanPresence = metrics.some(entry =>
    HUMAN_PRESENCE_CODES.has(entry.metricCode),
  );
  return (
    <section className="layer-b" aria-label="Layer B 실측 지표">
      <h3 className="layer-b-title">실측 지표 (Layer B)</h3>
      <p className="layer-b-note">
        모델(Layer A)과 분리된 비-AI 관측값입니다. 측정값과 공표 가격 기반 추정을 구분해 표기합니다.
      </p>
      {includesHumanPresence && (
        <p className="layer-b-scope">
          전 세계 궤도 기준 · 준궤도 제외 · 자동 점수 효과 없음
        </p>
      )}
      <ul className="layer-b-list">
        {metrics.map(entry => (
          <li key={entry.metricCode} className="layer-b-row">
            <span className="layer-b-identity">
              <span className="layer-b-name">
                {METRIC_LABEL[entry.metricCode] ?? entry.metricCode}
              </span>
              {METRIC_LABEL[entry.metricCode] && (
                <span className="layer-b-code">{entry.metricCode}</span>
              )}
            </span>
            <span className="layer-b-value">
              {formatValue(entry)} {UNIT_LABEL[entry.unit] ?? entry.unit}
            </span>
            <span className={`layer-b-basis layer-b-basis-${entry.basis.toLowerCase()}`}>
              {BASIS_LABEL[entry.basis] ?? entry.basis}
            </span>
            <span className="layer-b-pillar">{entry.pillarName}</span>
            <span className="layer-b-meta">
              <span>
                관측 <time dateTime={entry.observedOn}>{entry.observedOn}</time> · 검수{" "}
                <time dateTime={entry.accessedOn}>{entry.accessedOn}</time>
              </span>
              <a
                href={entry.sourceUrl}
                target="_blank"
                rel="noreferrer"
                aria-label="출처 보기"
                title={entry.sourceLabel}
              >
                출처 보기
              </a>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
