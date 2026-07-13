# Tracker 35-Node Set (`nodes-v1.0`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 20-node Phase 1 seed with an approved, versioned 35-node capability set that includes one integration node per pillar and the 26-month no-resupply finish-line condition.

**Architecture:** Keep the existing six pillars and 20 element nodes, add nine bounded element nodes and six pillar integration nodes, and migrate all rows to `nodes-v1.0`. Store the complete definitions in a reviewable specification before applying Flyway V6, then version the classifier rubric as `r2.0` and seed dependency edges into the existing inactive DAG table.

**Tech Stack:** Java 25, Spring Boot 4.1, Flyway SQL (Oracle ATP + H2 Oracle mode), JUnit 5, JdbcClient, Markdown rubric resources.

## Global Constraints

- Work only in `.claude/worktrees/tracker-mvp` on `feat/tracker-mvp`.
- Preserve untracked `.claude/`, `application-demo.yml`, and `backfill-demo.json`; never stage them.
- The final node set contains exactly 35 rows with `node_set_version='nodes-v1.0'`.
- Every pillar remains numbered 1–6 and its node weights sum to `1.0000 ± 0.001`.
- Every pillar has exactly one `is_integration_node='Y'` row.
- `P2-SURVIVAL-INTEGRATION` encodes the 26-month no-material-resupply crew-survival observation required by the finish line; arrival still requires all six integration nodes and all core element nodes at level 8 or higher.
- Node boundaries must prevent partial-system evidence from receiving full-system TRL/EGL credit.
- No live LLM or internet dependency appears in automated tests.
- Use the cached Maven binary with `-o -Dmaven.repo.local=C:\Users\jang\.m2\repository`.
- One independently reviewable commit per task.

---

## File Structure

- Create `docs/plans/wp/tracker-nodes-v1.md`: canonical human-readable 35-node definitions, boundaries, scales, weights, and integration conditions.
- Create `apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql`: deterministic node/rubric/edge migration.
- Create `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`: exact set, weights, integration nodes, rubric, and edge assertions.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/prompt-classify-system.txt`: 35-node registry and integration-node classification restrictions.
- Modify `apps/backend/springboot-app/src/main/resources/db/migration/V2__tracker_seed.sql`: do not rewrite historical migration contents; only update comments if required. V6 owns all state changes.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java`: replace the obsolete 20-row assertion with the versioned 35-row contract.
- Modify `docs/plans/wp/tracker-rubric-v1.md`: mark v0.1 as historical and link to the v1.0 registry.
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`: record WP2.1 file work without claiming G2 complete.

---

### Task 1: Approve the exact 35-node registry

**Files:**
- Create: `docs/plans/wp/tracker-nodes-v1.md`

**Interfaces:**
- Produces: exact node codes, pillar assignment, names, scale type, weights, boundaries, integration predicates, and dependency inputs consumed by V6 and the classifier prompt.

- [ ] **Step 1: Write the registry header and invariants**

Start the document with this exact contract:

```markdown
# Tracker Capability Nodes v1.0

Version: nodes-v1.0
Count: 35
Pillars: 6
Integration nodes: exactly one per pillar
Rubric version: r2.0

Each node definition contains: code, names, pillar, scale, weight,
inclusion boundary, exclusions, level anchors, integration predicate,
and dependency inputs. Partial demonstrations cannot satisfy an
integration node.
```

- [ ] **Step 2: Define the retained 20 nodes**

Copy the 20 existing codes from `tracker-rubric-v1.md` and sharpen exclusions where the new nodes split responsibilities. Preserve names and scale types. Apply these exact v1.0 weights:

```text
P1-REUSE-LV             0.18
P1-ORBIT-REFUEL         0.16
P1-DEEP-PROP            0.12
P1-EDL-HEAVY            0.14
P1-SURFACE-ASCENT       0.10

P2-ECLSS                0.24
P2-FOOD                 0.16
P2-RAD                  0.12
P2-MED                  0.12

P3-CONSTRUCT            0.23
P3-POWER                0.22
P3-COMMS                0.15

P4-ISRU-PROP            0.30
P4-NUKE                 0.22
P4-MATERIALS            0.14

P5-AUTOCON              0.28
P5-AUTONOMY             0.27

