# Tracker Phase 4 WP4.6 methodology transparency implementation plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Prior checkpoint: [WP4.5 micro-prediction plan](2026-07-16-tracker-wp45-micro-prediction-plan.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)

**Goal:** Publish the active formulas, assumptions, graph limits, completed
projection/backtest evidence, immutable micro-predictions, and Brier track
record through read-only APIs and an accessible React section without enabling
any live model, poller, or automatic Phase 4 job.

**Architecture:** A read-only `CredibilityController` composes immutable current
rows from the existing graph, model, projection, backtest, and prediction
repositories. Empty runs return explicit `NOT_RUN` or `INSUFFICIENT_DATA`
states rather than triggering calculations. React loads the six credibility
contracts independently so one absent report cannot hide the four mandatory
honesty statements or the available evidence. Runtime exports are captured
from these same public contracts, making documentation and UI auditable against
one source.

## Fixed constraints

- Work only on `codex/tracker-phase4`; do not use subagents or delete files.
- Never stage `.claude/`, `application-demo.yml`,
  `application-refbackfill.yml`, `backfill-demo.json`, or
  `vite.wp33.local.config.ts`.
- Public endpoints perform no calculation, mutation, network request, or LLM
  call and expose completed/current results only.
- Automatic projection, backtest, prediction issuance, prediction resolution,
  live model, LL2, official-index, and Metaculus defaults remain false.
- The four approved Korean honesty statements are exact immutable contract
  text and remain visible even when every optional report is absent.
- Tables use their own horizontal scroll region at 375 px; status is conveyed
  by text as well as visual treatment.
- G4 two-week observation remains `PENDING_OBSERVATION` after software
  completion.

## Task 1: Read-only credibility contracts

**Files**

- Create `tracker/api/CredibilityController.java` and focused API tests.
- Extend `PredictionRepository.java` with bounded completed-cohort readers.
- Add focused prediction-reader tests.

- [x] Publish `/methodology`, `/dag`, `/projections/current`,
  `/backtests/latest`, `/predictions`, and `/predictions/scorecard`.
- [x] Include hashes, versions, as-of dates, sample counts, censorship,
  insufficiency states, outcomes, Brier values, and full hazard diagnostics.
- [x] Prove reads cannot start a run and empty state remains explicit.
- [x] Commit as `feat(tracker): publish credibility read APIs`.

## Task 2: Runtime evidence and WP4.5 checkpoint

**Files**

- Create `docs/research/tracker-wp45-first-cohort.json`.
- Create `docs/research/tracker-wp45-validation-evidence.md`.
- Create/update WP4.4 report and validation evidence JSON/Markdown.
- Update the WP4.4/WP4.5 plans and master roadmap.

- [x] Run the approved local projection and backtest with runtime-only flags;
  leave repository defaults false.
- [x] Reissue the 147-claim first cohort twice and prove identical cohort ID,
  key, input hash, calibration version, and one-row cohort cardinality.
- [x] Export all 12 statements, probabilities, horizons, exposure/rate
  diagnostics, hashes, and explicit zero-outcome calibration state.
- [x] Export the complete selected backtest report and comparison metrics.
- [x] Commit as `docs(tracker): publish Phase 4 runtime evidence`.

## Task 3: React methodology and credibility section

**Files**

- Modify `tracker/api.ts`, `TrackerPage.tsx`, and `App.css`.
- Create `MethodologyCredibility.tsx` and focused tests.

- [ ] Show all four exact honesty statements unconditionally.
- [ ] Show current model interval/censorship, DAG caps, active formulas and
  versions, calibration/holdout metrics, predictions/outcomes/Brier, Layer B
  and external comparison status, data freshness, and dark live flags.
- [ ] Cover loading, partial, empty, error, censored, keyboard, heading, and
  375 px table-overflow behavior.
- [ ] Commit as `feat(tracker): add methodology credibility view`.

## Task 4: Phase 4 software gate and handoff

**Files**

- Create `docs/research/tracker-wp46-validation-evidence.md`.
- Create/update a Phase 4/G4 runbook if the existing runbooks do not cover the
  observation gate.
- Update this plan, the design checkpoint, and master roadmap.

- [ ] Run full Maven, Vitest, production build, browser desktop/375 px/console,
  `git diff --check`, secret/SAST scans, egress verifier, and GitOps rendering.
- [ ] Verify all live/automatic flags are false and protected fixtures remain
  untracked.
- [ ] Mark WP4.4–WP4.6 software complete and G4
  `PENDING_OBSERVATION`; do not claim formal launch.
- [ ] Commit as `docs(tracker): complete Phase 4 software gate`.

## Completion gate

WP4.6 is complete only when the six read-only contracts and React section show
the same immutable completed evidence; the exact honesty text is visible in all
data states; no read triggers calculation or egress; absent reports are clearly
identified; first-cohort and backtest exports are reproducible; responsive and
accessibility checks pass; and repository defaults remain dark. Phase 4
software completion does not satisfy the elapsed two-week G4 observation.
