# Tracker Phase 4 WP4.4 hindcast and calibration implementation plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Prior checkpoint: [WP4.3 projection evidence](../../research/tracker-wp43-validation-evidence.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)
>
> **Execution rule:** Execute task by task with TDD. The user prohibited
> subagents and deletion of existing files.

**Goal:** Replay only information visible at each historical cutoff, select one
predeclared common `m`, `k`, and shared DAG-delta multiplier on the pre-2010
calibration regime, evaluate that locked candidate once on the post-2010
holdout, and persist a deterministic auditable report without automatically
changing production model parameters.

**Architecture:** A pure historical replay builds cutoff-local node state,
effective readiness, state-change features, and weekly history from reviewed
claims. A deterministic hindcast predictor projects readiness twelve months
ahead with 1,000 constrained samples. `CalibrationSelector` receives a
calibration-only type and returns an immutable selected candidate before any
holdout type can be supplied. `BacktestRepository` atomically stores one run,
fold rows, aggregate metrics, and byte-stable JSON; a dark-flagged boot runner
is the only automatic execution path.

**Tech stack:** Java 21, Spring Boot 4.1, JdbcClient, H2 Oracle mode,
Oracle-compatible V18 schema, Jackson, JUnit 5, Maven 3.9.9, Git.

## Fixed decisions and constraints

- Work only on `codex/tracker-phase4` in `.claude/worktrees/tracker-mvp`.
- Do not use subagents and do not delete existing files, migrations, rows, or
  fixtures.
- Never stage `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- Do not edit V1–V17. WP4.4 adds V18 only.
- `TRACKER_PHASE4_BACKTEST_ENABLED=false` remains the default. No request path,
  network, LLM, secret, or external write is used.
- The candidate registry is fixed as `backtest-candidates-v1`:
  `m ∈ {4,6,8}`, `k ∈ {2,4,8}`, and
  `s_delta ∈ {0.75,1.00,1.25}`: exactly 27 candidates.
- Node weights, TRL/EGL mappings, individual edge deltas, rubrics, historical
  levels, and per-pillar windows are not calibration degrees of freedom.
- Ground-truth future readiness uses the reviewed central graph and active
  mapping/weight registry. A candidate changes the model being evaluated, not
  the historical answer key.
- Cutoffs are the first Monday on or after January 1, with a fixed 52-week
  horizon. Calibration cutoffs are 1957–2008 whose target remains on or before
  2009-12-31. Holdout cutoffs start in 2010 and require a completed target week.
  The 2009 cutoff is excluded because its outcome crosses the regime boundary.
- At the 2026-07-13 completed-week baseline this yields 52 calibration cutoffs,
  16 holdout cutoffs, and 408 selected-model fold/pillar rows.
- Every cutoff input, including adaptive-window intervals, sparsity count,
  regime break, history, and node state, is filtered to `<= cutoff`. Calibration
  candidate selection cannot accept a holdout corpus by API type.
- Each fold reports central predicted and observed readiness, logit values,
  advance direction, model-internal p10/p90 readiness, coverage, and central
  ETA. Individual Monte Carlo draws are never stored.
- Calibration objective components are macro-averaged across pillars and then
  equally weighted:

  ```text
  loss = mean(
    readiness_MAE,
    logit_MAE / (2 * abs(logit(epsilon))),
    1 - advance_direction_accuracy,
    abs(coverage_80 - 0.80) / 0.80,
    min(1, eta_volatility_years / 150)
  )
  ```

- A missing ETA-volatility sample is published as `INSUFFICIENT_DATA` and uses
  loss 1.0 for selection, so censoring cannot make a candidate look artificially
  stable. Other sparse pillar metrics remain visible and are not hidden.
- Objective ties within `1e-12` select the candidate closest to the approved
  default `(6,4,1.0)`, then stable numeric order. Holdout values never break a
  tie and never trigger a second selection.
- The selected candidate is a recommendation and backtest identity. WP4.4 does
  not mutate the active `params-v2` or `graph-v1.0`; changing runtime structure
  requires a reviewed versioned registry change.
- Identical canonical input reuses one completed run and byte-identical report.
  A failed run never deactivates the previous current result.

---

## Task 1: V18 storage contract

**Files**

- Create `apps/backend/springboot-app/src/main/resources/db/migration/V18__tracker_phase4_backtest.sql`
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase4V18SchemaTest.java`

**Interfaces**

- Add `backtest_run`, `backtest_fold`, and `backtest_metric` without modifying or
  deleting existing schema objects.
- Store versions, split boundary, selected candidate, report JSON/hash, status,
  and current-result state on the run.
- Constrain fold cohort/status and metric code/status; preserve nullable values
  only for explicitly insufficient observations.

- [x] Write RED schema and constraint tests.
- [x] Add Oracle/H2-compatible V18 migration.
- [x] Run V1–V18 migration tests.
- [x] Commit as `feat(tracker): add backtest audit schema`.

---

## Task 2: Candidate registry, schedule, and no-leak split

**Files**

- Create `tracker/backtest/BacktestCandidate.java`
- Create `tracker/backtest/BacktestSchedule.java`
- Create `tracker/backtest/BacktestFingerprint.java`
- Create focused tests.

**Interfaces**

- Materialize exactly 27 stable candidates and candidate-specific immutable
  params/graphs while retaining central truth inputs.
- Generate annual 52-week calibration and holdout folds with no cross-boundary
  target.
