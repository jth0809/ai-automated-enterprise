# Tracker Phase 4 WP4.2 Complete Trend Model Implementation Plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Prior checkpoint: [WP4.1 dependency DAG evidence](../../research/tracker-wp41-validation-evidence.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)
>
> **Execution rule:** Use `superpowers:executing-plans` task by task. The user
> prohibited subagents and deletion of existing files.

**Goal:** Replace the fixed-window Phase 1 ETA fit with a cutoff-safe complete
trend model that loads one validated active parameter version, uses historical
DAG-effective readiness, adapts each pillar window to real state-change cadence,
shrinks sparse slopes, isolates rollback level shifts, and resets only at
human-approved regime breaks.

**Architecture:** V17 versions the complete Phase 4 parameter and projection
schema without modifying V1–V16. `ModelParameterRepository` loads `params-v2`
fail-closed into an immutable `ModelParameters`. Historical weekly projection
replays node state through the same `graph-v1.0` engine used by current
snapshots. A pure `CompleteTrendModel` computes six preliminary fits, derives a
cutoff-safe cross-pillar prior, applies event-count shrinkage, and returns
auditable fit/used slopes. `SnapshotJob` persists the active parameter, graph,
window, event count, and both slopes. `MomentumService` is a separate display
classification and has no path into ETA.

**Tech stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, H2 Oracle mode,
Oracle-compatible SQL, JUnit 5, Maven 3.9.9, Git.

## Fixed decisions and constraints

- Work only on `codex/tracker-phase4` in `.claude/worktrees/tracker-mvp`.
- Do not use subagents and do not delete existing files or historical rows.
- Never stage `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- Do not edit V1–V16. V17 must contain all schema promised by the approved Phase
  4 design: parameter uncertainty, regime breaks, projection run/result.
- Exactly one active `parameter_set` is required. `Params.defaults()` remains a
  unit-test/bootstrap fallback, not the operational snapshot source.
- `params-v2` keeps the approved central defaults: `epsilon=.01`, `k=4`, `m=6`,
  fixed fallback 10 years, range 4–15 years, and the existing dormancy/ETA maps.
- Valid state changes are confirmed level/status transitions represented by
  `node_state_history` and a confirmed cause event. They include forward moves,
  rollback, cancellation/dormancy, and restoration; article count, duplicate
  reporting, announcement-only, setback-only, and retrospective rows are not
  counted.
- State-change dates and regime breaks must be `<= asOfDate`. No query or
  derived feature may use a later row.
- Adaptive window:

  ```text
  intervals = positive day gaps between ordered distinct state-change dates
  W_p = clamp(ceil(m * median(intervals) / 365.25), 4, 15)
  ```

  Fewer than two positive intervals (fewer than three distinct changes) uses
  `window_fixed_years=10`.
- `n` is the number of valid state changes in the current regime and selected
  trailing window, inclusive at both boundaries.
- All six preliminary finite slopes at the same cutoff form one arithmetic
  prior. If none is finite the prior is `0`; negative and zero priors remain
  negative or zero.
- Shrinkage is exactly
  `(n * trend_fit + k * trend_prior) / (n + k)`. No positivity floor exists.
- Confirmed rollback dates in the fit window become weighted-regression
  post-event level-shift dummies. Dummy coefficients are nuisance terms; the
  calendar slope remains `trend_fit`.
- Only rows in `model_regime_break` with `review_status='APPROVED'` can reset a
  pillar window. 2010 is not seeded as a break.
- Holt/momentum is display-only and cannot be passed to `LogitEta.etaYear`.
- `TRACKER_ENABLED=false` and all live/polling/Phase 4 auto flags remain false.
  No network egress, secret, pod, CronJob, or GitOps resource is added.

---

## Task 1: V17 Phase 4 model registry and schema

**Files**

- Create `apps/backend/springboot-app/src/main/resources/db/migration/V17__tracker_phase4_model.sql`
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase4V17SchemaTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java`

**Interfaces**

- Deactivate `params-v1` and insert the sole active `params-v2` with identical
  central maps and approved complete-model defaults.
- Create version-bound `parameter_uncertainty` rows for WP4.3; seed explicit
  distributions rather than hiding widths in Java constants.
- Create `model_regime_break` with pillar/date/cause event/review note/reviewer,
  parameter version, approval status, and immutable audit timestamps.
- Create `projection_run` and `projection_result` now so V17 is never edited by
  WP4.3.
- Preserve all snapshots and existing parameter history.

- [x] Write schema RED tests for V17 tables, constraints, one active parameter,
  zero seeded regime breaks, and no 2010 auto-break.
- [x] Run `TrackerPhase4V17SchemaTest,TrackerSchemaTest` and record RED.
- [x] Add Oracle-compatible V17 and explicit uncertainty seeds.
- [x] Run focused schema tests and record GREEN.
- [x] Commit as `feat(tracker): version complete trend parameters`.

---

## Task 2: Strict active parameter loading

**Files**

- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/ModelParameters.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/ParameterUncertainty.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/ModelParameterRepository.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/ModelParameterValidator.java`
- Create corresponding repository and validator tests under `src/test/java/.../tracker/math/`

**Interfaces**

- Load exactly one active row and all uncertainty rows in deterministic name
  order.
- Parse TRL/EGL CLOB JSON with the existing Jackson dependency.
- Return an immutable model carrying the existing `Params` value plus immutable
  uncertainty metadata for WP4.3.
- Reject malformed map keys, non-finite values, non-monotone mappings or
  dormancy, invalid windows/clamps/damping, invalid uncertainty ranges, and
  missing/duplicate uncertainty names.

- [x] Write validator tests for every approved bound and map invariant.
- [x] Write repository tests for `params-v2`, zero/multiple active versions, and
  malformed persisted JSON.
- [x] Confirm focused RED.
- [x] Implement immutable records, validation, and repository loading.
- [x] Confirm focused GREEN and existing `ReadinessTest` compatibility.
- [x] Commit as `feat(tracker): load validated model parameters`.

---

## Task 3: Historical DAG-effective weekly replay

**Files**

- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/WeeklyBackfillProjector.java`
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityReadinessService.java` only if a validated graph overload is required
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/WeeklyBackfillProjectorTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java`

**Interfaces**

- Materialize replay-date `NodeRow` values from claim state without mutating
  current `capability_node` rows.
- Load one active graph and parameter version once per projection transaction.
- Calculate each week with `EffectiveReadinessEngine`, persisting raw readiness,
  effective readiness, graph version, and `params-v2`.
- Upgrade the marker to `weekly-projector-v2` and include graph hash/version and
  parameter version so old bare history rebuilds transactionally.
- Existing operational snapshots remain conflict-protected.

- [x] Add RED tests where a high downstream replay level is capped by a low
  predecessor and raw/effective values differ exactly.
- [x] Add graph/version/params marker and immutability assertions.
- [x] Implement historical node materialization and one-graph replay.
- [x] Run projector/loader integration tests and record GREEN.
- [x] Commit as `feat(tracker): replay historical DAG readiness`.

---

## Task 4: Cutoff-safe trend features and complete model

**Files**

- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/StateChangeEvent.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/RegimeBreak.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/TrendFeatureRepository.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/AdaptiveWindow.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/WeightedStepRegression.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/PillarTrendResult.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/CompleteTrendModel.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/MomentumService.java`
- Create focused tests for each class.

**Interfaces**

- Repository queries are bounded by `asOfDate` in SQL, then defensively checked
  in Java; a future row is an error, not silently consumed.
- `AdaptiveWindow` returns years, event count, median interval, and active regime
  start for audit.
- `WeightedStepRegression` performs deterministic exponentially weighted least
  squares over intercept, calendar time, and one post-event dummy per distinct
  rollback date. Rank-deficient dummy columns are removed deterministically.
- `CompleteTrendModel` first calculates six fits, then one finite cross-pillar
  prior, then six shrunk slopes and ETAs.
- `MomentumService` returns only `ACCELERATING`, `STEADY`, `DECELERATING`, or
  `INSUFFICIENT_DATA`; no ETA method accepts it.

- [x] Write adaptive-window tests for median, 4/15 clamps, sparse 10-year
  fallback, duplicate dates, regime reset, and future-row rejection.
- [x] Write shrinkage tests for `n=0`, `n>>k`, and negative prior.
- [x] Write step-regression tests proving a one-time rollback changes the dummy
  coefficient without manufacturing a permanent negative slope.
- [x] Write six-pillar and momentum-separation tests.
- [x] Confirm RED, implement the pure model, and confirm GREEN.
- [x] Commit as `feat(tracker): calculate complete pillar trends`.

---

## Task 5: Snapshot integration and fail-closed audit

**Files**

- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/SnapshotJob.java`
- Reuse `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java` audit persistence contract
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/math/SnapshotJobTest.java`
- Modify API tests only where the now-audited `trend_used` value changes.

**Interfaces**

- `SnapshotJob` loads `ModelParameters` once, computes one DAG readiness result,
  then invokes one six-pillar complete-model calculation.
- Persist `trend_fit`, `trend_used`, `n_events_window`, `window_years`, active
  parameter version, raw/effective readiness, and graph version.
- ETA uses only `trend_used`. Residual intervals remain a clearly temporary
  pre-WP4.3 field and are replaced by Monte Carlo in the next work package.
- Parameter, graph, regime, or feature validation failure rolls back the whole
  current-date snapshot and preserves the previous completed result.

- [x] Write snapshot RED tests for active params-v2 audit fields, sparse
  shrinkage, rollback dummy, and approved break reset.
- [x] Replace `Params.defaults()` in operational snapshot code.
- [x] Persist complete-model audit values and retain freeze/display damping.
- [x] Run snapshot/repository/controller focused tests.
- [x] Run complete backend and frontend regressions.
- [x] Commit as `feat(tracker): apply complete trend model to snapshots`.

---

## Task 6: Evidence and WP4.2 checkpoint

**Files**

- Create `docs/research/tracker-wp42-validation-evidence.md`
- Modify this plan's checkboxes
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md` for WP4.2 only

- [x] Run `git diff --check` and an egress scan of the new math package.
- [x] Record exact schema, parameter, replay, trend, runtime, focused, and full
  regression evidence; record protected untracked files.
- [x] Mark WP4.2 complete while leaving WP4.3–WP4.6 and G4 pending.
- [x] Commit as `docs(tracker): record WP4.2 trend verification`.

## Completion gate

WP4.2 is complete only when current and historical readiness use the same active
DAG/parameter versions, the active parameter row is validated fail-closed,
adaptive windows and event counts are cutoff-safe, rollback dummies and approved
regime resets pass exact tests, sparse slopes use the specified shrinkage without
positive floors, ETA consumes `trend_used` only, all audit fields are persisted,
and the complete backend/frontend suites pass. This does not complete WP4.3,
Phase 4, or G4.
