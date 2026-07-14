import { useState } from "react";
import type { ReviewCase, ReviewEvidence } from "./api";

interface ReviewCaseCardProps {
  item: ReviewCase;
  busy: boolean;
  error: string | null;
  onDecide: (decision: "APPROVE" | "REJECT", note: string | null) => void;
}

function flukeBadge(item: ReviewCase): string {
  if (item.flukeStatus === "FAILED") return "FILTER FAILED";
  if (item.flukeStatus === "COMPLETE" && item.flukeResult) return item.flukeResult;
  return "FILTER PENDING";
}

function EvidenceDetails({ evidence }: { evidence: ReviewEvidence }) {
  if (evidence.kind === "HISTORICAL_REFERENCE") {
    return (
      <div className="review-evidence review-reference">
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
    <div className="review-evidence review-verbatim">
      <p className="evidence-kind">원문 인용</p>
      <p className="evidence-source">
        <a href={evidence.url} target="_blank" rel="noreferrer">
          {evidence.sourceLabel}
        </a>
      </p>
      {evidence.evidenceQuote !== null && <blockquote>{evidence.evidenceQuote}</blockquote>}
    </div>
  );
}

/**
 * One review case with full evidence context and an explicit two-step
 * decision flow. Rejection requires a note before confirmation is offered.
 */
export function ReviewCaseCard({ item, busy, error, onDecide }: ReviewCaseCardProps) {
  const [pending, setPending] = useState<"APPROVE" | "REJECT" | null>(null);
  const [note, setNote] = useState("");
  const [noteError, setNoteError] = useState(false);

  const trimmedNote = note.trim();
  const pendingReview = item.status === "PENDING";

  function request(decision: "APPROVE" | "REJECT") {
    if (decision === "REJECT" && trimmedNote.length === 0) {
      setNoteError(true);
      return;
    }
    setNoteError(false);
    setPending(decision);
  }

  return (
    <article className="review-card">
      <p className="review-head">
        <span className={`review-priority review-priority-${item.priority}`}>
          P{item.priority}
        </span>
        <span className="review-node">{item.nodeCode}</span>
        <span className="review-node-name">{item.nodeName}</span>
        <span className="review-fluke">{flukeBadge(item)}</span>
      </p>
      <p className="review-meta">
        <span>{item.eventType}</span>
        <span>{item.currentLevel} → {item.claimedLevel ?? "?"}</span>
        <span>{item.scaleType}</span>
        <span>{item.verificationLevel}</span>
        {item.impactScore !== null && <span>impact {item.impactScore}</span>}
        <span>{item.occurredOn}</span>
        {item.actor && <span>{item.actor}</span>}
        <span>{item.reason}</span>
        <span>sources {item.sourceCount}</span>
      </p>
      {item.evidence.map((evidence, i) => (
        <EvidenceDetails key={`${evidence.kind}-${evidence.url}-${i}`} evidence={evidence} />
      ))}
      {!pendingReview && (
        <div className="review-resolution" aria-label="Review resolution">
          <strong>{item.status}</strong>
          {item.reviewerNote && <span>{item.reviewerNote}</span>}
          {item.resolvedAt && <time dateTime={item.resolvedAt}>{item.resolvedAt}</time>}
        </div>
      )}
      {pendingReview && <div className="review-actions">
        <label className="review-note">
          Rejection note
          <textarea
            value={note}
            onChange={event => setNote(event.target.value)}
            maxLength={2000}
            rows={2}
          />
        </label>
        {noteError && <p className="review-error">Rejection note is required.</p>}
        {error && <p className="review-error">{error}</p>}
        {pending === null ? (
          <p className="review-buttons">
            <button type="button" disabled={busy} onClick={() => request("APPROVE")}>
              Approve
            </button>
            <button type="button" disabled={busy} onClick={() => request("REJECT")}>
              Reject
            </button>
          </p>
        ) : (
          <p className="review-buttons">
            <span className="review-confirm-label">
              {pending === "APPROVE" ? "Approve this advance?" : "Reject this event?"}
            </span>
            <button
              type="button"
              disabled={busy}
              onClick={() => onDecide(pending, pending === "REJECT" ? trimmedNote : (trimmedNote || null))}
            >
              Confirm
            </button>
            <button type="button" disabled={busy} onClick={() => setPending(null)}>
              Cancel
            </button>
          </p>
        )}
      </div>}
    </article>
  );
}
