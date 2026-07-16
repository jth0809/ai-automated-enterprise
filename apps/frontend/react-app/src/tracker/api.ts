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
  baseEtaLow: number | null;
  baseEtaHigh: number | null;
  etaLow: number | null;
  etaHigh: number | null;
  coherenceAdjusted: boolean;
  coherenceReportPeriod: string | null;
}

export interface TransportProjection {
  status:
    | "INSUFFICIENT_DATA"
    | "PROVISIONAL"
    | "ESTABLISHED"
    | "NON_DECLINING"
    | "REACHED"
    | "BEYOND_HORIZON";
  sufficiencyTier: "INSUFFICIENT_DATA" | "PROVISIONAL" | "ESTABLISHED";
  qualificationFlags: string[];
  observationCount: number;
  centralTargetUsdPerKg: number;
  easyTargetUsdPerKg: number;
  hardTargetUsdPerKg: number;
  centralEtaYear: number | null;
  earliestEtaYear: number | null;
  latestEtaYear: number | null;
  centralBeyondHorizon: boolean;
  earliestBeyondHorizon: boolean;
  latestBeyondHorizon: boolean;
  priceBasisYear: number;
  basis: "PUBLISHED_PRICE";
  priceMeaning: "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD";
  projectionLabel: string;
  intervalKind: "ASSUMPTION_SENSITIVITY";
  coherenceState: string;
  coherenceAlertActive: boolean;
}

export interface KIndexPoint {
  year: number;
  kValue: number;
}

export interface KIndexSummary {
  status: "CURRENT" | "STALE" | "INSUFFICIENT_DATA";
  latestYear: number | null;
  primaryEnergyTwh: number | null;
  powerWatts: number | null;
  kValue: number | null;
  annualDelta: number | null;
  typeOneGap: number | null;
  typeOneMultiplier: number | null;
  accountingBasis: "SUBSTITUTION" | "USEFUL" | null;
  sourceName: string | null;
  sourceUrl: string | null;
  accessedOn: string | null;
  series: KIndexPoint[];
}

export interface ForecastEstimate {
  status: string;
  year: number | null;
  rawYear: number | null;
  yearLow: number | null;
  yearHigh: number | null;
  relationKind: string;
  label: string;
  detail: string;
  sourceName: string | null;
  sourceUrl: string | null;
  sourceLocator: string | null;
  observedOn: string | null;
  accessedOn: string | null;
  legacy: boolean;
}

export interface ForecastComparisonRow {
  trackCode: "LANDING" | "RETURN" | "SETTLEMENT";
  trackLabel: string;
  definition: string;
  model: ForecastEstimate;
  transport: ForecastEstimate;
  crowd: ForecastEstimate;
  institutional: ForecastEstimate[];
}

export interface ForecastComparison {
  status: "INSUFFICIENT_DATA" | "PARTIAL" | "STALE" | "CURRENT";
  asOfDate: string;
  smoothingWindowDays: number;
  crowdLiveStatus: string;
  rows: ForecastComparisonRow[];
}

export interface TrackerModelParameters {
  version: string;
  epsilon?: number;
  kShrink?: number;
  windowM?: number;
  windowMinYears?: number;
  windowMaxYears?: number;
  defaultDeltaE?: number;
  [key: string]: unknown;
}

export interface TrackerHazardParameters {
  version: string;
  kappaNodeYears: number;
  probabilityFloor: number;
  probabilityCeiling: number;
  horizonsMonths: number[];
  cohortLimit: number;
  pillarLimit: number;
  calibrationMinOutcomes: number;
  calibrationMinQuarters: number;
}

export interface TrackerCalibration {
  calibrationVersion: string;
  method: string;
  status: string;
  sampleCount: number;
  quarterCount: number;
  [key: string]: unknown;
}

export interface PredictionOperations {
  completedCohorts: number;
  pendingPredictions: number;
  duePredictions: number;
  resolvedPredictions: number;
  voidPredictions: number;
  openResolutionConflicts: number;
  openDriftAlerts: number;
  currentCalibrationVersion: string | null;
  currentCalibrationStatus: string | null;
  issuanceFrozen: boolean;
}

