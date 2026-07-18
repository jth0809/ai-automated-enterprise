# Tracker Phase 4 WP4.3 Monte Carlo Projection Implementation Plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Prior checkpoint: [WP4.2 complete-trend evidence](../../research/tracker-wp42-validation-evidence.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)
>
> **Execution rule:** Execute task by task with TDD. The user prohibited
> subagents and deletion of existing files.

**Goal:** Replace temporary residual ETA bands, when explicitly enabled, with a
deterministic 4,000-sample model-internal projection that perturbs the approved
Phase 4 uncertainty registry, preserves all mathematical constraints, treats
unresolved horizons as right-censored, stores only auditable summaries, and
atomically retains the last completed result on failure.

**Architecture:** Extend weighted regression with the slope covariance needed
for coefficient draws. Pure deterministic samplers create bounded parameter,
mapping, pillar-weight, and shared-DAG-margin draws. `MonteCarloProjector`
combines sampled current DAG readiness with sampled complete-trend slopes and
emits unconditional p10/p50/p90 ranks where censored samples sort at positive
infinity. `ProjectionRepository` persists one immutable input-hash run and seven
summary rows. `SnapshotJob` uses these quantiles only when the separately dark
Phase 4 projection flag is enabled.

**Tech stack:** Java 21, Spring Boot 4.1, JdbcClient, H2 Oracle mode,
Oracle-compatible V17 schema, JUnit 5, Maven 3.9.9, Git.

## Fixed decisions and constraints

- Work only on `codex/tracker-phase4` in `.claude/worktrees/tracker-mvp`.
- Do not use subagents and do not delete existing files, migrations, rows, or
  fixtures.
- Never stage `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- Do not edit V1–V17. WP4.3 uses the already approved V17 run/result schema.
- `TRACKER_PHASE4_PROJECTION_ENABLED=false` remains the default. Enabling the
  local calculation performs no network, LLM, secret, or external write.
- Default sample count is the versioned `mc_samples.central=4000`; allowed
  registry range remains 1,000–10,000.
- The deterministic seed is derived from the complete canonical input hash,
  which includes snapshot date, dataset hash, node state, graph hash/version,
  node-set version, parameter version/registry, and complete-trend audit inputs.
- Identical canonical input must produce an identical seed and byte-stable
  summary. A completed matching run is reused rather than recomputed.
- Regression slope draws use the fitted slope standard error multiplied by a
  bounded `trend_covariance_scale` draw. Rollback dummy coefficients remain
  nuisance terms and are not projected as permanent slope.
- Pillar-local node weights use Dirichlet draws parameterized by approved
  central weights and `node_weight_concentration`, then renormalize exactly to
  one per pillar.
- TRL and EGL maps receive bounded zero-mean perturbations, remain within
  `[0,1]`, remain monotone, and retain L9 exactly `1.0`.
- Every graph edge keeps its reviewed central `delta_e`; all edges share one
  sampled multiplier from `0.75/1.00/1.25`, then clamp to `[0,0.5]`.
- `k` uses a mean-preserving log-normal draw with the versioned log sigma.
- Dormancy start, step, and floor use their bounded-normal registry rows and
  must retain `start >= floor`, nonnegative step, and all unit bounds.
- Invalid numeric or constraint-breaking samples are counted. More than 1% of
  requested samples fails the calculation; censored samples are valid samples.
- A pillar sample is `HORIZON_CENSORED` when `trend_used <= 0`, the ETA is
  non-finite, or it exceeds `asOf + 150 years`. An overall sample is censored
  when any pillar is censored; otherwise it is the maximum pillar ETA.
- Censored samples sort at positive infinity for unconditional quantiles. For
  quantile `q`, rank `ceil(q*N)` is returned only when that rank is finite;
  otherwise the quantile is `null`. Do not calculate quantiles only over the
  finite subset.
- Store no individual draws. Persist run metadata, diagnostics, invalid count,
  and exactly seven result rows (pillars 0–6).
- `projection_result.readiness` is the central DAG-effective readiness.
  `momentum` is the display-only enum from `MomentumService`, not an ETA input.
- A failed or invalid calculation never deactivates the previous completed
  current projection. Snapshot and successful projection replacement share one
  transaction.
- WP4.3 does not calibrate `m`, `k`, or `delta_scale`; WP4.4 owns candidate
  selection and no-leak calibration/holdout evaluation.

---

## Task 1: Regression covariance audit

**Files**

- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/WeightedStepRegression.java`
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/PillarTrendResult.java`
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/CompleteTrendModel.java`
- Modify focused tests in `src/test/java/.../tracker/math/`

**Interfaces**

- Derive the slope variance from residual variance and the slope diagonal of
  `(X'WX)^-1` using the same retained dummy columns as the fit.
- Return a finite nonnegative `slopeStandardError` for every finite fit and zero
  for exact noiseless fits or unavailable fits.
- Preserve all existing point estimates and ETA behavior exactly.

- [x] Write RED tests for noisy slope uncertainty, exact-fit zero uncertainty,
  and step-dummy covariance determinism.
- [x] Implement and propagate slope standard error.
- [x] Run WP4.2 math/snapshot regression tests.
- [x] Commit as `feat(tracker): expose trend covariance for projection`.

---

## Task 2: Deterministic constrained samplers

