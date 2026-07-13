import { useState } from "react";
import { decideReview, getReviews, ReviewApiError } from "./api";
import type { ReviewCase } from "./api";
import { ReviewCaseCard } from "./ReviewCaseCard";

/**
 * Token-gated human review queue. The admin token lives only in component
 * state and request headers — never in storage, URLs, logs, or the bundle.
 */
export function ReviewQueue() {
  const [token, setToken] = useState("");
  const [cases, setCases] = useState<ReviewCase[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [queueError, setQueueError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [cardErrors, setCardErrors] = useState<Record<number, string>>({});

  async function load() {
    setLoading(true);
    setQueueError(null);
    setCardErrors({});
    try {
      setCases(await getReviews(token));
    } catch (error) {
      setCases(null);
      setQueueError(
        error instanceof ReviewApiError && error.status === 401
          ? "Unauthorized: check the admin token."
          : "Failed to load the review queue. Try again.",
      );
    } finally {
      setLoading(false);
    }
  }

  async function decide(item: ReviewCase, decision: "APPROVE" | "REJECT", note: string | null) {
    setBusyId(item.reviewId);
    setCardErrors(previous => ({ ...previous, [item.reviewId]: "" }));
    try {
      await decideReview(item.reviewId, token, decision, note);
      setCases(previous =>
        previous === null ? previous : previous.filter(c => c.reviewId !== item.reviewId));
    } catch (error) {
      const message =
        error instanceof ReviewApiError && error.status === 409
          ? "Already resolved by another reviewer. Reload the queue."
          : error instanceof ReviewApiError && error.status === 401
            ? "Unauthorized: check the admin token."
            : "Decision failed. Try again.";
      setCardErrors(previous => ({ ...previous, [item.reviewId]: message }));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="review-queue" aria-label="Review queue">
      <form
        className="review-token"
        onSubmit={event => {
          event.preventDefault();
          void load();
        }}
      >
        <label>
          Admin token
          <input
            type="password"
            value={token}
            onChange={event => setToken(event.target.value)}
            autoComplete="off"
          />
        </label>
        <button type="submit" disabled={loading || token.length === 0}>
          Load reviews
        </button>
      </form>
      {loading && <p className="review-loading">Loading…</p>}
      {queueError && <p className="review-error">{queueError}</p>}
      {cases !== null && cases.length === 0 && (
        <p className="review-empty">No pending reviews.</p>
      )}
      {cases !== null &&
        cases.map(item => (
          <ReviewCaseCard
            key={item.reviewId}
            item={item}
            busy={busyId === item.reviewId}
            error={cardErrors[item.reviewId] || null}
            onDecide={(decision, note) => void decide(item, decision, note)}
          />
        ))}
    </section>
  );
}
