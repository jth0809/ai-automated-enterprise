# Tracker Phase 4 G4 two-week observation runbook

Status: `PENDING_OBSERVATION`
Software checkpoint: Phase 4 WP4.1-WP4.6
Required elapsed window: 14 consecutive days

## Purpose

This runbook governs the time-based part of G4. Passing the Phase 4 software
tests, a local dark run, or a deployment by itself does not start or complete
the observation. G4 is complete only after an approved environment has run for
the full window without an unplanned intervention and the evidence below has
been reviewed.

## Start prerequisites

1. The exact reviewed commit is deployed through Flux GitOps; no imperative
   production change is allowed.
2. The deployment records the intended value of `TRACKER_ENABLED` and keeps
   these automatic Phase 4 flags false unless a separate activation review
   explicitly changes them:

   - `TRACKER_PHASE4_PROJECTION_ENABLED`
   - `TRACKER_PHASE4_BACKTEST_ENABLED`
   - `TRACKER_PHASE4_PREDICTION_ISSUANCE_ENABLED`
   - `TRACKER_PHASE4_PREDICTION_RESOLUTION_ENABLED`

3. Live-model, Launch Library, official-index, and Metaculus polling remain
   independently disabled unless their own approved rollout says otherwise.
4. The six public credibility endpoints return without starting calculations:

   - `GET /api/tracker/methodology`
   - `GET /api/tracker/dag`
   - `GET /api/tracker/projections/current`
   - `GET /api/tracker/backtests/latest`
   - `GET /api/tracker/predictions`
   - `GET /api/tracker/predictions/scorecard`

5. The start record contains deployment revision, image digest, dataset/node/
   rubric/parameter/graph versions, current hashes, database migration level,
   start timestamp, expected end timestamp, and the approving reviewer.

## Observation invariants

- Health and readiness probes remain healthy.
- Completed projection, backtest, calibration, and prediction cohort rows do
  not change merely because a public endpoint is read.
- The completed backtest input/report hashes and first prediction cohort key,
  input hash, statement hashes, and probabilities remain immutable.
- No duplicate current run or duplicate cohort is created for an identical
  input.
- No unresolved prediction is silently converted to `VOID`; contradictory
  resolution evidence enters the human conflict queue.
- No automatic job becomes active through configuration drift.
- No new external destination appears in runtime or GitOps egress policy.
- Error-rate, restart, memory, CPU, database growth, unresolved conflict,
  drift-alert, and issuance-freeze observations remain inside the reviewed
  operating envelope.

## Evidence cadence

Capture evidence at start, once per UTC day, and at the end. Each capture must
include:

- timestamp, commit/image identity, pod age/restart count, health;
- all automatic/live feature flags from `/api/tracker/methodology`;
- projection/backtest run IDs, hashes, seeds, status, and completion time;
- prediction cohort count, pending/due/resolved/void counts, calibration
  status, conflict/drift counts, and issuance-freeze state;
- application error count and resource/storage observations;
- Flux reconciliation status and a note that no manual production mutation
  occurred.

Store the captures in the approved operational evidence location. Do not store
admin tokens, secret values, raw environment dumps, or user credentials.

## Intervention and clock-reset rules

An intervention is any unplanned code/configuration/data change, manual job
execution, database repair, pod restart performed to recover the service, or
change to a monitored invariant. Record the incident and restart the 14-day
clock after the reviewed fix is deployed.

Routine read-only observation, scheduled platform maintenance that causes no
tracker intervention, and an already-approved GitOps reconciliation are not
interventions, but must still be recorded. When uncertain, treat the event as
an intervention until reviewed.

## Completion review

G4 may move from `PENDING_OBSERVATION` to complete only when:

1. the start and end timestamps prove at least 14 consecutive days;
2. every daily capture is present;
3. no clock-reset condition occurred;
4. hashes, immutable cohorts, dark flags, and egress boundaries remained as
   declared;
5. the reviewer signs the evidence and a Git commit updates the roadmap.

Until then, Phase 4 software may be complete, but formal launch must not be
claimed.
