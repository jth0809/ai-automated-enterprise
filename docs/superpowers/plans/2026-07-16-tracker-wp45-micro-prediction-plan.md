# Tracker Phase 4 WP4.5 micro-prediction implementation plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Prior checkpoint: [WP4.4 backtest plan](2026-07-16-tracker-wp44-backtest-plan.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)
>
> **Execution rule:** Execute task by task with TDD. The user prohibited
> subagents and deletion of existing files.

**Goal:** Publish a deterministic, immutable first cohort of 6–24 month node
advancement predictions, resolve only matured predicates against confirmed
state transitions, publish Brier scorecards, and add a guarded live probability
calibration loop that never tunes the structural readiness or ETA model.

**Architecture:** V19 extends the existing `prediction` table and adds cohort,
resolution-evidence, calibration, drift-alert, and conflict audit tables. A pure
Gamma–Poisson-equivalent hazard estimator replays the reviewed corpus without
network calls. An issuance service selects at most two informative predictions
per pillar and twelve per cohort, then atomically stores immutable statements
and probabilities. A resolver consumes only confirmed state history. PAVA is
unavailable until the predeclared 30-outcome/four-quarter gate; before that the
published calibration is identity with an explicit insufficiency status.

## Fixed decisions and safety constraints

- Work only on `codex/tracker-phase4` in `.claude/worktrees/tracker-mvp`.
- Do not use subagents or delete existing files, migrations, rows, or fixtures.
- Never stage `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- Do not edit V1–V18. Add V19 only.
- `TRACKER_PHASE4_PREDICTION_ISSUANCE_ENABLED=false` and
  `TRACKER_PHASE4_PREDICTION_RESOLUTION_ENABLED=false` remain defaults. Manual
  execution is token-gated and does not imply automatic live operation.
- `hazard-v1` uses `kappa=4.0` node-years, horizons `{6,12,18,24}` months, and
  issued-probability clamps `[0.02,0.98]`.
- One verified level-increase event counts as one advance even if a reviewed
  claim jumps multiple registered levels. Rollbacks never count as advances.
- Exposure starts on 1957-01-07 and is accumulated in days/365.2425 only while
  the node is ACTIVE and below L8. Dormancy and L8–L9 intervals are excluded.
- The pillar prior is `sum(advances)/sum(exposure)` across its nodes. Node rate
  is `(N_node + kappa * lambda_p)/(E_node + kappa)`.
- Statements target exactly the next registered integer level. Integration
  nodes remain independent predicates and cannot resolve from component maxima.
- Horizon selection maximizes issued `p(1-p)`. Ties use node weight descending,
  then code and horizon ascending. Candidates in `[0.10,0.90]` are selected
  before `LOW_INFORMATION` candidates.
- Calibration remains identity below 30 non-VOID outcomes or four distinct
  resolved quarters. Once eligible, expanding-time folds audit PAVA out of
  sample and a final monotone model is fit on all eligible prior outcomes.
- Drift alerts begin only at the same 30/four-quarter gate. Absolute
  calibration-in-the-large `>=0.15`, recent-minus-prior Brier `>=0.10`, or 75%
  probability-decile concentration creates an alert. Issuance freezes only at
  absolute calibration bias `>=0.25` or Brier deterioration `>=0.20`; frequency
  concentration is diagnostic, preventing small-sample overreaction.
- Brier never changes `params-v2`, DAG edges, weights, mappings, rubrics, or any
  structural model setting.
- `VOID` is manual and token-gated, requires an unadjudicable-predicate reason,
  and is never inferred from cancellation, unfavorable results, or missing data.
- Public readers expose only completed cohorts/calibrations. Repeated identical
  issuance and resolution are idempotent; contradictory resolutions are placed
  in a human conflict queue.

## Task 1: V19 immutable prediction audit schema

**Files**

- Create `apps/backend/springboot-app/src/main/resources/db/migration/V19__tracker_phase4_prediction.sql`.
- Create `TrackerPhase4V19SchemaTest.java`.

- [x] Write RED migration, constraint, uniqueness, and compatibility tests.
- [x] Add versioned hazard parameters, cohort, prediction extensions,
  resolution evidence/conflict, calibration, and drift-alert storage.
- [x] Prove V1–V19 migration compatibility without altering prior migrations.
- [x] Commit as `feat(tracker): add micro-prediction audit schema`.

## Task 2: Cutoff-safe hazard model and candidate policy

**Files**

- Create `tracker/prediction/HazardParameters.java`.
- Create `tracker/prediction/HazardEstimator.java`.
- Create `tracker/prediction/PredictionCandidateSelector.java`.
- Create focused pure tests.

- [x] Write RED exposure, dormancy, rollback, jump, sparse-prior, clamp, and
  horizon tests.
- [x] Write RED 12/cohort, two/pillar, integration, information-band, and stable
  tie-order tests.
- [x] Implement deterministic statement and candidate generation without LLMs.
- [x] Commit as `feat(tracker): derive bounded hazard predictions`.

## Task 3: Atomic cohort issuance

**Files**

- Create `PredictionInputFactory.java`, `PredictionRepository.java`, and
  `PredictionIssuanceService.java` with focused integration tests.

- [ ] Validate the reviewed resource hash/count against its imported audit row.
- [ ] Persist one completed immutable cohort atomically; reuse identical input.
- [ ] Reject duplicate cohort/node/target rows and preserve prior cohorts on
  calculation or persistence failure.
- [ ] Commit as `feat(tracker): issue immutable prediction cohorts`.

## Task 4: Due-date resolution and Brier scorecards

**Files**

- Create `PredictionResolutionService.java`, `PredictionScorecard.java`, and
  focused repository/service tests.

- [ ] Prove first confirmed target transition by due date yields HIT and no
  transition yields MISS.
- [ ] Prove cancellation/data absence never yields VOID; manual VOID requires an
  explicit unadjudicable-predicate audit.
- [ ] Prove exact Brier boundaries, idempotent repeats, and contradictory-outcome
  conflict isolation.
- [ ] Aggregate cohort, horizon, pillar, and overall mean Brier with sample size.
- [ ] Commit as `feat(tracker): resolve and score micro predictions`.

## Task 5: PAVA calibration and bounded drift diagnostics

**Files**

- Create `PavaCalibrator.java`, `PredictionCalibrationService.java`,
  `PredictionDriftDetector.java`, and focused tests.

- [ ] Prove identity behavior below both minimum gates.
- [ ] Prove monotonic PAVA knots, expanding-time out-of-sample diagnostics, and
  preservation of raw/calibrated/issued probability.
- [ ] Prove alerts and freeze thresholds do not mutate structural parameters.
- [ ] Persist and reuse byte-stable calibration input/version records.
- [ ] Commit as `feat(tracker): calibrate prediction probabilities`.

## Task 6: Dark jobs and token-gated manual controls

**Files**

- Create issuance/resolution job components and prediction admin controller.
- Modify `TrackerConfig.java` and `application.yml`.
- Create flag, ShedLock, authorization, and conflict-response tests.

- [ ] Keep both automatic flags default-off and subordinate to
  `TRACKER_ENABLED`.
- [ ] Add token-gated manual issue, resolve, calibrate, status, and manual-VOID
  operations without adding egress.
- [ ] Ensure calculation jobs cannot overlap.
- [ ] Commit as `feat(tracker): gate micro-prediction operations`.

## Task 7: First cohort and WP4.5 checkpoint

**Files**

- Create `docs/research/tracker-wp45-first-cohort.json`.
- Create `docs/research/tracker-wp45-validation-evidence.md`.
- Modify this plan and the master roadmap for WP4.5 only.

- [ ] Run focused/full Maven, Vitest, production build, diff/security/GitOps
  checks, and a network-egress scan.
- [ ] Manually issue the first audited cohort from the 147-claim corpus and
  publish all probabilities, horizons, exposure/rate diagnostics, hashes, and
  insufficiency state.
- [ ] Re-run identical issuance and prove cohort reuse.
- [ ] Verify live/polling flags remain false and protected fixtures untracked.
- [ ] Mark WP4.5 complete while leaving WP4.6 and G4 observation pending.
- [ ] Commit as `docs(tracker): publish first micro predictions`.

## Completion gate

WP4.5 is complete only when every issued statement is deterministic and
immutable; no cohort exceeds twelve or two predictions per pillar; probability
inputs are cutoff-safe; duplicate issuance is idempotent; due resolution uses
confirmed events; VOID cannot hide an unfavorable result; Brier scorecards
publish values and sample sizes; identity/PAVA calibration state is explicit;
drift cannot tune structural model parameters; both automatic jobs remain dark;
and the first real cohort plus full verification evidence is published. This
does not complete WP4.6, Phase 4, or the two-week G4 observation gate.