**Files**

- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/DeterministicRandom.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionSampler.java`
- Create corresponding unit/property tests.

**Interfaces**

- Supply deterministic uniform, Gaussian, gamma, bounded-normal, discrete, and
  Dirichlet draws without external libraries or hidden distribution widths.
- Produce immutable sampled `Params`, `NodeRow` weights, and `CapabilityGraph`
  values from `ModelParameters` registry rows.
- Validate every sample before model evaluation; do not silently keep NaN,
  negative weights, non-monotone maps, or invalid dormancy curves.

- [x] Write RED tests for deterministic sequences and distribution boundaries.
- [x] Write property tests for 1,000 sampled maps, weights, DAG margins, `k`, and
  dormancy curves.
- [x] Implement samplers and immutable sampled inputs.
- [x] Run focused tests and existing graph/readiness tests.
- [x] Commit as `feat(tracker): sample constrained projection inputs`.

---

## Task 3: Pure right-censored Monte Carlo projector

**Files**

- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionInput.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionResult.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionRunResult.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionFingerprint.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/RightCensoredQuantiles.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/MonteCarloProjector.java`
- Create focused tests.

**Interfaces**

- Canonicalize all inputs in stable pillar/node/edge/parameter order and derive
  lowercase SHA-256 plus a nonnegative signed-64-bit seed.
- Produce six pillar sample ETAs and one overall maximum per valid sample.
- Preserve censoring mass in p10/p50/p90 rank calculation and publish
  `censoredFraction` for every row.
- Fail when the invalid fraction is greater than 1%; return diagnostics when it
  is within tolerance.

- [x] Write RED tests for canonical hash/seed stability and input sensitivity.
- [x] Write exact censoring-rank tests where p50 or p90 becomes null.
- [x] Write tests for overall=max, any-pillar censoring, negative slopes, and
  deterministic 1,000-sample byte-stable output.
- [x] Implement the pure projector and diagnostics.
- [x] Run focused and WP4.1/WP4.2 math tests.
- [x] Commit as `feat(tracker): project right-censored ETA distribution`.

---

## Task 4: Idempotent atomic persistence

**Files**

- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionRepository.java`
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/projection/ProjectionService.java`
- Create repository/service integration tests.

**Interfaces**

- Resolve the latest imported tracker dataset hash and reject projection without
  an auditable import.
- Reuse a completed run with the same input hash.
- Insert RUNNING, seven results, then COMPLETED/current in one transaction;
  deactivate the prior current run only after all new rows are valid.
- Read APIs later consume only `COMPLETED` plus `current_result='Y'`.

- [x] Write RED tests for seven-row persistence, unique reuse, quantile nulls,
  and prior-current preservation on invalid output.
- [x] Implement repository and orchestration service.
- [x] Run V17 schema and projection persistence tests.
- [x] Commit as `feat(tracker): persist atomic projection summaries`.

---

## Task 5: Flagged snapshot integration

**Files**

- Modify `apps/backend/springboot-app/src/main/resources/application.yml`
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/SnapshotJob.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/math/SnapshotJobTest.java`
- Create projection-enabled integration tests.

**Interfaces**

- Keep the projection bean absent unless
  `tracker.phase4-projection-enabled=true`.
- With the flag off, retain the validated WP4.2 central/residual fallback and
  create no projection run.
- With the flag on, calculate/reuse one projection from the exact current
  readiness/trend inputs and replace pillar/overall ETA with p50 and p10/p90.
- If a requested quantile is censored, persist null rather than substituting a
  clamp year. Preserve display damping and snapshot rollback semantics.

- [x] Write RED tests for default-off, enabled 1,000-sample integration,
  p10/p50/p90 replacement, reuse, and prior-result preservation.
- [x] Add the dark config flag and optional snapshot integration.
- [x] Run snapshot/controller focused tests.
- [x] Run complete backend/frontend regressions and production build.
- [x] Commit as `feat(tracker): apply Monte Carlo projection to snapshots`.

---

## Task 6: Runtime evidence and WP4.3 checkpoint

**Files**

- Create `docs/research/tracker-wp43-validation-evidence.md`
- Modify this plan's checkboxes
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md` for WP4.3 only

- [x] Run `git diff --check`, projection-package egress scan, full tests, and
  build.
- [x] Run the real 147-claim local corpus with projection explicitly enabled;
  record seed, input hash, invalid/censored fractions, seven rows, API, browser,
  responsive state, and console.
- [x] Verify all live/polling flags remain false and protected files untracked.
- [x] Mark WP4.3 complete while leaving WP4.4–WP4.6 and G4 pending.
- [x] Commit as `docs(tracker): record WP4.3 projection verification`.

## Completion gate

WP4.3 is complete only when approved registry uncertainties drive a deterministic
4,000-sample calculation; every sampled mapping, weight set, graph margin, `k`,
and dormancy curve preserves its constraints; censored mass remains in
unconditional quantiles; an overall sample is the maximum of six finite pillar
samples or censored; one completed input hash owns exactly seven summary rows;
failed calculations preserve the prior current run; enabled snapshots use
p50/p10/p90 while the default flag remains false; no individual samples or new
egress exist; and full backend/frontend/runtime gates pass. This does not
complete WP4.4, Phase 4, or G4.
