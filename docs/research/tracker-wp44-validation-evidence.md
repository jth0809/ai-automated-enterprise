# Tracker WP4.4 validation evidence

Date: 2026-07-16 KST

## Runtime execution

The backend ran locally with profiles `test,demo,refbackfill`. Only
`TRACKER_PHASE4_PROJECTION_ENABLED=true` and
`TRACKER_PHASE4_BACKTEST_ENABLED=true` were runtime overrides. Prediction
automation, Launch Library polling, official-index polling, Metaculus polling,
golden live evaluation, and the live model remained disabled.

The 147-claim corpus produced:

- backtest run ID 1, status `COMPLETED`;
- 27 calibration candidates, 408 fold rows, and 35 aggregate metric rows;
- selected `(m=8, k=4.0, delta=0.75)`;
- input hash `ddf6e065a3b90fadcde7d3490c4ccf48b65afa0b932e8d15f9b5a81a7e804fc2`;
- report hash `c3f83c3315eec4f3b609af33a7bfd86bdac8f7f009f0d6b760be94bb9c25c33b`;
- projection 4,000 valid / 0 invalid samples, input hash
  `9b8aa530b7b936119e43476093243980355a6b6bb2fb06aba7127a7079bb05cd`.

The public readers returned `COMPLETED` without starting a calculation. The
complete API response, including all candidates, folds, metrics, versions,
hashes, and projection rows, parses as JSON in
[tracker-wp44-backtest-report.json](tracker-wp44-backtest-report.json).

## Persistent restart and reuse proof

The same reviewed input was also run against the file-backed H2 database
`C:/tmp/tracker-phase4-idempotence-v1`. The first completed execution stored
run ID 1 at 2026-07-16 23:19:52 KST. A separate JVM restart against that same
database returned:

```text
tracker backtest reused id=1
input=ddf6e065a3b90fadcde7d3490c4ccf48b65afa0b932e8d15f9b5a81a7e804fc2
report=c3f83c3315eec4f3b609af33a7bfd86bdac8f7f009f0d6b760be94bb9c25c33b
seed=6770845816941252525
```

The public contract retained the same run ID, input hash, report hash, seed,
and completion timestamp after restart. No second completed run was created.
The first prediction cohort was then issued twice against the same persistent
database and both calls returned cohort ID 1, the same key/input/calibration,
one cohort row, and twelve pending prediction rows.

## Leakage and mutation controls

- Calibration selection accepts only the typed pre-2010 corpus; holdout data is
  supplied after the candidate has been locked.
- The 2009 crossing fold is absent.
- Fold replay filters claims, state, histories, sparsity, intervals, and regime
  information to the cutoff.
- Running the report does not update active model parameters, DAG edges,
  weights, mappings, rubrics, or historical levels.
- The repository defaults for projection and backtest remain false; the public
  read endpoints have no calculator/service dependency.

## Remaining Phase 4 gate work

The final combined Maven, Vitest, production build, browser, security, egress,
and GitOps results are recorded during WP4.6 closure. G4 still requires a
separate elapsed two-week observation and is not satisfied by this report.