export interface MethodologyResponse {
  methodologyVersion: string;
  asOf: string;
  modelParameters: {
    params: TrackerModelParameters;
    uncertainty: Record<string, unknown>;
  };
  hazardParameters: TrackerHazardParameters;
  graph: {
    version: string;
    nodeSetVersion: string;
    edgeSha256: string;
    edgeCount: number;
  };
  dataset: {
    version: string;
    sha256: string;
    nodeSetVersion: string;
    recordCount: number;
    importedAt: string;
  } | null;
  currentCalibration: TrackerCalibration | null;
  predictionOperations: PredictionOperations;
  formulas: Record<string, string>;
  honestyLabels: string[];
  automaticFeatures: Record<string, boolean>;
  transportAssumptions: {
    centralUsdPerKg: number;
    easyUsdPerKg: number;
    hardUsdPerKg: number;
    priceBasis: string;
    intervalKind: string;
  };
}

export interface DagNode {
  nodeCode: string;
  nodeName: string;
  pillar: number;
  rawReadiness: number;
  effectiveReadiness: number;
  dependencyCap: number | null;
  capped: boolean;
  limitingGroups: number[];
  limitingDependencies: string[];
}

export interface DagResponse {
  graphVersion: string;
  nodeSetVersion: string;
  edgeSha256: string;
  edgeCount: number;
  asOf: string;
  edges: unknown[];
  nodes: DagNode[];
}

export interface ProjectionPillarResult {
  pillar: number;
  readiness: number;
  etaP10: number | null;
  etaP50: number | null;
  etaP90: number | null;
  censoredFraction: number;
  momentum: string;
}

export interface ProjectionResponse {
  status: "NOT_RUN" | "COMPLETED";
  runId?: number | null;
  inputSha256?: string | null;
  seed?: string | null;
  requestedSamples?: number | null;
  validSamples?: number | null;
  invalidSamples?: number | null;
  paramsVersion?: string | null;
  graphVersion?: string | null;
  nodeSetVersion?: string | null;
  datasetSha256?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  output?: {
    inputSha256: string;
    seed: number;
    requestedSamples: number;
    validSamples: number;
    invalidSamples: number;
    diagnostics: unknown;
    results: Record<string, ProjectionPillarResult>;
  } | null;
}

export interface BacktestMetric {
  code: string;
  pillar: number;
  calibrationValue: number | null;
  holdoutValue: number | null;
  calibrationSamples: number;
  holdoutSamples: number;
  calibrationStatus: string;
  holdoutStatus: string;
}

export interface BacktestResponse {
  status: "NOT_RUN" | "COMPLETED";
  runId?: number | null;
  inputSha256?: string | null;
  reportSha256?: string | null;
  seed?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  report?: {
    reportVersion: string;
    candidateRegistryVersion: string;
    calibrationCutoffCount: number;
    holdoutCutoffCount: number;
    sampleCount: number;
    selectedCandidate: {
      windowM: number;
      kShrink: number;
      deltaScale: number;
    };
    objectiveScore: number;
    metrics: BacktestMetric[];
  } | null;
}

export interface PublishedPrediction {
  id: number;
  cohortRank: number;
  statement: string;
  nodeCode: string;
  pillar: number;
  targetLevel: number;
  dueOn: string;
  horizonMonths: number;
  issuedProbability: number;
  informationStatus: string;
  outcome: string;
  brier: number | null;
  resolutionStatus: string;
  statementSha256: string;
  [key: string]: unknown;
}

export interface PublishedCohort {
  id: number;
  cohortKey: string;
  inputSha256: string;
  datasetSha256: string;
  nodeSetVersion: string;
  rubricVersion: string;
  hazardParamsVersion: string;
  calibrationVersion: string;
  asOf: string;
  issuedOn: string;
  predictions: PublishedPrediction[];
  createdAt: string;
  completedAt: string;
}

export interface PredictionsResponse {
  status: "EMPTY" | "PUBLISHED";
  cohorts: PublishedCohort[];
}

