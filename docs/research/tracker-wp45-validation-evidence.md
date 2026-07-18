# Tracker WP4.5 first-cohort validation evidence

Date: 2026-07-16 KST

The canonical public-contract capture is
[tracker-wp45-first-cohort.json](tracker-wp45-first-cohort.json).

## Cohort identity and idempotence

Two consecutive token-gated issuance calls returned the same immutable result:

- cohort ID `1`;
- key `micro-v1-2026-07-16-9f9612f9a484`;
- input hash `9f9612f9a484fa0a3172a1d0e8eeb75e7786d4c9ddfe880cf22edd8ae7a3173d`;
- dataset hash `2e1ca5ff6d31be81b3fce3e2b7ce8288a549f44e2754a437eac89a116028f287`;
- calibration `calibration-identity-v1-c4c989b85755`;
- one completed cohort and twelve pending predictions after both calls.

The twelve statement hashes are unique. Every pillar contributes exactly two
predictions, so neither the two-per-pillar nor twelve-per-cohort bound is
exceeded. All statements target the next registered integer level and preserve
their node, due date, probability inputs, exposure/rate diagnostics, input
hash, and statement hash.

## Probability and calibration state

All twelve selected horizons are 24 months. Issued probabilities range from
0.028362 to 0.080538 and all twelve are explicitly `LOW_INFORMATION`. This is
the honest result of the sparse reviewed transition history and the fixed
information-ranking policy; it is not converted into stronger probabilities
to make the display look more active.

There are zero resolved outcomes across zero quarters, so calibration is
`IDENTITY / INSUFFICIENT_CALIBRATION_DATA`. The scorecard is
`INSUFFICIENT_DATA` with sample count zero, no Brier value is invented, no drift
alert is open, and issuance is not frozen. PAVA remains unavailable until both
30 non-VOID outcomes and four resolved quarters exist.

## Operational controls

- Automatic issuance and automatic resolution are false.
- Manual operations require the tracker admin token; the token is not present
  in the evidence artifact.
- Resolution can score only confirmed due-date transitions; missing data and
  cancellations cannot become `VOID`.
- Brier/calibration/drift code cannot mutate structural parameters, weights,
  DAG edges, mappings, or rubrics.
- The combined prediction/API regression suite passed 47/47 on 2026-07-16.

Full repository regression, browser, security, egress, and GitOps checks are
recorded at WP4.6 closure. G4 remains `PENDING_OBSERVATION`.
