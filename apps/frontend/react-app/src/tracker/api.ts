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

export type EvidenceKind = "VERBATIM" | "HISTORICAL_REFERENCE";
export type OccurredOnPrecision = "DAY" | "MONTH" | "YEAR";

export interface TrackerEvidence {
  kind: EvidenceKind;
  sourceLabel: string;
  url: string;
  evidenceQuote: string | null;
  factSummary: string | null;
  locator: string | null;
  accessedOn: string | null;
}

export interface TimelineEvent {
  occurredOn: string;
  occurredOnPrecision?: OccurredOnPrecision;
  nodeName: string;
  eventType: string;
  levelFrom: number | null;
  levelTo: number | null;
  impactScore: number | null;
  verificationLevel: string;
  sourceCount: number;
  evidenceQuote: string | null;
  primaryEvidence?: TrackerEvidence | null;
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

export type ReviewEvidence = TrackerEvidence;

interface ReviewEvidenceWire {
  kind?: EvidenceKind;
  sourceLabel?: string | null;
  url?: string;
  evidenceQuote?: string | null;
  factSummary?: string | null;
  locator?: string | null;
  accessedOn?: string | null;
  articleTitle?: string | null;
  articleUrl?: string;
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
  resolvedAt: string | null;
}

export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";
export type ReviewReason =
  | "HIGH_IMPACT"
  | "LEVEL_JUMP"
  | "FLUKE_MISMATCH"
  | "ARRIVAL_CANDIDATE"
  | "CIRCUIT_BREAKER";

export interface ReviewPage {
  items: ReviewCase[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  sort: string;
}

export interface ReviewPageQuery {
  status: ReviewStatus;
  reason: ReviewReason | "";
  page: number;
  size: number;
}

/** HTTP failure from the admin review API, keeping the status for the UI. */
export class ReviewApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string | null = null,
  ) {
    super(`review request failed: HTTP ${status}`);
  }
}

export async function getReviews(token: string): Promise<ReviewCase[]> {
  const res = await fetch("/api/tracker/admin/review", {
    headers: { "X-Tracker-Admin-Token": token },
  });
  if (!res.ok) throw await reviewApiError(res);
  const cases = (await res.json()) as Array<
    Omit<ReviewCase, "evidence"> & { evidence: ReviewEvidenceWire[] }
  >;
  return cases.map(normalizeReviewCase);
}

export async function getReviewPage(
  token: string,
  query: ReviewPageQuery,
): Promise<ReviewPage> {
  const params = new URLSearchParams({
    status: query.status,
    page: String(query.page),
    size: String(query.size),
  });
  if (query.reason !== "") params.set("reason", query.reason);
  const res = await fetch(`/api/tracker/admin/reviews?${params.toString()}`, {
    headers: { "X-Tracker-Admin-Token": token },
  });
  if (!res.ok) throw await reviewApiError(res);
  const page = (await res.json()) as Omit<ReviewPage, "items"> & {
    items: Array<Omit<ReviewCase, "evidence"> & { evidence: ReviewEvidenceWire[] }>;
  };
  return { ...page, items: page.items.map(normalizeReviewCase) };
}

function normalizeReviewCase(
  item: Omit<ReviewCase, "evidence"> & { evidence: ReviewEvidenceWire[] },
): ReviewCase {
  return {
    ...item,
    resolvedAt: item.resolvedAt ?? null,
    evidence: item.evidence.map(normalizeReviewEvidence),
  };
}

function normalizeReviewEvidence(evidence: ReviewEvidenceWire): ReviewEvidence {
  const url = evidence.url ?? evidence.articleUrl ?? "";
  return {
    kind: evidence.kind ?? "VERBATIM",
    sourceLabel: evidence.sourceLabel ?? evidence.articleTitle ?? url,
    url,
    evidenceQuote: evidence.evidenceQuote ?? null,
    factSummary: evidence.factSummary ?? null,
    locator: evidence.locator ?? null,
    accessedOn: evidence.accessedOn ?? null,
  };
}

export async function decideReview(
  reviewId: number,
  token: string,
  decision: "APPROVE" | "REJECT",
  note: string | null,
): Promise<void> {
  const res = await fetch(`/api/tracker/admin/reviews/${reviewId}/decision`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Tracker-Admin-Token": token,
    },
    body: JSON.stringify({ decision, note }),
  });
  if (!res.ok) throw await reviewApiError(res);
}

export interface GoldenRun {
  id: number;
  mode: string;
  status: string;
  datasetVersion: string;
  promptVersion: string;
  modelVersion: string;
  totalCount: number;
  matchedCount: number;
  failedCount: number;
  agreement: number | null;
  startedAt: string;
  completedAt: string | null;
}

export interface ControlMetric {
  metricDate: string;
  metricCode: string;
  value: number;
  baselineMean: number | null;
  lowerBound: number | null;
  upperBound: number | null;
  status: string;
  violation: boolean;
  consecutiveViolations: number;
  sampleDays: number;
}

export interface DeadmanFeed {
  source: string;
  status: string;
  intervalSamples: number;
  medianIntervalHours: number | null;
  silenceHours: number | null;
}

export interface DeadmanSummary {
  status: string;
  observedAt: string | null;
  feedCount: number;
  alertCount: number;
  insufficientCount: number;
  feeds: DeadmanFeed[];
}

export interface OpsOverview {
  frozen: boolean;
  freezeReason: string | null;
  freezeTrigger: string | null;
  freezeAt: string | null;
  latestGolden: GoldenRun | null;
  controlMetrics: ControlMetric[];
  deadman: DeadmanSummary | null;
}

export async function getOpsOverview(token: string): Promise<OpsOverview> {
  const res = await fetch("/api/tracker/admin/ops", {
    headers: { "X-Tracker-Admin-Token": token },
  });
  if (!res.ok) throw await reviewApiError(res);
  return (await res.json()) as OpsOverview;
}

export async function releaseOps(token: string, reason: string): Promise<void> {
  const res = await fetch("/api/tracker/admin/ops/release", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Tracker-Admin-Token": token,
    },
    body: JSON.stringify({ reason }),
  });
  if (!res.ok) throw await reviewApiError(res);
}

async function reviewApiError(response: Response): Promise<ReviewApiError> {
  let code: string | null = null;
  try {
    const body = (await response.json()) as { error?: unknown };
    if (typeof body.error === "string" && body.error.length <= 80) code = body.error;
  } catch {
    // The UI reports only the HTTP class when no small safe error code exists.
  }
  return new ReviewApiError(response.status, code);
}
