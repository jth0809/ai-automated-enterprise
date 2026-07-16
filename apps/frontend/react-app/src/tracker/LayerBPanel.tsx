import type { LayerBMetric } from "./api";

const BASIS_LABEL: Record<string, string> = {
  MEASURED: "측정",
  PUBLISHED_PRICE: "공표 가격 기반 추정",
  CONSTRUCTED: "구성 지수",
};

/**
 * Layer B measured indicators: non-AI observations kept separate from the
 * model (Layer A). Each row shows its basis so a published-price estimate is
 * never read as a measured cost (concept v2.10 honesty).
 */
export function LayerBPanel({ metrics }: { metrics: LayerBMetric[] }) {
  if (metrics.length === 0) {
    return null;
  }
  return (
    <section className="layer-b" aria-label="Layer B 실측 지표">
      <h3 className="layer-b-title">실측 지표 (Layer B)</h3>
      <p className="layer-b-note">
        모델(Layer A)과 분리된 비-AI 관측값입니다. 측정값과 공표 가격 기반 추정을 구분해 표기합니다.
      </p>
      <ul className="layer-b-list">
        {metrics.map(entry => (
          <li key={entry.metricCode} className="layer-b-row">
            <span className="layer-b-name">{entry.metricCode}</span>
            <span className="layer-b-value">
              {entry.value.toLocaleString()} {entry.unit}
            </span>
            <span className={`layer-b-basis layer-b-basis-${entry.basis.toLowerCase()}`}>
              {BASIS_LABEL[entry.basis] ?? entry.basis}
            </span>
            <span className="layer-b-pillar">{entry.pillarName}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
