/**
 * API client for the multiplanetary tracker dashboard.
 * Mirrors the backend contract served by TrackerController.
 */

export interface Summary {
  displayedEtaYear: number | null;
  etaLow: number | null;
  etaHigh: number | null;
  label: string;
  overallReadiness: number | null;
  bottleneckPillar: number | null;
  frozen: boolean;
}

export interface PillarSummary {
  pillar: number;
  name: string;
  readiness: number | null;
  etaYear: number | null;
  momentum: number | null;
}

export interface TimelineEvent {
  occurredOn: string;
  nodeName: string;
  eventType: string;
  levelFrom: number | null;
  levelTo: number | null;
  impactScore: number | null;
  verificationLevel: string;
  sourceCount: number;
  evidenceQuote: string | null;
}

export async function getSummary(): Promise<Summary> {
  const res = await fetch("/api/tracker/summary");
  if (!res.ok) throw new Error(`tracker summary failed: HTTP ${res.status}`);
  return (await res.json()) as Summary;
}

export async function getPillars(): Promise<PillarSummary[]> {
  const res = await fetch("/api/tracker/pillars");
  if (!res.ok) throw new Error(`tracker pillars failed: HTTP ${res.status}`);
  return (await res.json()) as PillarSummary[];
}

export async function getEvents(limit = 50): Promise<TimelineEvent[]> {
  const res = await fetch(`/api/tracker/events?limit=${limit}`);
  if (!res.ok) throw new Error(`tracker events failed: HTTP ${res.status}`);
  return (await res.json()) as TimelineEvent[];
}

export interface ReviewEvidence {
  articleTitle: string | null;
  articleUrl: string;
  evidenceQuote: string;
}

export interface ReviewCase {
  reviewId: number;
  reason: string;
  priority: number;
  flukeStatus: string;
  flukeResult: string | null;
  createdAt: string;
  eventId: number;
  eventType: string;
  occurredOn: string;
  actor: string | null;
  verificationLevel: string;
  impactScore: number | null;
  claimedLevel: number | null;
  nodeCode: string;
  nodeName: string;
  scaleType: string;
  currentLevel: number;
  sourceCount: number;
  evidence: ReviewEvidence[];
  status: string;
  reviewerNote: string | null;
}

/** HTTP failure from the admin review API, keeping the status for the UI. */
export class ReviewApiError extends Error {
  constructor(public readonly status: number) {
    super(`review request failed: HTTP ${status}`);
  }
}

export async function getReviews(token: string): Promise<ReviewCase[]> {
  const res = await fetch("/api/tracker/admin/review", {
    headers: { "X-Tracker-Admin-Token": token },
  });
  if (!res.ok) throw new ReviewApiError(res.status);
  return (await res.json()) as ReviewCase[];
}

export async function decideReview(
  reviewId: number,
  token: string,
  decision: "APPROVE" | "REJECT",
  note: string | null,
): Promise<void> {
  const res = await fetch(`/api/tracker/admin/review/${reviewId}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Tracker-Admin-Token": token,
    },
    body: JSON.stringify({ decision, note }),
  });
  if (!res.ok) throw new ReviewApiError(res.status);
}
