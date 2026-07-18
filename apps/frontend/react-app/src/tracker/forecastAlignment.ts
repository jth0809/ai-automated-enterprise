import type { ProjectionResponse, Summary } from "./api";

export type ForecastAlignmentState =
  | "ALIGNED"
  | "DIFFERENT_RUN"
  | "VERSION_MISMATCH"
  | "UNAVAILABLE";

const ETA_TOLERANCE_YEARS = 0.05;

/** Compares two separately persisted artifacts without implying a shared run. */
export function forecastAlignment(
  summary: Summary | null | undefined,
  projection: ProjectionResponse | null | undefined,
): ForecastAlignmentState {
  if (
    !summary ||
    summary.indicatorStatus !== "COMPLETE" ||
    !summary.snapshotDate ||
    !summary.paramsVersion ||
    !summary.graphVersion ||
    !projection ||
    projection.status !== "COMPLETED" ||
    !projection.paramsVersion ||
    !projection.graphVersion ||
    !projection.output ||
    !("0" in projection.output.results)
  ) {
    return "UNAVAILABLE";
  }

  if (
    summary.paramsVersion !== projection.paramsVersion ||
    summary.graphVersion !== projection.graphVersion
  ) {
    return "VERSION_MISMATCH";
  }

  const snapshotEta = summary.displayedEtaYear;
  const projectionEta = projection.output.results["0"].etaP50;
  if (snapshotEta === null || projectionEta === null) {
    return snapshotEta === projectionEta ? "ALIGNED" : "DIFFERENT_RUN";
  }
  return Math.abs(snapshotEta - projectionEta) <= ETA_TOLERANCE_YEARS
    ? "ALIGNED"
    : "DIFFERENT_RUN";
}
