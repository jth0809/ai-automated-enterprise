# Tracker Phase 4 WP4.1 Dependency DAG Implementation Plan

> Design: [Tracker Phase 4 credibility design](../specs/2026-07-16-tracker-phase4-credibility-design.md)
>
> Master roadmap: [Multiplanetary tracker execution plan](../../plans/multiplanetary-tracker-execution-plan.md)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:executing-plans` to implement this plan task-by-task. The user
> prohibited subagents, so `subagent-driven-development` must not be used.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate a versioned capability dependency DAG, validate it fail-closed,
and make weekly snapshots use auditable effective readiness without changing
observed TRL/EGL state.

**Architecture:** Flyway V16 preserves the V6 legacy edges and adds an immutable
active `graph-v1.0` copy whose singleton OR groups encode the intended mandatory
AND dependencies. A focused graph repository loads one active version, a pure
validator rejects malformed graphs, and a topological engine computes raw and
effective node/pillar readiness. `SnapshotJob` persists effective readiness in
the existing `readiness` field while adding raw readiness and graph version audit
columns.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, H2 Oracle mode,
Oracle-compatible SQL, JUnit 5, Maven 3.9.9, Git.

## Global Constraints

- Work only in `.claude/worktrees/tracker-mvp` on `codex/tracker-phase4`.
- Do not use subagents and do not delete existing files.
- Never stage `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- Do not edit V1–V15; add V16 only.
- Preserve all 35 `nodes-v1.0` nodes, weights, levels, status, and `r2.0` rubric.
- Preserve V6's 29 rows as inactive `graph-v0-legacy`; do not delete them.
- `graph-v1.0` contains exactly 29 active edges. Each mandatory input receives a
  distinct `or_group`, so groups combine with AND while alternatives within a
  future shared group combine with OR.
- `delta_e` remains 0.150 for all seeded edges and must stay in `[0, 0.5]`.
- Effective readiness may only cap observed readiness; it must never raise or
  mutate a node's TRL/EGL level.
- Graph validation failure aborts the snapshot transaction and preserves the
  previous completed snapshot.
- `TRACKER_ENABLED=false` remains the production parent gate. No network egress,
  secret, pod, or Kubernetes CronJob is added.
- Write the failing test first, observe RED, implement the smallest tested change,
  observe GREEN, then commit each task.

---

## File Structure

- Create `apps/backend/springboot-app/src/main/resources/db/migration/V16__tracker_phase4_dag.sql`:
  versioned graph registry, preserved legacy graph, corrected active graph, and
  snapshot audit columns.
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerDagV16SchemaTest.java`:
  migration cardinality, AND grouping, immutability metadata, and constraints.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`:
  scope dependency assertions to the active graph and reject the old all-OR
  interpretation.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityEdgeRow.java`:
  immutable edge DTO.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraph.java`:
  immutable graph/version aggregate and canonical hash.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraphRepository.java`:
  load exactly one active graph from JdbcClient.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraphValidator.java`:
  validate versions, nodes, edge ranges, duplicates, and acyclicity.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/NodeReadinessResult.java`:
  per-node raw/effective/cap explanation.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/ReadinessResult.java`:
  graph-versioned node and pillar result.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/EffectiveReadinessEngine.java`:
  topological AND/OR evaluation.
- Create `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityReadinessService.java`:
  Spring boundary joining repository, validator, nodes, parameters, and engine.
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/SnapshotRow.java`:
  add nullable `rawReadiness` and `graphVersion` while retaining the legacy
  constructor.
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`:
  persist and read the two audit columns.
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/SnapshotJob.java`:
  consume one graph calculation per snapshot transaction.
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/graph/CapabilityGraphValidatorTest.java`:
  malformed graph and cycle tests.
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/graph/EffectiveReadinessEngineTest.java`:
  exact numeric AND/OR, topological, and no-raise tests.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/math/SnapshotJobTest.java`:
  prove persisted raw/effective/version values.
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`: mark WP4.1 only
  after all verification evidence exists.

---

### Task 1: V16 graph registry and corrected active edge set

**Files:**

- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V16__tracker_phase4_dag.sql`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerDagV16SchemaTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`

**Interfaces:**

