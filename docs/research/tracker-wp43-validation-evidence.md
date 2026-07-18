# Tracker WP4.3 Monte Carlo projection validation evidence

Validation date: 2026-07-16 KST

Branch: `codex/tracker-phase4`

Scope: deterministic constrained sampling, right-censored projection,
idempotent seven-row persistence, snapshot integration, API and browser runtime
verification.

## Result

WP4.3 is complete. When `tracker.phase4-projection-enabled=true`, the weekly
snapshot path runs or reuses a deterministic 4,000-sample projection. It stores
only six pillar summaries and one overall summary. The default remains `false`,
and no live model, poller, external network request, or secret is required.

The calculation preserves the approved constraints for TRL/EGL maps, pillar
weights, DAG margins, event shrinkage, and dormancy. Censored draws remain in
the unconditional quantile ranks. Failed runs do not replace the last completed
current result.

## Implementation commits

| Commit | Result |
|---|---|
| `de06d82` | Regression slope covariance exposed without changing point estimates |
| `aa200f1` | Deterministic constrained parameter and model samplers |
| `2239aad` | Pure right-censored Monte Carlo projection and canonical fingerprint |
| `2913c4d` | Idempotent atomic run and exactly seven summary rows |
| `f34db23` | Dark-flagged snapshot integration |
| `5769782` | Compact run-level audit log |

## Automated verification

| Gate | Result |
|---|---:|
| Projector plus WP4.1/WP4.2 focused regression | 67/67 |
| Projection repository/service/V17 focused tests | 12/12 |
| Final snapshot/projection focused tests | 18/18 |
| Audit-log follow-up focused tests | 6/6 |
| Maven full regression | 676/676 |
| Vitest full regression | 79/79 |
| TypeScript and Vite production build | PASS |
| `git diff --check` | PASS |
| Projection package egress scan | 0 matches |

The full backend, frontend, and build gates ran after snapshot integration. The
only subsequent source change was compact audit logging; its focused service and
snapshot integration suite passed 6/6.

## Real 147-claim corpus

Runtime profile: `test,demo,refbackfill`, with projection explicitly enabled and
all external collection/live-model flags explicitly disabled.

- Health: `UP`
- Dataset: `backfill-v1`, 147 reviewed claims
- Input hash: `9b8aa530b7b936119e43476093243980355a6b6bb2fb06aba7127a7079bb05cd`
- Seed: `1984580214469506577`
- Requested samples: 4,000
- Invalid samples: 0
- Stored result rows: exactly 7
- Censored fraction: 0.0 for every row

| Row | Effective readiness | p10 | p50 | p90 | Momentum |
|---|---:|---:|---:|---:|---|
| Overall | 0.1470 | 2089.1 | 2091.2 | 2093.6 | `INSUFFICIENT_DATA` |
| P1 Transport | 0.4624 | 2068.4 | 2072.2 | 2076.5 | `STEADY` |
| P2 Life support | 0.1842 | 2082.8 | 2085.0 | 2087.4 | `STEADY` |
| P3 Habitation | 0.1470 | 2083.6 | 2086.7 | 2090.1 | `STEADY` |
| P4 Resources and energy | 0.1920 | 2087.9 | 2090.0 | 2092.2 | `STEADY` |
| P5 Robotics and autonomy | 0.2991 | 2062.1 | 2064.3 | 2066.6 | `STEADY` |
| P6 Economy and governance | 0.2667 | 2086.9 | 2090.0 | 2093.3 | `STEADY` |

The summary API returned p50 `2091.2`, p10 `2089.1`, p90 `2093.6`, overall
effective readiness `0.147`, and bottleneck pillar 3. The pillars API returned
the same six p10/p50/p90 triples.

The fast local demo scheduler attempted one snapshot before the reference
backfill import completed. Projection rejected the unauditable input and left
the current result untouched. The next locked execution succeeded after the
import. This exercised the intended fail-closed startup behavior.

## Browser verification

The current worktree Vite server at `127.0.0.1:5176` proxied the Spring server
at port 8080. At a 617 px viewport the Tracker tab remained visible and rendered:

- overall `2091` and `2089 – 2094` after display rounding;
- all six pillar readiness and ETA rows;
- the reviewed historical evidence timeline;
- the fixed scenario/80-percent interval label.

Browser console error count was zero. Production CSS and component tests cover
the wider layout; this run directly exercised the narrow responsive layout.

## Safety and boundary audit

- `TRACKER_PHASE4_PROJECTION_ENABLED` defaults to `false`.
- LL2, official-index, transport-economics, Metaculus, golden-live, and live
  model/polling flags remained false for the runtime check.
- The projection package contains no HTTP client, URL, or endpoint reference.
- No individual Monte Carlo draws are stored.
- No new secret, egress rule, pod, CronJob, or GitOps workload was introduced.
- `.claude/`, demo/refbackfill fixtures, and the local Vite configuration remain
  untracked and unstaged.
- WP4.4 through WP4.6 and the two-week G4 observation gate remain pending.
