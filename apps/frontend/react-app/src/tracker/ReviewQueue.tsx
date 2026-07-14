import { useRef, useState } from "react";
import { decideReview, getReviewPage, ReviewApiError } from "./api";
import type {
  ReviewCase,
  ReviewPage,
  ReviewPageQuery,
  ReviewReason,
  ReviewStatus,
} from "./api";
import { ReviewCaseCard } from "./ReviewCaseCard";
import { ReviewFilters } from "./ReviewFilters";

const PAGE_SIZE = 25;

/**
 * Token-gated human review queue. The admin token lives only in component
 * state and request headers — never in storage, URLs, logs, or the bundle.
 */
export function ReviewQueue() {
  const [token, setToken] = useState("");
  const [result, setResult] = useState<ReviewPage | null>(null);
  const [status, setStatus] = useState<ReviewStatus>("PENDING");
  const [reason, setReason] = useState<ReviewReason | "">("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [queueError, setQueueError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [cardErrors, setCardErrors] = useState<Record<number, string>>({});
  const requestSequence = useRef(0);

  async function load(next: Partial<ReviewPageQuery> = {}) {
    const query: ReviewPageQuery = {
      status: next.status ?? status,
      reason: next.reason ?? reason,
      page: next.page ?? page,
      size: PAGE_SIZE,
    };
    const requestId = ++requestSequence.current;
    setLoading(true);
    setQueueError(null);
    setCardErrors({});
    try {
      let nextResult = await getReviewPage(token, query);
      let actualPage = query.page;
      if (nextResult.items.length === 0
          && actualPage > 0
          && actualPage >= nextResult.totalPages) {
        actualPage -= 1;
        nextResult = await getReviewPage(token, { ...query, page: actualPage });
      }
      if (requestId !== requestSequence.current) return;
      setPage(actualPage);
      setResult(nextResult);
    } catch (error) {
      if (requestId !== requestSequence.current) return;
      setResult(null);
      setQueueError(
        error instanceof ReviewApiError && error.status === 401
          ? "Unauthorized: check the admin token."
          : "Failed to load the review queue. Try again.",
      );
    } finally {
      if (requestId === requestSequence.current) setLoading(false);
    }
  }

  async function decide(item: ReviewCase, decision: "APPROVE" | "REJECT", note: string | null) {
    setBusyId(item.reviewId);
    setCardErrors(previous => ({ ...previous, [item.reviewId]: "" }));
    try {
      await decideReview(item.reviewId, token, decision, note);
      await load({ page });
    } catch (error) {
      const message =
        error instanceof ReviewApiError && error.status === 409 && error.code === "FROZEN"
          ? "State updates are frozen. Investigate the operations panel before release."
          : error instanceof ReviewApiError && error.status === 409
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
          setPage(0);
          void load({ page: 0 });
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
      {result !== null && (
        <ReviewFilters
          status={status}
          reason={reason}
          disabled={loading || busyId !== null}
          onStatusChange={nextStatus => {
            setStatus(nextStatus);
            setPage(0);
            void load({ status: nextStatus, page: 0 });
          }}
          onReasonChange={nextReason => {
            setReason(nextReason);
            setPage(0);
            void load({ reason: nextReason, page: 0 });
          }}
        />
      )}
      {loading && <p className="review-loading">Loading…</p>}
      {queueError && <p className="review-error">{queueError}</p>}
      {result !== null && (
        <p className="review-page-summary">
          Page {result.totalPages === 0 ? 0 : result.page + 1} of {result.totalPages} · {result.total} total
        </p>
      )}
      {result !== null && result.items.length === 0 && (
        <p className="review-empty">No {status.toLowerCase()} reviews.</p>
      )}
      {result !== null &&
        result.items.map(item => (
          <ReviewCaseCard
            key={item.reviewId}
            item={item}
            busy={busyId === item.reviewId}
            error={cardErrors[item.reviewId] || null}
            onDecide={(decision, note) => void decide(item, decision, note)}
          />
        ))}
      {result !== null && result.totalPages > 1 && (
        <nav className="review-pagination" aria-label="Review pages">
          <button
            type="button"
            disabled={loading || page <= 0}
            onClick={() => void load({ page: page - 1 })}
          >
            Previous page
          </button>
          <span>{page + 1} / {result.totalPages}</span>
          <button
            type="button"
            disabled={loading || page + 1 >= result.totalPages}
            onClick={() => void load({ page: page + 1 })}
          >
            Next page
          </button>
        </nav>
      )}
    </section>
  );
}