P6-LAUNCH-MARKET        0.27
P6-GOV-FRAMEWORK        0.24
P6-FUNDING              0.17
```

- [ ] **Step 3: Define the nine new element nodes**

Use these exact codes and responsibilities:

```text
P1-CREW-SAFE        TRL  유인 수송 안전·비상 탈출      weight 0.12
P1-ORBIT-LOGISTICS  TRL  궤도 물류·저장·화물 취급      weight 0.08
P2-WASTE-CYCLE      TRL  폐기물·영양염 폐쇄 순환       weight 0.12
P2-HEALTH-AUTONOMY  TRL  지구 지원 없는 임상·의료 운영 weight 0.10
P3-THERMAL          TRL  표면 열제어·환경 차폐          weight 0.12
P3-DUST             TRL  먼지·레골리스 오염 제어       weight 0.10
P4-MANUFACTURING    TRL  현지 제조·예비품 생산          weight 0.16
P5-MAINTENANCE      TRL  자율 정비·고장 복구            weight 0.20
P6-INSURANCE-STD    EGL  보험·표준·금융 인프라          weight 0.14
```

Required exclusions:

- `P1-CREW-SAFE` excludes generic vehicle reliability without crew escape, rescue, or loss-of-crew evidence.
- `P1-ORBIT-LOGISTICS` excludes propellant transfer itself (`P1-ORBIT-REFUEL`) and launch-market economics (`P6-LAUNCH-MARKET`).
- `P2-HEALTH-AUTONOMY` covers independent diagnosis/treatment capability; biomedical knowledge remains `P2-MED`.
- `P4-MANUFACTURING` covers production of usable parts from local/recycled feedstock; materials research remains `P4-MATERIALS`, habitat assembly remains `P3-CONSTRUCT`.
- `P5-MAINTENANCE` requires physical inspection, repair, or replacement; planning/navigation software remains `P5-AUTONOMY`.

- [ ] **Step 4: Define the six integration nodes**

```text
P1-TRANSPORT-INTEGRATION TRL 지구-궤도-행성표면 통합 수송       weight 0.10
P2-SURVIVAL-INTEGRATION  TRL 26개월 무보급 생존·건강 유지      weight 0.14
P3-HABITAT-INTEGRATION   TRL 26개월 표면 거주 인프라 통합 운용 weight 0.18
P4-RESOURCE-INTEGRATION  TRL ISRU-전력-저장-제조 통합 운용      weight 0.18
P5-OPS-INTEGRATION       TRL 장기 자율기지 통합 운영            weight 0.25
P6-SETTLEMENT-INTEGRATION EGL 정착 경제·법제·공급망 지속 운용  weight 0.18
```

For every integration node, level 8 requires an integrated operational demonstration, not the maximum of component levels. `P2-SURVIVAL-INTEGRATION` level 8 additionally requires at least 26 continuous months without material resupply while maintaining normal operations and crew health.

- [ ] **Step 5: Verify arithmetic and count before review**

Run:

```powershell
$doc = Get-Content -Raw docs/plans/wp/tracker-nodes-v1.md
($doc | Select-String -AllMatches 'P[1-6]-[A-Z0-9-]+' ).Matches.Value |
  Sort-Object -Unique | Measure-Object
```

Expected: `Count : 35`. Manually sum the listed weights; each pillar must equal 1.00.

- [ ] **Step 6: Human review gate**

Present `tracker-nodes-v1.md` for review. Do not create V6 until the user explicitly approves the registry, boundaries, and weights. Record corrections directly in the document; do not maintain an alternative hidden list.

- [ ] **Step 7: Commit the approved registry**

```powershell
git add docs/plans/wp/tracker-nodes-v1.md
git commit -m "docs(tracker): define 35-node capability set"
```

---

### Task 2: Add failing `nodes-v1.0` schema tests

**Files:**
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java`

**Interfaces:**
- Consumes: approved registry from Task 1.
- Produces: executable V6 acceptance contract.

- [ ] **Step 1: Write exact count/version/integration tests**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrackerNodesV1Test {
    @Autowired JdbcClient jdbc;

    @Test
    void nodesV1HasExactlyThirtyFiveRowsAndSixIntegrationNodes() {
        assertEquals(35, jdbc.sql("""
            SELECT COUNT(*) FROM capability_node
             WHERE node_set_version = 'nodes-v1.0'
            """).query(Integer.class).single());
        assertEquals(6, jdbc.sql("""
            SELECT COUNT(*) FROM capability_node
             WHERE node_set_version = 'nodes-v1.0'
               AND is_integration_node = 'Y'
            """).query(Integer.class).single());
    }
}
```

- [ ] **Step 2: Add exact code-set assertion**

Define the complete expected code set and compare it to the database:

```java
private static final Set<String> EXPECTED_CODES = Set.of(
        "P1-REUSE-LV", "P1-ORBIT-REFUEL", "P1-DEEP-PROP",
        "P1-EDL-HEAVY", "P1-SURFACE-ASCENT", "P1-CREW-SAFE",
        "P1-ORBIT-LOGISTICS", "P1-TRANSPORT-INTEGRATION",
        "P2-ECLSS", "P2-FOOD", "P2-RAD", "P2-MED",
        "P2-WASTE-CYCLE", "P2-HEALTH-AUTONOMY", "P2-SURVIVAL-INTEGRATION",
        "P3-CONSTRUCT", "P3-POWER", "P3-COMMS", "P3-THERMAL",
        "P3-DUST", "P3-HABITAT-INTEGRATION",
        "P4-ISRU-PROP", "P4-NUKE", "P4-MATERIALS",
        "P4-MANUFACTURING", "P4-RESOURCE-INTEGRATION",
        "P5-AUTOCON", "P5-AUTONOMY", "P5-MAINTENANCE",
        "P5-OPS-INTEGRATION",
        "P6-LAUNCH-MARKET", "P6-GOV-FRAMEWORK", "P6-FUNDING",
        "P6-INSURANCE-STD", "P6-SETTLEMENT-INTEGRATION");