- Consumes: V6 `capability_edge` rows and `nodes-v1.0` node IDs.
- Produces: active `graph-v1.0`, inactive `graph-v0-legacy`, 58 preserved total
  rows, active canonical SHA-256
  `f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e`,
  and nullable `pillar_snapshot.raw_readiness`/`graph_version`.

- [x] **Step 1: Write the failing V16 schema test**

Create a Spring/H2 schema test containing these exact assertions:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrackerDagV16SchemaTest {
    @Autowired JdbcClient jdbc;

    @Test
    void preservesLegacyEdgesAndActivatesCorrectMandatoryGraph() {
        assertEquals(2, count("SELECT COUNT(*) FROM capability_graph_version"));
        assertEquals(29, count("SELECT COUNT(*) FROM capability_edge "
                + "WHERE graph_version_label = 'graph-v0-legacy'"));
        assertEquals(29, count("SELECT COUNT(*) FROM capability_edge "
                + "WHERE graph_version_label = 'graph-v1.0'"));
        assertEquals(1, count("SELECT COUNT(*) FROM capability_graph_version "
                + "WHERE active = 'Y' AND version_label = 'graph-v1.0' "
                + "AND edge_count = 29"));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM (
                  SELECT to_node_id, or_group, COUNT(*) grouped
                    FROM capability_edge
                   WHERE graph_version_label = 'graph-v1.0'
                   GROUP BY to_node_id, or_group
                ) WHERE grouped <> 1
                """));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
```

Add tests that inserting `delta_e=-0.001`, `delta_e=0.501`, `or_group=0`, a
self-edge, or a duplicate `(graph_version_label,to_node_id,from_node_id)` fails
with `DataIntegrityViolationException`.

- [x] **Step 2: Run the schema test and confirm RED**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=TrackerDagV16SchemaTest' test
```

Expected: test compilation or context startup fails because V16 tables/columns
do not exist.

- [x] **Step 3: Add the Oracle-compatible V16 migration**

The migration must use this schema and preserve the legacy rows:

```sql
CREATE TABLE capability_graph_version (
  version_label  VARCHAR2(40) PRIMARY KEY,
  node_set_version VARCHAR2(40) NOT NULL,
  active         CHAR(1) DEFAULT 'N' NOT NULL,
  edge_count     NUMBER(3) NOT NULL,
  edge_sha256    CHAR(64) NOT NULL,
  notes          VARCHAR2(1000) NOT NULL,
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_graph_version_active CHECK (active IN ('Y','N')),
  CONSTRAINT ck_graph_version_count CHECK (edge_count BETWEEN 0 AND 999),
  CONSTRAINT ck_graph_version_sha CHECK (LENGTH(edge_sha256) = 64)
);

INSERT INTO capability_graph_version VALUES (
  'graph-v0-legacy', 'nodes-v1.0', 'N', 29,
  '5d22687ffb5142b1f72e948383e6e431346b78e97546b2e52780e0fec6704fb2',
  'V6 rows preserved; shared group 1 encodes the superseded all-OR reading',
  CURRENT_TIMESTAMP
);

INSERT INTO capability_graph_version VALUES (
  'graph-v1.0', 'nodes-v1.0', 'Y', 29,
  'f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e',
  'Mandatory pillar-local integration dependencies; singleton OR groups combine by AND',
  CURRENT_TIMESTAMP
);

ALTER TABLE capability_edge ADD graph_version_label VARCHAR2(40)
  DEFAULT 'graph-v0-legacy' NOT NULL;
ALTER TABLE capability_edge ADD CONSTRAINT fk_edge_graph_version
  FOREIGN KEY (graph_version_label)
  REFERENCES capability_graph_version(version_label);
ALTER TABLE capability_edge DROP CONSTRAINT uq_edge;
ALTER TABLE capability_edge ADD CONSTRAINT uq_edge_version
  UNIQUE (graph_version_label, to_node_id, from_node_id);
ALTER TABLE capability_edge ADD CONSTRAINT ck_edge_delta_range
  CHECK (delta_e BETWEEN 0 AND 0.5);
ALTER TABLE capability_edge ADD CONSTRAINT ck_edge_or_group_range
  CHECK (or_group BETWEEN 1 AND 99);

INSERT INTO capability_edge (
  to_node_id, from_node_id, or_group, delta_e, graph_version_label
)
SELECT legacy.to_node_id,
       legacy.from_node_id,
       ROW_NUMBER() OVER (
         PARTITION BY legacy.to_node_id ORDER BY source.code
       ),
       legacy.delta_e,
       'graph-v1.0'
  FROM capability_edge legacy
  JOIN capability_node source ON source.id = legacy.from_node_id
 WHERE legacy.graph_version_label = 'graph-v0-legacy';

ALTER TABLE pillar_snapshot ADD raw_readiness NUMBER(6,5);
ALTER TABLE pillar_snapshot ADD graph_version VARCHAR2(40);
ALTER TABLE pillar_snapshot ADD CONSTRAINT fk_snapshot_graph_version
  FOREIGN KEY (graph_version)
  REFERENCES capability_graph_version(version_label);
```

The two SHA values are the exact Task 2 canonical hashes for the preserved
legacy and corrected active graphs. Repository tests recompute both values.

- [x] **Step 4: Update the V6 registry test to select the active graph**

Join `capability_graph_version` and filter `active='Y'`. Replace the legacy
assertion that every `or_group` equals 1 with:

```java
Map<String, Long> edgeCount = edges.stream().collect(Collectors.groupingBy(
        Edge::toCode, Collectors.counting()));
Map<String, Long> groupCount = edges.stream().collect(Collectors.groupingBy(
        Edge::toCode,
        Collectors.collectingAndThen(
                Collectors.mapping(Edge::orGroup, Collectors.toSet()),
                groups -> (long) groups.size())));
assertEquals(edgeCount, groupCount, "every mandatory input must be its own AND group");
```

- [x] **Step 5: Run focused schema tests and confirm GREEN**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=TrackerDagV16SchemaTest,TrackerNodesV1Test' test
```

Expected: all tests PASS and Flyway reports schema version 16.

- [x] **Step 6: Commit Task 1**

```powershell
git add -- `
  apps/backend/springboot-app/src/main/resources/db/migration/V16__tracker_phase4_dag.sql `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerDagV16SchemaTest.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java
git commit -m "feat(tracker): version capability dependency graph"
```

---

### Task 2: Fail-closed graph loading and validation

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityEdgeRow.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraph.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraphRepository.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityGraphValidator.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/graph/CapabilityGraphRepositoryTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/graph/CapabilityGraphValidatorTest.java`

**Interfaces:**

- Produces: `CapabilityGraphRepository.loadActive(): CapabilityGraph` and
  `CapabilityGraphValidator.validate(CapabilityGraph, Collection<NodeRow>): void`.
- Canonical SHA input is UTF-8, LF-only, with header lines `version` and
  `nodeSetVersion`, followed by edges sorted by `toCode`, `orGroup`, `fromCode`
  and formatted `to|%02d|from|%.3f` with `Locale.ROOT`.

- [x] **Step 1: Write failing validator tests**

Create fixtures with `NodeRow` codes `A`, `B`, `C` and assert acceptance of
`A -> B -> C`. Assert `IllegalStateException` for an unknown node, self-edge,
duplicate pair, mixed graph version, `delta_e` outside `[0,0.5]`, non-positive
group, hash mismatch, edge-count mismatch, and cycle `A -> B -> A`.

The exact public records are:

```java
public record CapabilityEdgeRow(
        String graphVersion, String fromCode, String toCode,
        int orGroup, double deltaE) {}

public record CapabilityGraph(
        String version, String nodeSetVersion, String declaredSha256,
        int declaredEdgeCount, List<CapabilityEdgeRow> edges) {
    public CapabilityGraph { edges = List.copyOf(edges); }
}
```

- [x] **Step 2: Run validator tests and confirm RED**

Run `-Dtest=CapabilityGraphValidatorTest test` and expect missing-type
compilation failures.

- [x] **Step 3: Implement canonicalization and graph validation**

`CapabilityGraph.canonicalText()` must be equivalent to:

```java
String edgeText = edges.stream()
        .sorted(Comparator.comparing(CapabilityEdgeRow::toCode)
                .thenComparingInt(CapabilityEdgeRow::orGroup)
                .thenComparing(CapabilityEdgeRow::fromCode))
        .map(edge -> String.format(Locale.ROOT, "%s|%02d|%s|%.3f",
                edge.toCode(), edge.orGroup(), edge.fromCode(), edge.deltaE()))
        .collect(Collectors.joining("\n"));
return version + "\n" + nodeSetVersion
        + (edgeText.isEmpty() ? "" : "\n" + edgeText);
```

`CapabilityGraphValidator` must run all structural checks before a Kahn
topological sort and compare lowercase SHA-256 hex of `canonicalText()` to the
declared hash using `MessageDigest.isEqual`.

- [x] **Step 4: Write failing repository integration tests**

Autowire `CapabilityGraphRepository` in an H2 Spring test and assert:

```java
CapabilityGraph graph = repository.loadActive();
assertEquals("graph-v1.0", graph.version());
assertEquals("nodes-v1.0", graph.nodeSetVersion());
assertEquals(29, graph.edges().size());
assertEquals(graph.declaredSha256(), graph.computedSha256());
```

Set both graph rows active inside a rolled-back test transaction and assert
`loadActive()` throws `IllegalStateException` instead of choosing one.

- [x] **Step 5: Implement the focused JdbcClient repository**

Annotate it with the parent feature gate:

```java
@Repository
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class CapabilityGraphRepository {
    private final JdbcClient jdbc;
    public CapabilityGraphRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public CapabilityGraph loadActive() {
        List<GraphHeader> headers = jdbc.sql("""
                SELECT version_label, node_set_version, edge_sha256, edge_count
                  FROM capability_graph_version WHERE active = 'Y'
                """).query((rs, rowNum) -> new GraphHeader(
                        rs.getString("version_label"),
                        rs.getString("node_set_version"),
                        rs.getString("edge_sha256"),
                        rs.getInt("edge_count")))
                .list();
        if (headers.size() != 1) {
            throw new IllegalStateException("exactly one active capability graph is required");
        }
        GraphHeader header = headers.getFirst();
        List<CapabilityEdgeRow> edges = jdbc.sql("""
                SELECT e.graph_version_label, source.code AS from_code,
                       target.code AS to_code, e.or_group, e.delta_e
                  FROM capability_edge e
                  JOIN capability_node source ON source.id = e.from_node_id
                  JOIN capability_node target ON target.id = e.to_node_id
                 WHERE e.graph_version_label = :version
                 ORDER BY target.code, e.or_group, source.code
                """)
                .param("version", header.version())
                .query((rs, rowNum) -> new CapabilityEdgeRow(
                        rs.getString("graph_version_label"),
                        rs.getString("from_code"),
                        rs.getString("to_code"),
                        rs.getInt("or_group"),
                        rs.getDouble("delta_e")))
                .list();
        return new CapabilityGraph(header.version(), header.nodeSetVersion(),
                header.edgeSha256(), header.edgeCount(), edges);
    }

    private record GraphHeader(String version, String nodeSetVersion,
            String edgeSha256, int edgeCount) {}
}
```

- [x] **Step 6: Run Task 2 tests and confirm GREEN**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=CapabilityGraphValidatorTest,CapabilityGraphRepositoryTest' test
```

Expected: all tests PASS.

- [x] **Step 7: Commit Task 2**

Stage only the six graph files and commit:

```powershell
git commit -m "feat(tracker): validate active capability graph"
```

---

### Task 3: Topological effective-readiness engine

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/NodeReadinessResult.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/ReadinessResult.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/EffectiveReadinessEngine.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/graph/EffectiveReadinessEngineTest.java`

**Interfaces:**

- Consumes: validated `CapabilityGraph`, complete `List<NodeRow>`, `Params`, and
  UTC `LocalDate asOf`.
- Produces:

```java
public record NodeReadinessResult(
        String nodeCode, double rawReadiness, double effectiveReadiness,
        Double dependencyCap, List<Integer> limitingGroups,
        List<String> limitingDependencies) {}

public record ReadinessResult(
        String graphVersion,
        Map<String, NodeReadinessResult> nodes,
        Map<Integer, Double> rawPillarReadiness,
        Map<Integer, Double> effectivePillarReadiness) {}
```

- [x] **Step 1: Write exact numeric RED tests**

Cover these cases with levels/readiness values chosen through a test `Params`
mapping or package-visible raw-value helper:

```text
No inputs: raw 0.30 -> effective 0.30
Mandatory AND: A 0.30 + 0.15, B 0.60 + 0.15, target raw 0.90
  -> caps 0.45 and 0.75 -> dependency cap 0.45 -> effective 0.45
OR alternatives in one group: A 0.30, B 0.60, delta 0.15
  -> group max 0.75
Two-hop chain: A 0.30 -> B raw 0.90 -> C raw 0.90
  -> B 0.45 -> C 0.60
Raw below cap: target raw 0.20, cap 0.45 -> effective 0.20
```

Also assert raw/effective pillar sums use node weights, every effective value is
`<= raw + 1e-12`, input collections are not mutated, and results are deterministic
under shuffled node/edge order.

- [x] **Step 2: Run the engine test and confirm RED**

Run `-Dtest=EffectiveReadinessEngineTest test`; expect missing classes.

- [x] **Step 3: Implement one-pass topological evaluation**

The engine entry point is exact:

```java
public ReadinessResult calculate(
        List<NodeRow> nodeRows,
        CapabilityGraph graph,
        Params params,
        LocalDate asOf)
```

For each node, calculate raw readiness with `Readiness.nodeReadiness(...)`.
Process nodes in deterministic topological/code order. Group incoming edges by
`orGroup`, compute each group maximum, take the minimum group cap, then apply:

```java
double effective = Math.min(raw, dependencyCap);
```

Use a tolerance of `1e-12` when selecting tied limiting groups/dependencies.
Return unmodifiable, insertion-ordered maps. Never update `capability_node`.

- [x] **Step 4: Run engine and math regression tests**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=EffectiveReadinessEngineTest,ReadinessTest,LogitEtaTest' test
```

Expected: all tests PASS.

- [x] **Step 5: Commit Task 3**

Stage only the four engine files and commit:

```powershell
git commit -m "feat(tracker): compute effective DAG readiness"
```

---

### Task 4: Snapshot integration and audit persistence

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph/CapabilityReadinessService.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/SnapshotRow.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/math/SnapshotJob.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/math/SnapshotJobTest.java`

**Interfaces:**

- `CapabilityReadinessService.calculate(List<NodeRow>, Params, LocalDate)` loads
  and validates the active graph for every snapshot transaction, then delegates
  to the pure engine.
- `pillar_snapshot.readiness` becomes effective readiness; `raw_readiness`
  preserves the pre-cap weighted value; `graph_version` records `graph-v1.0`.
- Legacy snapshot writers continue using the 14-argument `SnapshotRow`
  constructor, which sets `rawReadiness=readiness` and `graphVersion=null`.

- [x] **Step 1: Write failing snapshot audit tests**

After loading the sample backfill and running `snapshotNow()`, assert all seven
rows contain:

```java
assertEquals("graph-v1.0", pillarOne.graphVersion());
assertNotNull(pillarOne.rawReadiness());
assertTrue(pillarOne.readiness() <= pillarOne.rawReadiness() + 1e-12);
```

For a deterministic cap case, update `P1-TRANSPORT-INTEGRATION` to L9 and
`P1-DEEP-PROP` to L1 inside the rolled-back test transaction before running the
job. Assert Pillar 1 effective readiness is strictly below raw readiness. Record
all node levels before and after the job and assert exact equality, proving the
graph never mutates observations.

Corrupt the active graph hash inside a rolled-back transaction, call
`snapshotNow()`, assert `IllegalStateException`, and assert no row for today's
date was replaced.

- [x] **Step 2: Run SnapshotJobTest and confirm RED**

Run `-Dtest=SnapshotJobTest test`; expect missing accessors/columns or unchanged
readiness behavior.

- [x] **Step 3: Extend SnapshotRow without breaking legacy callers**

Use this canonical shape and compatibility constructor:

```java
public record SnapshotRow(
        long id, int pillar, LocalDate snapshotDate,
        double readiness, double logitClipped,
        Double trendFit, Double trendUsed, Integer eventsInWindow,
        Integer windowYears, Double etaYear, Double etaLow, Double etaHigh,
        Double displayedEtaYear, String paramsVersion,
        Double rawReadiness, String graphVersion) {

    public SnapshotRow(long id, int pillar, LocalDate snapshotDate,
            double readiness, double logitClipped,
            Double trendFit, Double trendUsed, Integer eventsInWindow,
            Integer windowYears, Double etaYear, Double etaLow, Double etaHigh,
            Double displayedEtaYear, String paramsVersion) {
        this(id, pillar, snapshotDate, readiness, logitClipped,
                trendFit, trendUsed, eventsInWindow, windowYears,
                etaYear, etaLow, etaHigh, displayedEtaYear, paramsVersion,
                readiness, null);
    }
}
```

- [x] **Step 4: Persist and map both audit fields**

Add `raw_readiness` and `graph_version` to `SNAPSHOT_SELECT`,
`replaceSnapshot` columns/parameters, and `mapSnapshot`. Bare historical inserts
may leave `graph_version` null but must write `raw_readiness=readiness` when they
are newly generated.

- [x] **Step 5: Switch SnapshotJob to effective readiness**

Inject `CapabilityReadinessService`. At the start of `snapshotNow()` after nodes
load, calculate exactly once:

```java
ReadinessResult readiness = readinessService.calculate(nodes, params, today);
```

Pass each pillar's effective value into trend fitting, persist its raw value and
graph version, and use the minimum effective pillar for the overall row. The
overall raw value is the minimum raw pillar value. Keep existing ETA aggregation,
damping, freeze behavior, and transaction boundary unchanged.

- [x] **Step 6: Run focused integration tests and confirm GREEN**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=SnapshotJobTest,TrackerRepositoryTest,WeeklyBackfillProjectorTest,TrackerControllerTest' test
```

Expected: all tests PASS; no node state changes occur.

- [x] **Step 7: Run complete backend regression**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
```

Expected: every backend test passes with zero failures and zero errors.

- [x] **Step 8: Commit Task 4**

Stage only the five listed source/test files and commit:

```powershell
git commit -m "feat(tracker): apply DAG readiness to snapshots"
```

---

### Task 5: WP4.1 evidence and master-plan checkpoint

**Files:**

- Create: `docs/research/tracker-wp41-validation-evidence.md`
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/superpowers/plans/2026-07-16-tracker-wp41-dag-plan.md`

**Interfaces:**

- Produces: reproducible RED/GREEN/full-regression evidence and a WP4.1-only
  completion marker. WP4.2 consumes the persisted raw/effective readiness and
  `graphVersion`; no WP4.2 code belongs in this task.

- [x] **Step 1: Run non-test repository checks**

```powershell
git diff --check
rg -n "https?://|WebClient|HttpClient" `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/graph
```

Expected: `git diff --check` exits 0 and the egress search returns no matches.

- [x] **Step 2: Record exact evidence**

The evidence document must include the V16 active/legacy counts, active hash,
focused test totals, complete Maven total, changed snapshot raw/effective values,
node-state before/after equality, feature-flag state, and protected untracked
file status. Do not claim G4; mark it `PENDING_SOFTWARE_AND_OBSERVATION`.

- [x] **Step 3: Update plan checkboxes and master summary**

Mark only completed steps `[x]`. Change WP4.1 in the master plan to completed
with links to this plan/evidence. Leave WP4.2–WP4.6 unchecked.

- [x] **Step 4: Commit documentation**

```powershell
git add -- `
  docs/research/tracker-wp41-validation-evidence.md `
  docs/plans/multiplanetary-tracker-execution-plan.md `
  docs/superpowers/plans/2026-07-16-tracker-wp41-dag-plan.md
git commit -m "docs(tracker): record WP4.1 DAG verification"
```

## Completion Gate

WP4.1 is complete only when all five tasks are checked, the active graph has
29 singleton mandatory groups, graph validation is fail-closed, the exact
`0.30 + 0.15 = 0.45` case passes, snapshots persist raw/effective/version fields,
no node level changes, and the complete backend suite passes. This does not
complete Phase 4 or G4; the next execution plan is WP4.2 and must consume these
real interfaces rather than invent parallel readiness state.
