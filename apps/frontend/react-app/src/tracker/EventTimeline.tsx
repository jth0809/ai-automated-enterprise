import type { TimelineEvent, TrackerEvidence } from "./api";

interface EventTimelineProps {
  events: TimelineEvent[];
}

function displayedDate(event: TimelineEvent): string {
  if (event.occurredOnPrecision === "YEAR") return event.occurredOn.slice(0, 4);
  if (event.occurredOnPrecision === "MONTH") return event.occurredOn.slice(0, 7);
  return event.occurredOn;
}

function EvidenceDetails({ evidence }: { evidence: TrackerEvidence }) {
  if (evidence.kind === "HISTORICAL_REFERENCE") {
    return (
      <div className="timeline-evidence timeline-reference">
        <p className="evidence-kind">인간 검수 사실 요약</p>
        {evidence.factSummary !== null && (
          <p className="evidence-summary">{evidence.factSummary}</p>
        )}
        <p className="evidence-source">
          <a href={evidence.url} target="_blank" rel="noreferrer">
            {evidence.sourceLabel}
          </a>
          {evidence.locator !== null && <span>{evidence.locator}</span>}
          {evidence.accessedOn !== null && <span>확인일 {evidence.accessedOn}</span>}
        </p>
      </div>
    );
  }

  return (
    <div className="timeline-evidence timeline-verbatim">
      <p className="evidence-kind">원문 인용</p>
      <p className="evidence-source">
        <a href={evidence.url} target="_blank" rel="noreferrer">
          {evidence.sourceLabel}
        </a>
      </p>
      {evidence.evidenceQuote !== null && (
        <blockquote className="timeline-quote">{evidence.evidenceQuote}</blockquote>
      )}
    </div>
  );
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
            <time dateTime={event.occurredOn}>{displayedDate(event)}</time>
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
          {event.primaryEvidence != null ? (
            <EvidenceDetails evidence={event.primaryEvidence} />
          ) : event.evidenceQuote !== null ? (
            <blockquote className="timeline-quote">{event.evidenceQuote}</blockquote>
          ) : null}
        </li>
      ))}
    </ol>
  );
}