```

```java
Set<String> actual = Set.copyOf(jdbc.sql("""
    SELECT code FROM capability_node
     WHERE node_set_version='nodes-v1.0'
    """).query(String.class).list());
assertEquals(EXPECTED_CODES, actual);
```

- [ ] **Step 3: Add weight and one-integration-per-pillar assertions**

```java
for (int pillar = 1; pillar <= 6; pillar++) {
    double sum = jdbc.sql("SELECT SUM(weight) FROM capability_node WHERE pillar=:p")
            .param("p", pillar).query(Double.class).single();
    assertEquals(1.0, sum, 0.001);
    int integrations = jdbc.sql("""
        SELECT COUNT(*) FROM capability_node
         WHERE pillar=:p AND is_integration_node='Y'
        """).param("p", pillar).query(Integer.class).single();
    assertEquals(1, integrations);
}
```

- [ ] **Step 4: Add rubric and edge expectations**

```java
assertEquals(1, jdbc.sql("""
    SELECT COUNT(*) FROM rubric_version
     WHERE version_label='r2.0' AND node_set_version='nodes-v1.0' AND active='Y'
    """).query(Integer.class).single());
assertEquals(6, jdbc.sql("""
    SELECT COUNT(DISTINCT n.id)
      FROM capability_node n
     WHERE n.is_integration_node='Y'
       AND EXISTS (SELECT 1 FROM capability_edge e WHERE e.to_node_id=n.id)
    """).query(Integer.class).single());
```

- [ ] **Step 5: Run tests and verify RED**

Run:

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test,TrackerSchemaTest'
```

Expected: failure because only 20 `nodes-v0.1` rows and `r2.0` does not exist.

- [ ] **Step 6: Commit tests**

```powershell
git add apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java
git commit -m "test(tracker): specify nodes-v1 capability registry"
```

---

### Task 3: Migrate nodes, weights, rubric, and DAG edges

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql`

**Interfaces:**
- Consumes: exact registry and weights from Task 1.
- Produces: 35 `nodes-v1.0` rows and dependency edges for all integration nodes. Rubric activation remains in Task 4 so no commit contains a mismatched prompt hash.

- [ ] **Step 1: Update the retained 20 rows**

For each retained code, V6 updates `weight`, `description`, and
`node_set_version='nodes-v1.0'`. Use one explicit `UPDATE` per row so the migration is auditable; do not generate dynamic SQL.

Example:

```sql
UPDATE capability_node
   SET weight = 0.1800,
       node_set_version = 'nodes-v1.0',
       description = '완전 재사용의 범위와 부분계통 제외 조건을 tracker-nodes-v1.md와 동일하게 기록'
 WHERE code = 'P1-REUSE-LV';
```

- [ ] **Step 2: Insert the 15 approved new rows**

Use explicit `INSERT INTO capability_node` statements with `current_level=0`, `node_status='ACTIVE'`, and exactly the weights from Task 1. Integration rows use `is_integration_node='Y'`; other new rows use `N`.

- [ ] **Step 3: Seed integration dependency edges**

Each integration node receives edges from every element node in its pillar. Use `or_group=1` for mandatory AND dependencies and `delta_e=0.150`.

```sql
INSERT INTO capability_edge (to_node_id, from_node_id, or_group, delta_e)
SELECT target.id, source.id, 1, 0.150
  FROM capability_node target
  JOIN capability_node source ON source.pillar = target.pillar
 WHERE target.code = 'P1-TRANSPORT-INTEGRATION'
   AND source.is_integration_node = 'N';
```

Repeat for all six integration nodes. Do not add cross-pillar edges in this task.

- [ ] **Step 4: Leave rubric `r1.0` active**

Do not insert or activate `r2.0` in this task. This ensures the committed database state never claims that the old 20-node classifier prompt represents the new registry.

- [ ] **Step 5: Run node-only focused tests**

Run the exact count, code-set, weight, integration, and edge test methods from `TrackerNodesV1Test`; exclude the rubric test until Task 4. Expected: node tests pass and the separately run rubric test remains RED because `r2.0` is absent.

- [ ] **Step 6: Commit migration**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql
git commit -m "feat(tracker): migrate to 35-node capability set"
```

