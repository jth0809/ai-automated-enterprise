import { useEffect, useState } from "react";
import {
  getEvents,
  getLayerB,
  getPillars,
  getSummary,
  getTransportEconomics,
} from "./api";
import type {
  LayerBMetric,
  PillarSummary,
  Summary,
  TimelineEvent,
  TransportProjection,
} from "./api";
import { Countdown } from "./Countdown";
import { EventTimeline } from "./EventTimeline";
import { LayerBPanel } from "./LayerBPanel";
import { PillarEtaList } from "./PillarEtaList";
import { OpsPanel } from "./OpsPanel";
import { PillarRadar } from "./PillarRadar";
import { ReviewQueue } from "./ReviewQueue";
import { TransportEconomicsCard } from "./TransportEconomicsCard";

interface TrackerData {
  summary: Summary;
  pillars: PillarSummary[];
  events: TimelineEvent[];
  layerB: LayerBMetric[];
  transportEconomics: TransportProjection;
}

/** Multiplanetary tracker dashboard: countdown, pillar radar, event timeline. */
export function TrackerPage() {
  const [data, setData] = useState<TrackerData | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getSummary(),
      getPillars(),
      getEvents(50),
      getLayerB(),
      getTransportEconomics(),
    ])
      .then(([summary, pillars, events, layerB, transportEconomics]) => {
        if (!cancelled) {
          setData({ summary, pillars, events, layerB, transportEconomics });
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (failed) {
    return <p className="tracker-empty">추적기 데이터가 아직 없습니다.</p>;
  }
  if (!data) {
    return <p className="tracker-loading">Loading…</p>;
  }
  return (
    <div className="tracker">
      {data.summary.frozen && (
        <p className="tracker-frozen">상태 갱신이 일시 동결되어 있습니다 (검수 중).</p>
      )}
      <Countdown
        etaYear={data.summary.displayedEtaYear}
        etaLow={data.summary.etaLow}
        etaHigh={data.summary.etaHigh}
        label={data.summary.label}
        pillars={data.pillars}
      />
      <PillarRadar pillars={data.pillars} bottleneck={data.summary.bottleneckPillar} />
      <PillarEtaList pillars={data.pillars} bottleneck={data.summary.bottleneckPillar} />
      <EventTimeline events={data.events} />
      <LayerBPanel metrics={data.layerB} />
      <TransportEconomicsCard projection={data.transportEconomics} />
      <details className="review-section">
        <summary>검수 큐 (admin)</summary>
        <ReviewQueue />
      </details>
      <details className="ops-section">
        <summary>운영 상태 (admin)</summary>
        <OpsPanel />
      </details>
    </div>
  );
}
