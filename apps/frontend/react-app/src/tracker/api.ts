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
