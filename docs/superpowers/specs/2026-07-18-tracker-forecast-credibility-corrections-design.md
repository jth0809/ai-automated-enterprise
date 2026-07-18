# Tracker forecast credibility corrections design

Date: 2026-07-18
Status: user-approved
Scope: automatic bottlenecks, forecast provenance, backtest skill diagnostics, and evidence coverage
Branch: `codex/tracker-phase4`

## 1. Purpose

The tracker currently computes its readiness bottleneck automatically, but exposes only one
`bottleneckPillar` field. The frontend reuses that field for both the readiness radar and the ETA
list. This conflates two different questions:

- **Readiness bottleneck:** the pillar with the lowest current readiness.
- **ETA bottleneck:** the pillar that currently prevents or most delays the overall ETA.

The current data therefore correctly identifies P3 as the readiness bottleneck while P4 is the
finite ETA bottleneck that determines the displayed overall year. The correction must make both
concepts automatic and explicit. It must also address the credibility weaknesses found during the
Phase 4 review: persisted snapshot/projection ambiguity, no naive backtest comparison, active and
recommended model confusion, over-broad interval wording, and hidden evidence concentration.

## 2. Goals

1. Derive readiness and ETA bottlenecks from the latest complete six-pillar snapshot on every API
   request; no pillar number or name may be hard-coded.
2. Represent ties and unresolved ETA pillars without silently choosing one pillar.
3. Preserve the existing `bottleneckPillar` response field as a compatibility alias while moving
   the frontend to the explicit fields.
4. Make the main snapshot and the latest Monte Carlo run visibly distinguishable by source date,
   model versions, and alignment state.
5. Publish selected-model, active-model, persistence, and always-no-change backtest comparisons in
   a versioned v2 result without overwriting Phase 4 v1 audit records.
6. State whether the selected model has demonstrated skill over the persistence baseline. A
   completed calculation must not be styled as a successful validation by itself.
7. Rename the 10th-to-90th percentile interval as a **model-internal sensitivity interval** and
   explicitly exclude model-family error, source-selection error, target-threshold uncertainty,
   and external shocks.
8. Publish dynamic evidence-coverage counts so users can see how much of the registry is directly
   supported and how concentrated the evidence is.

## 3. Non-goals and invariants

- Do not activate `LIVE_MODEL`, polling, publication automation, or automatic parameter promotion.
- Do not mutate observed node levels, rubric decisions, historical Phase 4 runs, or existing
  protected local fixtures.
- Do not claim a calibrated real-world confidence or prediction interval.
- Do not replace the trend model with a Bayesian hazard/survival model in this correction. That is
  a future model-family change requiring a separate pre-registered design and more outcomes.
- Do not delete existing files or remove legacy API fields during this phase.
- No new network connection, secret, workload, or GitOps egress rule is required.

## 4. Automatic indicator contract

### 4.1 Backend derivation

Create a focused `TrackerIndicatorService` that receives the latest row for each pillar and returns
an immutable `TrackerIndicators` value. A snapshot set is complete only when it contains exactly
one latest row for every pillar 1 through 6.

The service applies these deterministic rules:

```text
readiness minimum = min(readiness[1..6])
readiness bottlenecks = every pillar within 1e-9 of that minimum

unresolved ETA pillars = every pillar whose eta_year is null
if unresolved ETA pillars are non-empty:
    ETA bottlenecks = unresolved ETA pillars
else:
    ETA maximum = max(eta_year[1..6])
    ETA bottlenecks = every pillar within 0.05 year of that maximum
```

Pillar arrays are sorted ascending for byte-stable responses. Missing pillars produce
`INCOMPLETE_SNAPSHOT`, list the missing pillar numbers, and do not fabricate bottlenecks.

The summary API adds:

```json
{
  "indicatorStatus": "COMPLETE",
  "readinessBottleneckPillars": [3],
  "etaBottleneckPillars": [4],
  "unresolvedEtaPillars": [],
  "missingPillars": [],
  "snapshotDate": "2026-07-18",
  "paramsVersion": "params-v2",
  "graphVersion": "graph-v1"
}
```

`bottleneckPillar` remains the first readiness bottleneck, or `null`, only for compatibility.

### 4.2 Frontend semantics

- `PillarRadar` highlights every readiness bottleneck and labels the concept “현재 준비도 병목”.
- `PillarEtaList` highlights every ETA bottleneck and labels the concept “전체 ETA 병목”.
- Unresolved ETA pillars receive a separate “추세 미해결” state; they take precedence over the
  latest finite ETA because any unresolved pillar makes the overall ETA unresolved.
- A compact `ForecastStatusBar` immediately below the countdown displays both bottleneck sets,
  snapshot date, parameter version, and graph version.
- Ties are rendered as a comma-separated list of pillar codes and names. Color is never the only
  carrier of state.

## 5. Snapshot and projection provenance

The top countdown remains the atomically stored weekly snapshot result. The methodology projection
card remains the latest standalone Monte Carlo audit run. They are not silently treated as one
artifact.

The frontend derives an alignment state from the two public responses:

- `ALIGNED`: parameter and graph versions match, and the finite overall medians differ by no more
  than 0.05 year (or both are unresolved).
- `DIFFERENT_RUN`: versions match but the result differs, indicating separately persisted runs or
  dates.
- `VERSION_MISMATCH`: parameter or graph versions differ.
- `UNAVAILABLE`: either artifact is not available.

The projection card displays its completion time and versions alongside the snapshot date. A
non-aligned state is a visible informational warning, not an application error. No schema migration
is needed merely to pretend that old snapshots and projection runs share an unavailable run ID.

## 6. Versioned backtest credibility correction

### 6.1 Preserve v1

Existing `backtest-report-v1`, `backtest-candidates-v1`, folds, metrics, hashes, and current records
remain immutable. New executions use `backtest-report-v2` and `backtest-candidates-v2`.

### 6.2 Coordinate-system correction

For each candidate, cutoff readiness, predicted readiness, and later observed readiness are replayed
through the same candidate graph. The central graph may be reported as a separate sensitivity
comparison, but may not be mixed into the candidate's error metric.

### 6.3 Model roles and baselines

A new append-only `backtest_model_evaluation` table records aggregate calibration and holdout
metrics for these roles:

- `SELECTED`: calibration-selected candidate.
- `ACTIVE`: currently active runtime parameter set, evaluated independently.
- `PERSISTENCE`: predicts target readiness equals cutoff readiness.
- `ALWAYS_NO_CHANGE`: predicts no state advance; direction metric only.

The table stores the candidate tuple when applicable, metric code, cohort values, sample counts,
and explicit sufficiency status. It does not alter `parameter_set.active`.

The primary skill indicator is:

```text
holdout readiness MAE ratio = selected holdout readiness MAE
                              / persistence holdout readiness MAE
```

- ratio `< 1`: `OUTPERFORMS_PERSISTENCE`
- ratio `>= 1`: `NO_SKILL_VS_PERSISTENCE`
- zero or insufficient baseline samples: `INSUFFICIENT_DATA`

Direction accuracy is shown against `ALWAYS_NO_CHANGE`, but is removed from any “validation
passed” wording. The v2 report continues to disclose the pre-registered selection objective; the
skill gate is an additional diagnostic and never triggers automatic promotion.

### 6.4 API and UI

`GET /api/tracker/backtests/latest` adds `modelEvaluations`, `skillStatus`,
`readinessMaeRatioVsPersistence`, and `selectedMatchesActive`. Older v1 runs return an explicit
`LEGACY_NOT_EVALUATED` state instead of fabricated baseline values.

The UI calls a run “계산 완료” only. It separately shows:

- selected recommendation and active model tuples;
- selected/active/persistence readiness MAE;
- selected and always-no-change direction accuracy;
- sample counts and skill status;
- the warning that the active model has not been promoted automatically.

## 7. Evidence coverage

Create a read-only `EvidenceCoverageService` backed by aggregate SQL over current tracker tables.
It publishes:

- historical candidate count;
- approved claim count;
- distinct candidates used by approved claims;
- active node count and directly mapped active node count;
- claims with exactly one evidence reference;
- verification-level counts.

Counts are computed from persisted rows, never copied from documentation or hard-coded fixtures.
The methodology section displays both counts and ratios, including the single-evidence
concentration. These are coverage diagnostics, not a readiness adjustment.

## 8. Interval and status language

The fixed public wording becomes:

> 현 추세 지속 시나리오 · 모델 내부 민감도 80% 구간

Supporting copy must state that 4,000 simulations measure parameter uncertainty inside the current
model only. It does not imply 4,000 independent observations, empirical coverage, or external
calibration. `OK` is reserved for machine-readable calculation status and is not rendered as a
green validation badge.

## 9. Failure and compatibility behavior

- Missing or partial pillar snapshots return bounded status fields and preserve the last valid ETA;
  no guessed bottleneck is emitted.
- Evidence-coverage query failure affects only the coverage card and does not suppress the tracker.
- Missing v2 backtest evaluation rows render `LEGACY_NOT_EVALUATED`.
- Existing clients can continue reading `bottleneckPillar` and existing backtest fields.
- New arrays are always present and use empty arrays rather than `null`.

## 10. Verification and acceptance criteria

1. Backend unit tests cover automatic P3-to-P4 changes, ties, unresolved ETAs, missing pillars, and
   the legacy alias.
2. Controller tests prove summary metadata and arrays come from repository state, not constants.
3. Backtest tests prove candidate prediction and target use the same graph, active and selected
   roles are separate, baselines are deterministic, and v1 records remain readable.
4. Coverage tests verify dynamic counts and zero-data behavior against H2.
5. Frontend tests prove radar and ETA highlighting can point to different pillars and update solely
   from API data.
6. Frontend tests cover ties, unresolved states, provenance alignment warnings, baseline skill
   statuses, interval copy, and 375 px containment.
7. Full Maven tests, frontend Vitest, production build, `git diff --check`, and local browser smoke
   testing pass before completion is claimed.
8. `LIVE_MODEL` and automatic parameter promotion remain disabled.
