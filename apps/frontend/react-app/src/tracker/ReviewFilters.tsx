import type { ReviewReason, ReviewStatus } from "./api";

interface ReviewFiltersProps {
  status: ReviewStatus;
  reason: ReviewReason | "";
  disabled: boolean;
  onStatusChange: (status: ReviewStatus) => void;
  onReasonChange: (reason: ReviewReason | "") => void;
}

const STATUSES: Array<{ value: ReviewStatus; label: string }> = [
  { value: "PENDING", label: "Pending" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
];

const REASONS: ReviewReason[] = [
  "HIGH_IMPACT",
  "LEVEL_JUMP",
  "FLUKE_MISMATCH",
  "ARRIVAL_CANDIDATE",
  "CIRCUIT_BREAKER",
];

/** Accessible allowlisted controls for the formal review history. */
export function ReviewFilters({
  status,
  reason,
  disabled,
  onStatusChange,
  onReasonChange,
}: ReviewFiltersProps) {
  return (
    <div className="review-filters">
      <div className="review-tabs" role="group" aria-label="Review status">
        {STATUSES.map(item => (
          <button
            key={item.value}
            type="button"
            aria-pressed={status === item.value}
            disabled={disabled}
            onClick={() => onStatusChange(item.value)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <label>
        Reason filter
        <select
          value={reason}
          disabled={disabled}
          onChange={event => onReasonChange(event.target.value as ReviewReason | "")}
        >
          <option value="">All reasons</option>
          {REASONS.map(value => <option key={value} value={value}>{value}</option>)}
        </select>
      </label>
    </div>
  );
}