export interface PredictionScorecardResponse {
  groups: Array<{
    type: string;
    key: string;
    sampleCount: number;
    meanBrier: number | null;
    status: string;
  }>;
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

export async function getTransportEconomics(): Promise<TransportProjection> {
  const res = await fetch("/api/tracker/transport-economics");
  if (!res.ok) {
    throw new Error(`tracker transport economics failed: HTTP ${res.status}`);
  }
  return (await res.json()) as TransportProjection;
}

export async function getKIndex(): Promise<KIndexSummary> {
  const res = await fetch("/api/tracker/k-index");
  if (!res.ok) throw new Error(`tracker K-index failed: HTTP ${res.status}`);
  return (await res.json()) as KIndexSummary;
}

export async function getForecastComparison(): Promise<ForecastComparison> {
  const res = await fetch("/api/tracker/forecast-comparison");
  if (!res.ok) {
    throw new Error(`tracker forecast comparison failed: HTTP ${res.status}`);
  }
  const body: unknown = await res.json();
  if (!isForecastComparison(body)) {
    throw new Error("tracker forecast comparison returned an invalid contract");
  }
  return body;
}

export async function getMethodology(): Promise<MethodologyResponse> {
  return getCredibilityContract<MethodologyResponse>(
    "/api/tracker/methodology",
    "methodology",
    isMethodologyResponse,
  );
}

export async function getDag(): Promise<DagResponse> {
  return getCredibilityContract<DagResponse>(
    "/api/tracker/dag",
    "DAG",
    isDagResponse,
  );
}

export async function getProjection(): Promise<ProjectionResponse> {
  return getCredibilityContract<ProjectionResponse>(
    "/api/tracker/projections/current",
    "projection",
    isProjectionResponse,
  );
}

export async function getBacktest(): Promise<BacktestResponse> {
  return getCredibilityContract<BacktestResponse>(
    "/api/tracker/backtests/latest",
    "backtest",
    isBacktestResponse,
  );
}

export async function getPredictions(): Promise<PredictionsResponse> {
  return getCredibilityContract<PredictionsResponse>(
    "/api/tracker/predictions",
    "predictions",
    isPredictionsResponse,
  );
}

export async function getPredictionScorecard(): Promise<PredictionScorecardResponse> {
  return getCredibilityContract<PredictionScorecardResponse>(
    "/api/tracker/predictions/scorecard",
    "prediction scorecard",
    isPredictionScorecardResponse,
  );
}

async function getCredibilityContract<T>(
  path: string,
  label: string,
  validate: (value: unknown) => value is T,
): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`tracker ${label} failed: HTTP ${res.status}`);
  const body: unknown = await res.json();
  if (!validate(body)) {
    throw new Error(`tracker ${label} returned an invalid contract`);
  }
  return body;
}

function isMethodologyResponse(value: unknown): value is MethodologyResponse {
  return (
    isRecord(value) &&
    typeof value.methodologyVersion === "string" &&
    typeof value.asOf === "string" &&
    isRecord(value.modelParameters) &&
    isRecord(value.modelParameters.params) &&
    typeof value.modelParameters.params.version === "string" &&
    isRecord(value.hazardParameters) &&
    typeof value.hazardParameters.version === "string" &&
    isRecord(value.graph) &&
    typeof value.graph.version === "string" &&
    isRecord(value.predictionOperations) &&
    isRecord(value.formulas) &&
    Array.isArray(value.honestyLabels) &&
    value.honestyLabels.every((label) => typeof label === "string") &&
    isRecord(value.automaticFeatures) &&
    isRecord(value.transportAssumptions)
  );
}

function isDagResponse(value: unknown): value is DagResponse {
  return (
    isRecord(value) &&
    typeof value.graphVersion === "string" &&
    typeof value.edgeSha256 === "string" &&
    typeof value.edgeCount === "number" &&
    Array.isArray(value.nodes) &&
    value.nodes.every(
      (node) =>
        isRecord(node) &&
        typeof node.nodeCode === "string" &&
        typeof node.capped === "boolean" &&
        Array.isArray(node.limitingDependencies),
    )
  );
}

function isProjectionResponse(value: unknown): value is ProjectionResponse {
  if (!isRecord(value) || !["NOT_RUN", "COMPLETED"].includes(String(value.status))) {
    return false;
  }
  return (
    value.status === "NOT_RUN" ||
    (isRecord(value.output) && isRecord(value.output.results))
  );
}