---

### Task 4: Version the classifier prompt for 35 nodes

**Files:**
- Modify: `apps/backend/springboot-app/src/main/resources/tracker/prompt-classify-system.txt`
- Modify: `apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql`
- Modify: `docs/plans/wp/tracker-rubric-v1.md`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`

**Interfaces:**
- Consumes: exact 35-node definitions.
- Produces: `r2.0` fixed prompt and SHA-256 parity with the database seed.

- [ ] **Step 1: Replace the prompt registry block**

List all 35 codes, names, inclusion boundaries, and exclusions. Add these rules verbatim:

```text
Integration nodes require evidence of an integrated system operating as a unit.
Do not infer an integration-node level from component-node levels.
P2-SURVIVAL-INTEGRATION level 8 or 9 requires an observed continuous period
of at least 26 months without material resupply while normal operations and
crew health are maintained.
Partial first-stage reuse is not full-vehicle reuse.
Plans, design reviews, funding awards, and target dates are ANNOUNCEMENT_ONLY.
```

- [ ] **Step 2: Add a prompt registry test**

Read the prompt and assert every expected code appears exactly once in the node registry section. Assert all six integration codes and the string `26 months` are present.

- [ ] **Step 3: Compute the LF-normalized SHA-256**

Run:

```powershell
$path = 'apps/backend/springboot-app/src/main/resources/tracker/prompt-classify-system.txt'
$text = (Get-Content -Raw $path) -replace "`r`n", "`n"
$bytes = [Text.Encoding]::UTF8.GetBytes($text)
$hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
$hash
```

Append the `r2.0` seed to V6 using the computed `$hash`; never commit an interim copied or zero hash:

```powershell
$migration = 'apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql'
$sql = @"

UPDATE rubric_version SET active='N' WHERE active='Y';

INSERT INTO rubric_version (
  version_label, gate_model, classify_model,
  gate_prompt_sha256, classify_prompt_sha256,
  node_set_version, active, notes
) SELECT
  'r2.0', gate_model, classify_model,
  gate_prompt_sha256, '$hash',
  'nodes-v1.0', 'Y', '35-node registry and integration-node rubric'
FROM rubric_version WHERE version_label='r1.0';
"@
Add-Content -Path $migration -Value $sql
```

Verify that V6 contains `$hash` exactly once and contains no 64-zero prompt hash.

- [ ] **Step 4: Add DB/resource hash parity test**

```java
String seeded = jdbc.sql("""
    SELECT classify_prompt_sha256 FROM rubric_version
     WHERE version_label='r2.0'
    """).query(String.class).single();
assertEquals(sha256LfNormalized("tracker/prompt-classify-system.txt"), seeded);

private static String sha256LfNormalized(String resource) throws Exception {
    String text = new ClassPathResource(resource)
            .getContentAsString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
    return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
}
```

- [ ] **Step 5: Run focused and full backend tests**

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test,TrackerSchemaTest,DeepClassifierTest'
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
```

Expected: focused tests and complete backend suite pass with zero failures/errors/skips.

- [ ] **Step 6: Update rubric documentation**

Mark `tracker-rubric-v1.md` as the archived `nodes-v0.1` seed and link `tracker-nodes-v1.md` as the active registry. Record `r2.0` and its prompt hash.

- [ ] **Step 7: Commit prompt and rubric version**

```powershell
git add apps/backend/springboot-app/src/main/resources/tracker/prompt-classify-system.txt apps/backend/springboot-app/src/main/resources/db/migration/V6__tracker_nodes_v1.sql apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java docs/plans/wp/tracker-rubric-v1.md
git commit -m "feat(tracker): version rubric for nodes-v1"
```

---

### Task 5: Complete nodes-v1 documentation and regression handoff

**Files:**
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `.superpowers/sdd/progress.md` (ignored; do not stage)

**Interfaces:**
- Produces: reviewed WP2.1 file-work evidence; does not claim G2 completion.

- [ ] **Step 1: Run final verification**

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
git diff --check
git status --short
```

Expected: backend passes; only intentional untracked demo files remain.

- [ ] **Step 2: Inspect migration and version invariants**

Verify V1–V6 order, exactly one active rubric, 35 v1 nodes, six integration nodes, and no accidental edits to V1/V2 historical SQL.

- [ ] **Step 3: Update the execution plan**

Record that `nodes-v1.0` file work is implemented and tested. Keep WP2.1 human calibration and G2 operational gate open until the user approves weights and production data.

- [ ] **Step 4: Commit handoff documentation**

```powershell
git add docs/plans/multiplanetary-tracker-execution-plan.md
git commit -m "docs(tracker): record nodes-v1 implementation"
```
