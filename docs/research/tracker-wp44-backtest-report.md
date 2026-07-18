# Tracker WP4.4 real-corpus backtest report

Captured on 2026-07-16 from the read-only
`/api/tracker/backtests/latest` and `/api/tracker/projections/current`
contracts. The canonical machine-readable report is
[tracker-wp44-backtest-report.json](tracker-wp44-backtest-report.json).

## Audit identity

| Field | Value |
|---|---|
| Reviewed claims | 147 |
| Dataset | `backfill-v1` / `2e1ca5ff6d31be81b3fce3e2b7ce8288a549f44e2754a437eac89a116028f287` |
| Node/rubric | `nodes-v1.0` / `r2.0` |
| Graph | `graph-v1.0`, 29 edges / `f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e` |
| Active parameters | `params-v2` |
| Candidate registry | `backtest-candidates-v1`, 27 candidates |
| Report version | `backtest-report-v1` |
| Input hash | `ddf6e065a3b90fadcde7d3490c4ccf48b65afa0b932e8d15f9b5a81a7e804fc2` |
| Report hash | `c3f83c3315eec4f3b609af33a7bfd86bdac8f7f009f0d6b760be94bb9c25c33b` |
| Deterministic seed | `6770845816941252525` |

## Locked selection

The calibration regime contains 52 annual cutoffs from 1957 through 2008;
the 2009 cutoff is excluded because its target crosses the regime boundary.
The locked holdout contains 16 cutoffs beginning in 2010. Six pillars across
68 cutoffs produce 408 selected-model fold rows.

Calibration-only selection chose `m=8`, `k=4.0`, and shared DAG-delta scale
`0.75`, with objective `0.27169275617931676`. The approved default
`(6, 4.0, 1.0)` scored `0.2766743177123259`. This is a modest calibration
improvement, not authority to mutate `params-v2` or `graph-v1.0`.

## Aggregate results

| Metric | Calibration | Locked holdout | Samples (cal/holdout) |
|---|---:|---:|---:|
| Readiness MAE | 0.009356 | 0.012040 | 312 / 96 |
| Logit-readiness MAE | 0.087069 | 0.085538 | 312 / 96 |
| Advance-direction accuracy | 10.26% | 31.25% | 312 / 96 |
| Model-internal 80% coverage | 53.53% | 85.42% | 312 / 96 |
| ETA volatility | 16.6903 years | 4.9486 years | 108 / 90 |

All 35 published aggregate metric rows have explicit `OK` status for both
regimes. Per-pillar sample counts and values remain in the JSON report rather
than being hidden by the aggregate.

The low direction accuracy is a material limitation: the model is better at
estimating readiness magnitude than predicting whether a sparse annual step
will occur. Holdout interval coverage is close to, but not proof of, nominal
80% calibration. The interval is internal to the declared model family and
does not cover unknown structural breaks.

## Current projection captured with the report

The runtime-only projection completed 4,000 valid samples with zero invalid
draws. Its input hash is
`9b8aa530b7b936119e43476093243980355a6b6bb2fb06aba7127a7079bb05cd`
and seed is `1984580214469506577`. Overall readiness was 0.147; the projected
internal p10/p50/p90 years were 2089.1 / 2091.2 / 2093.6 with zero right
censoring in this run.

## Interpretation boundary

- The selected tuple is a versioned recommendation and backtest identity only.
- Historical state is the reviewed central mapping, so this evaluates the
  declared tracker rather than establishing an external physical ground truth.
- Annual folds and sparse level transitions make direction metrics unstable.
- No live model, poller, LLM, network source, or production parameter update was
  activated by this run.