function isBacktestResponse(value: unknown): value is BacktestResponse {
  if (!isRecord(value) || !["NOT_RUN", "COMPLETED"].includes(String(value.status))) {
    return false;
  }
  return (
    value.status === "NOT_RUN" ||
    (isRecord(value.report) &&
      isRecord(value.report.selectedCandidate) &&
      Array.isArray(value.report.metrics))
  );
}

function isPredictionsResponse(value: unknown): value is PredictionsResponse {
  return (
    isRecord(value) &&
    ["EMPTY", "PUBLISHED"].includes(String(value.status)) &&
    Array.isArray(value.cohorts) &&
    value.cohorts.every(
      (cohort) => isRecord(cohort) && Array.isArray(cohort.predictions),
    )
  );
}

function isPredictionScorecardResponse(
  value: unknown,
): value is PredictionScorecardResponse {
  return (
    isRecord(value) &&
    Array.isArray(value.groups) &&
    value.groups.every(
      (group) =>
        isRecord(group) &&
        typeof group.type === "string" &&
        typeof group.key === "string" &&
        typeof group.sampleCount === "number" &&
        typeof group.status === "string",
    )
  );
}

function isForecastComparison(value: unknown): value is ForecastComparison {
  if (!isRecord(value)) return false;
  const statuses = new Set(["INSUFFICIENT_DATA", "PARTIAL", "STALE", "CURRENT"]);
  if (
    typeof value.status !== "string" ||
    !statuses.has(value.status) ||
    typeof value.asOfDate !== "string" ||
    typeof value.smoothingWindowDays !== "number" ||
    typeof value.crowdLiveStatus !== "string" ||
    !Array.isArray(value.rows) ||
    value.rows.length !== 3
  ) {
    return false;
  }
  const tracks = new Set<string>();
  for (const row of value.rows) {
    if (
      !isRecord(row) ||
      !["LANDING", "RETURN", "SETTLEMENT"].includes(String(row.trackCode)) ||
      typeof row.trackLabel !== "string" ||
      typeof row.definition !== "string" ||
      !isForecastEstimate(row.model) ||
      !isForecastEstimate(row.transport) ||
      !isForecastEstimate(row.crowd) ||
      !Array.isArray(row.institutional) ||
      !row.institutional.every(isForecastEstimate)
    ) {
      return false;
    }
    tracks.add(String(row.trackCode));
  }
  return tracks.size === 3;
}

function isForecastEstimate(value: unknown): value is ForecastEstimate {
  if (!isRecord(value)) return false;
  const nullableNumber = (item: unknown) => item === null || typeof item === "number";
  const nullableString = (item: unknown) => item === null || typeof item === "string";
  return (
    typeof value.status === "string" &&
    nullableNumber(value.year) &&
    nullableNumber(value.rawYear) &&
    nullableNumber(value.yearLow) &&
    nullableNumber(value.yearHigh) &&
    typeof value.relationKind === "string" &&
    typeof value.label === "string" &&
    typeof value.detail === "string" &&
    nullableString(value.sourceName) &&
    nullableString(value.sourceUrl) &&
    nullableString(value.sourceLocator) &&
    nullableString(value.observedOn) &&
    nullableString(value.accessedOn) &&
    typeof value.legacy === "boolean"
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function getEvents(limit = 50): Promise<TimelineEvent[]> {
  const res = await fetch(`/api/tracker/events?limit=${limit}`);
  if (!res.ok) throw new Error(`tracker events failed: HTTP ${res.status}`);
  return (await res.json()) as TimelineEvent[];
}

export interface LayerBMetric {
  metricCode: string;
  pillar: number;
  pillarName: string;
  observedOn: string;
  value: number;
  unit: string;
  basis: string;
  sourceLabel: string;
  sourceUrl: string;
  accessedOn: string;
  factSummary: string;
}

export async function getLayerB(): Promise<LayerBMetric[]> {
  const res = await fetch("/api/tracker/layer-b");
  if (!res.ok) throw new Error(`tracker layer-b failed: HTTP ${res.status}`);
  return (await res.json()) as LayerBMetric[];
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