- Canonicalize dataset, node/rubric/parameter/graph versions, graph hash,
  candidate registry, cutoff schedule, sample count, and code/report version.

- [x] Write RED candidate-count/order/default-distance tests.
- [x] Write RED 2010-boundary and latest-completed-target tests.
- [x] Write hash stability and sensitivity tests.
- [x] Implement immutable registry, split types, and fingerprint.
- [x] Commit as `feat(tracker): define leak-proof backtest inputs`.

---

## Task 3: Cutoff replay and hindcast prediction

**Files**

- Create `tracker/backtest/HistoricalClaimReplay.java`
- Create `tracker/projection/HindcastPredictor.java`
- Make only the minimum existing sampler entry point reusable.
- Create focused replay, leakage, and deterministic interval tests.

**Interfaces**

- Replay advancement, rollback, capability-program end, dormancy, and
  restoration in date order using only claims visible at cutoff.
- Build central truth and candidate weekly histories without reading operational
  snapshots from after the cutoff.
- Predict 52-week readiness centrally and with deterministic p10/p90 constrained
  samples; retain null/censored ETA rather than inventing a year.
- Fail if more than 1% of fold samples violate numeric/model constraints.

- [x] Write RED state replay and future-claim tripwire tests.
- [x] Write RED deterministic p10/p90, central forecast, and invalid-limit tests.
- [x] Implement replay and hindcast predictor.
- [x] Run WP4.1–WP4.3 graph/math/projection regression tests.
- [x] Commit as `feat(tracker): replay cutoff-safe hindcasts`.

---

## Task 4: Calibration-only selection and locked holdout

**Files**

- Create `tracker/backtest/BacktestMetric.java`
- Create `tracker/backtest/BacktestReport.java`
- Create `tracker/backtest/BacktestHarness.java`
- Create `tracker/backtest/CalibrationSelector.java`
- Create focused synthetic and property tests.

**Interfaces**

- Evaluate the five fixed metrics and the exact normalized equal-weight loss.
- Select from calibration values only, lock the candidate, then evaluate the
  holdout exactly once.
- Macro-average pillars, expose sample counts and `INSUFFICIENT_DATA`, and apply
  the predeclared tie rule.
- Produce stable JSON-ready ordering and diagnostics for all 27 calibration
  candidates plus selected calibration/holdout folds.

- [x] Write RED exact-objective, sparse-data, and default-distance tie tests.
- [x] Write a holdout tripwire proving selection cannot inspect post-2010 data.
- [x] Implement metrics, candidate selection, and one-pass holdout evaluation.
- [x] Run deterministic synthetic recovery and report-order tests.
- [x] Commit as `feat(tracker): calibrate on pre-2010 hindcasts`.

---

## Task 5: Atomic persistence and dark execution

**Files**

- Create `tracker/backtest/BacktestRepository.java`
- Create `tracker/backtest/BacktestService.java`
- Modify `tracker/config/TrackerConfig.java`
- Modify `apps/backend/springboot-app/src/main/resources/application.yml`
- Create repository/service/runner integration tests.

**Interfaces**

- Validate the configured reviewed corpus against the latest imported dataset,
  then load active nodes, graph, and parameters.
- Reuse a completed matching input hash; otherwise insert RUNNING, all folds,
  aggregate metrics, report/hash, then atomically mark completed/current.
- Preserve the prior current run on any replay, calculation, serialization, or
  persistence failure.
- Run only when `tracker.phase4-backtest-enabled=true`, after the backfill boot
  runner; do not run on a public read request.

- [x] Write RED idempotency, report-hash, row-count, and prior-current tests.
- [x] Write default-off and explicit-on context/runner tests.
- [x] Implement repository, service, flag, and ordered runner.
- [x] Run V18 and service integration tests.
- [x] Commit as `feat(tracker): persist calibrated backtest reports`.

---

## Task 6: Real-corpus report and WP4.4 checkpoint

**Files**

- Create `docs/research/tracker-wp44-backtest-report.json`
- Create `docs/research/tracker-wp44-backtest-report.md`
- Create `docs/research/tracker-wp44-validation-evidence.md`
- Modify this plan's checkboxes.
- Modify the master roadmap for WP4.4 only.

- [x] Run focused and full Maven tests, full Vitest, production build,
  `git diff --check`, and backtest-package egress scan.
- [x] Run the real 147-claim corpus with backtest explicitly enabled and record
  all 27 calibration scores, the selected candidate, locked holdout metrics,
  insufficiency states, input/report hashes, and row counts.
- [x] Re-run identical input and prove idempotent run/report reuse.
- [x] Verify live/polling flags remain false and protected files remain untracked.
- [x] Mark WP4.4 complete while leaving WP4.5–WP4.6 and G4 pending.
- [x] Commit as `docs(tracker): publish WP4.4 backtest evidence`.

## Completion gate

WP4.4 is complete only when every fold is cutoff-local; no calibration target
crosses 2010; exactly the predeclared 27 candidates are scored; the selected
candidate depends only on calibration values; the locked candidate receives one
post-2010 evaluation; all five metrics and sample counts are published without
hiding sparse pillars; identical inputs produce the same hash and JSON; failed
runs preserve the prior current report; the default flag remains false; and the
real corpus, full regression, build, security, and artifact gates pass. This
does not complete WP4.5, Phase 4, or the two-week G4 observation gate.
