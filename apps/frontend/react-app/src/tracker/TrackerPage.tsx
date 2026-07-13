import { useEffect, useState } from "react";
import { getEvents, getPillars, getSummary } from "./api";
import type { PillarSummary, Summary, TimelineEvent } from "./api";
import { Countdown } from "./Countdown";
import { EventTimeline } from "./EventTimeline";
import { PillarRadar } from "./PillarRadar";
import { ReviewQueue } from "./ReviewQueue";

interface TrackerData {
  summary: Summary;
  pillars: PillarSummary[];
  events: TimelineEvent[];
}

/** Multiplanetary tracker dashboard: countdown, pillar radar, event timeline. */
export function TrackerPage() {
  const [data, setData] = useState<TrackerData | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([getSummary(), getPillars(), getEvents(50)])
      .then(([summary, pillars, events]) => {
        if (!cancelled) setData({ summary, pillars, events });
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
      />
      <PillarRadar pillars={data.pillars} bottleneck={data.summary.bottleneckPillar} />
      <EventTimeline events={data.events} />
      <details className="review-section">
        <summary>검수 큐 (admin)</summary>
        <ReviewQueue />
      </details>
    </div>
  );
}
