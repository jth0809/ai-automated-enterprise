import type { TimelineEvent } from "./api";

interface EventTimelineProps {
  events: TimelineEvent[];
}

/** Newest-first list of tracked capability events. */
export function EventTimeline({ events }: EventTimelineProps) {
  if (events.length === 0) {
    return <p className="timeline-empty">아직 기록된 사건이 없습니다.</p>;
  }
  return (
    <ol className="timeline" aria-label="Event timeline">
      {events.map((event, i) => (
        <li key={`${event.occurredOn}-${i}`} className="timeline-item">
          <p className="timeline-head">
            <time dateTime={event.occurredOn}>{event.occurredOn}</time>
            <span className="timeline-node">{event.nodeName}</span>
          </p>
          <p className="timeline-meta">
            <span className="timeline-type">{event.eventType}</span>
            {event.levelFrom !== null && event.levelTo !== null && (
              <span className="timeline-levels">
                {event.levelFrom} → {event.levelTo}
              </span>
            )}
            <span className="timeline-verification">{event.verificationLevel}</span>
            <span className="timeline-sources">출처 {event.sourceCount}</span>
          </p>
          {event.evidenceQuote && (
            <blockquote className="timeline-quote">{event.evidenceQuote}</blockquote>
          )}
        </li>
      ))}
    </ol>
  );
}
