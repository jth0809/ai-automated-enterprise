# Tracker WP4.6 and Phase 4 software validation evidence

Date: 2026-07-18 KST
Software status: `SOFTWARE_GATE_PASSED`
G4 status: `PENDING_OBSERVATION`

## Immutable runtime contract

The file-backed local runtime used the reviewed 147-claim corpus and retained
the same completed evidence across a JVM restart:

- projection input
  `9b8aa530b7b936119e43476093243980355a6b6bb2fb06aba7127a7079bb05cd`,
  seed `1984580214469506577`, 4,000 valid and zero invalid samples;
- backtest run ID 1, input
  `ddf6e065a3b90fadcde7d3490c4ccf48b65afa0b932e8d15f9b5a81a7e804fc2`,
  report
  `c3f83c3315eec4f3b609af33a7bfd86bdac8f7f009f0d6b760be94bb9c25c33b`,
  seed `6770845816941252525`;
- first cohort ID 1,
  `micro-v1-2026-07-16-9f9612f9a484`, twelve pending immutable
  predictions, zero scored outcomes;
- calibration `IDENTITY / INSUFFICIENT_CALIBRATION_DATA`, with no invented
  Brier value.

All six public credibility endpoints returned completed or explicit
insufficiency state without starting a calculation. After the evidence run, the
backend was restarted with projection, backtest, issuance, resolution, live
model, and all external pollers disabled. It still returned the same backtest
run and first cohort.

## Automated verification

| Gate | Result |
|---|---|
| Backend full Maven regression | 774 tests, 0 failures, 0 errors, 0 skipped; build success |
| Frontend full Vitest | 84 tests in 19 files, all passed |
| Frontend production build | TypeScript + Vite passed; 49 modules |
| Runtime evidence JSON | Both artifacts parsed; 27 candidates, 408 folds, 35 metrics, 12 predictions; Korean statement text preserved |
| GitOps egress/default verifier | Passed, including exactly one declaration of each Phase 4 dark flag |
| Kustomize render | `kubectl kustomize gitops/apps/backend-springboot` passed |
| Phase 4 egress scan | No network client, URL-opening, API-key, password, or secret API in projection/backtest/prediction/public credibility paths |
| High-confidence secret diff scan | No credential material; the only initial pattern hit was the verifier's own literal detection regex |
| Whitespace check | `git diff --check` passed for committed checkpoints and is rerun at closure |
| PR security gates | [PR #43](https://github.com/jth0809/ai-automated-enterprise/pull/43): [Gitleaks](https://github.com/jth0809/ai-automated-enterprise/actions/runs/29630056908), [Semgrep](https://github.com/jth0809/ai-automated-enterprise/actions/runs/29630056886), and [Trivy](https://github.com/jth0809/ai-automated-enterprise/actions/runs/29630056888) all passed |

The repository's pull-request workflows remain the authoritative Semgrep
`p/ci`, Gitleaks full-history, and Trivy HIGH/CRITICAL gates. Native scanner
binaries were not installed on this Windows host; all three authoritative
workflows passed for PR #43 on 2026-07-18.

## Browser and accessibility verification

The real Spring/Vite application was inspected at
`http://127.0.0.1:5176/` against the persistent runtime:

- Tracker navigation remained present and active.
- The four approved Korean honesty statements were visible.
- All eight automatic/live status rows displayed `비활성`.
- Five aggregate backtest metric rows and twelve published prediction rows
  were visible.
- At an explicit 1280 px viewport, document `scrollWidth` equaled
  `clientWidth`.
- At an explicit 375 px viewport, document `scrollWidth` equaled
  `clientWidth`; all three audit-table wrappers retained their own
  `overflow-x: auto` region.
- A pre-existing long Layer B metric name that exceeded the mobile document by
  2 px was reproduced with a failing test, fixed with
  `overflow-wrap: anywhere`, and reverified.
- Browser warning/error log count was zero.

## Operational boundary

GitOps explicitly declares these values as `false`:

- `TRACKER_PHASE4_PROJECTION_ENABLED`
- `TRACKER_PHASE4_BACKTEST_ENABLED`
- `TRACKER_PHASE4_PREDICTION_ISSUANCE_ENABLED`
- `TRACKER_PHASE4_PREDICTION_RESOLUTION_ENABLED`

`TRACKER_ENABLED` also remains false in the production manifest. No egress,
secret, workload, or production parameter activation was added.

The two-week gate is governed by
[tracker-phase4-g4-observation.md](../runbooks/tracker-phase4-g4-observation.md).
Local execution and software tests do not satisfy elapsed time. Formal launch
therefore remains `PENDING_OBSERVATION`.
