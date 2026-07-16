# Tracker WP4.1 Dependency DAG Validation Evidence

Date: 2026-07-16 KST

Design: [Phase 4 credibility design](../superpowers/specs/2026-07-16-tracker-phase4-credibility-design.md)  
Execution plan: [WP4.1 dependency DAG plan](../superpowers/plans/2026-07-16-tracker-wp41-dag-plan.md)

## Result

WP4.1 is complete. The tracker now keeps observed TRL/EGL readiness separate
from dependency-capped effective readiness, validates one immutable active graph
fail-closed, and stamps each current snapshot with its graph version. The graph
can only preserve or lower observed readiness; it cannot raise or mutate an
observed node state.

G4 status: `PENDING_SOFTWARE_AND_OBSERVATION`. This checkpoint does not claim
Phase 4 completion or the required two-week unattended observation period.

## Versioned graph evidence

Flyway V16 migrated a fresh H2 Oracle-mode schema through all 16 migrations and
established:

- `graph-v0-legacy`: inactive, 29 preserved V6 edges, canonical SHA-256
  `5d22687ffb5142b1f72e948383e6e431346b78e97546b2e52780e0fec6704fb2`.
- `graph-v1.0`: the only active graph, 29 edges in 29 singleton mandatory
  groups, canonical SHA-256
  `f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e`.
- `pillar_snapshot.raw_readiness`: observed weighted readiness for audit.
- `pillar_snapshot.readiness`: dependency-capped effective readiness.
- `pillar_snapshot.graph_version`: immutable graph provenance.

The repository rejects zero or multiple active versions. The validator rejects
hash/count drift, mixed versions, unknown or duplicate nodes, duplicate/self
edges, invalid groups or deltas, node-set mismatch, and cycles before any
snapshot write.

## Exact numeric and production-corpus evidence

The pure engine test reproduces the design anchor exactly:

```text
predecessor effective readiness 0.30 + delta 0.15 = dependency cap 0.45
```

AND groups use the minimum group cap, alternatives within one OR group use the
maximum source cap, and multi-hop caps are evaluated once in deterministic
topological order.

After replaying all 147 reviewed production claims on 2026-07-16, the regression
test fixes these values:

| Pillar | Raw readiness | Effective readiness | DAG effect |
|---:|---:|---:|---:|
| 1 | 0.4624 | 0.4624 | 0.0000 |
| 2 | 0.1884 | 0.1842 | -0.0042 |
| 3 | 0.1524 | 0.1470 | -0.0054 |
| 4 | 0.1920 | 0.1920 | 0.0000 |
| 5 | 0.2991 | 0.2991 | 0.0000 |
| 6 | 0.2667 | 0.2667 | 0.0000 |

This demonstrates that the DAG does not erase graded partial credit: only P2
and P3 are capped, and all other pillar values are preserved. Every node also
satisfies `effectiveReadiness <= rawReadiness`.

The engine input-immutability test, `SnapshotJobTest`, and the production audit
confirm that the 35 observed node levels/statuses are unchanged before and after
calculation. A deliberately corrupted active graph hash aborts the snapshot
transaction and leaves the last completed snapshot intact.

## Test evidence

All commands completed successfully on 2026-07-16 KST:

| Scope | Result |
|---|---:|
| V16 schema and nodes-v1 registry | 16/16 passed |
| Graph repository and validator | 8/8 passed |
| Effective-readiness engine and math | 14/14 passed |
| Snapshot/repository/controller integration | 27/27 passed |
| Production-corpus DAG regression | 1/1 passed |
| Complete Spring Boot regression | 619/619 passed |
| Complete React/Vitest regression | 79/79 passed |

The complete Maven run reported `Failures: 0`, `Errors: 0`, `Skipped: 0` and
`BUILD SUCCESS`. `git diff --check` exited 0. A search for `https?://`,
`WebClient`, or `HttpClient` in the new `tracker/graph` production package
returned no matches.

## Runtime smoke evidence

The current branch was booted locally with the isolated
`test,demo,refbackfill` profiles and imported 147 reviewed claims. The current
API returned:

- summary readiness `0.1470`, bottleneck pillar `3`, frozen `false`;
- pillar effective readiness values matching the table above;
- no overall ETA because the pre-Monte-Carlo estimator still has unresolved
  pillar projections. WP4.2 and WP4.3 intentionally own that next work.

This local smoke profile does not alter production activation. Production
defaults remain dark: `TRACKER_ENABLED=false`, `TRACKER_LL2_ENABLED=false`,
`TRACKER_METACULUS_ENABLED=false`, `TRACKER_METACULUS_TERMS_APPROVED=false`,
and `TRACKER_GOLDEN_LIVE_ENABLED=false`.

## Repository and security boundary

- No external network client, secret, pod, Kubernetes CronJob, CNP, or GitOps
  resource was added by WP4.1.
- No existing file was deleted.
- The following protected local fixtures remained untracked and were never
  staged: `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `tracker/backfill-demo.json`, and `vite.wp33.local.config.ts`.

## Commits

- `e49440f` — version capability dependency graph
- `2f04508` — validate active capability graph
- `b665bcb` — compute effective DAG readiness
- `69a3084` — apply DAG readiness to snapshots
- `80fb0d8` — lock production DAG readiness

