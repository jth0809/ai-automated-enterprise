# Tracker Reference-Only Backfill Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map the approved historical corpus onto `nodes-v1.0`, persist approved references without copied source text, derive verification in code, replay historical state, and expose correctly labelled evidence through the API and React UI.

**Architecture:** Add historical-only source rows and dedicated `historical_evidence`/`backfill_import` tables, validate a mapped `backfill-v1.json`, and replace the empty-database backfill shortcut with dataset-hash idempotency. Live verbatim evidence and historical reference summaries remain separate in storage and DTOs but share source-count and timeline views.

**Tech Stack:** Java 25, Spring Boot 4.1, JdbcClient, Flyway V7/V8, Oracle ATP + H2 Oracle mode, Jackson, JUnit 5, React 19, TypeScript 7, Vitest 4.

## Global Constraints

- Begin only after `nodes-v1.0` and the node-neutral historical corpus pass their human review gates.
- Preserve untracked `.claude/`, `application-demo.yml`, and `backfill-demo.json`.
- Store no third-party source body, quote, excerpt, source title, HTML, PDF, image, or attachment.
- Historical evidence stores only source references, content fingerprints, and reviewer-authored factual summaries.
- A backfill claim requires fact review and rubric review both `APPROVED`.
- Verification is derived from source metadata and publication paths; expected verification is a checked assertion.
- Import works on a database containing live events and is idempotent by dataset hash and record identifiers.
- Any invalid record rolls back the complete dataset-version transaction.
- No import-time network call, LLM call, or fluke-filter call.
- Historical summaries are labelled `인간 검수 사실 요약`, never rendered as quotations.
- Deploy only through GitOps; no manual production mutation.
- Use TDD and one reviewable commit per task.

---

## File Structure

- Create `V7__tracker_historical_sources.sql`: non-feed source-registry additions from the approved catalog.
- Create `V8__tracker_reference_backfill.sql`: `historical_evidence` and `backfill_import`.
- Create `tracker/backfill-v1.json`: approved 110–150 mapped node claims.
- Create `backfill/BackfillClaim.java`, `BackfillReview.java`, `ValidatedBackfill.java`, `BackfillDatasetValidator.java`.
- Create `domain/HistoricalEvidenceRow.java`, `domain/BackfillImportRow.java`, `domain/EvidenceKind.java`.
- Modify `ingest/BackfillLoader.java`: dataset-based reference importer and chronological state replay.
- Modify `domain/TrackerRepository.java`: source lookup, historical-evidence persistence, import audit, combined evidence queries.
- Modify `event/VerificationDeriver.java` only if a shared evidence projection is required; retain existing ranking rules.
- Modify `domain/ReviewEvidence.java`, `TimelineRow.java`, API controllers, React tracker API, timeline, and review cards.
- Add focused schema, validator, importer, repository, API, and UI tests.

---

### Task 1: Map candidates to approved 35-node claims

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json`
- Create: `docs/research/tracker-backfill-mapping-review.md`

**Interfaces:**
- Consumes: approved `historical-candidates-v1.jsonl`, `historical-source-catalog-v1.json`, `tracker-nodes-v1.md`, and rubric `r2.0`.
- Produces: 110–150 dual-approved flat node claims.

- [ ] **Step 1: Create the mapped file as an empty JSON array**

Use this exact record shape:

```json
{
  "backfillId": "BF-P1-REUSE-LV-2015-01",
  "candidateId": "HC-2015-F9-LANDING",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-REUSE-LV",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 5,
  "actor": "SpaceX",
  "occurredOn": "2015-12-21",
  "expectedVerificationLevel": "INDEPENDENT",
  "eventTitle": "First-stage post-flight landing demonstration",
  "rubricJustification": "Partial-system flight evidence; not full-vehicle reuse.",
  "evidenceRefs": [
    "HC-2015-F9-LANDING#SPACEX",
    "HC-2015-F9-LANDING#SPACENEWS"
  ],
  "review": {
    "fact": "APPROVED",
    "rubric": "APPROVED",
    "reviewerNote": "Date, actor, type, and level boundary checked."
  }
}
```

- [ ] **Step 2: Map candidates pillar by pillar**

Assign node, event type, and level only from `tracker-nodes-v1.md`. One candidate may produce multiple node claims with distinct `backfillId` and justification. Do not let one evidence reference support a claim outside the fact stated in its `factSummary`.

- [ ] **Step 3: Include non-progress events**

Map substantiated setbacks, cancellations, retrospectives, and Pillar 6 rollbacks. `ANNOUNCEMENT_ONLY`, `RETROSPECTIVE`, `SETBACK`, and `PROGRAM_CANCELLATION` do not advance state. `ROLLBACK` requires OFFICIAL-or-higher evidence and a lower claimed EGL.

- [ ] **Step 4: Perform fact and rubric review separately**

Fact review reopens references and checks metadata. Rubric review checks boundaries without changing source facts. A rejected row remains in the mapping-review document but is removed from the production JSON.

- [ ] **Step 5: Verify acceptance range**

Expected: 110–150 approved claims. Unachieved integration nodes may have no state-advancing claim. Record per-node claim counts and explain every level-0 current state.

- [ ] **Step 6: Commit mapped data separately**

```powershell
git add apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json docs/research/tracker-backfill-mapping-review.md
git commit -m "data(tracker): map reviewed history to nodes-v1"
```

---

### Task 2: Add failing mapped-dataset validation tests

**Files:**
- Create: `backfill/BackfillClaim.java`
- Create: `backfill/BackfillReview.java`
- Create: `backfill/ValidatedBackfill.java`
- Create: `backfill/BackfillDatasetValidator.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java`

**Interfaces:**
- Produces: `ValidatedBackfill BackfillDatasetValidator.validate(Resource candidates, Resource mappings)`.

- [ ] **Step 1: Write failing cross-file tests**

```java
@Test
void approvedMappingResolvesCandidateEvidenceAndVersions() {
    ValidatedBackfill result = validator.validate(candidates(), mappings());
    assertFalse(result.claims().isEmpty());
    assertTrue(result.errors().isEmpty());
}
```

Add tests for unknown candidate/evidence ref, unknown node, duplicate backfill ID, invalid enum/level/date, non-approved reviews, version mismatch, missing evidence, and prohibited source-text keys.

- [ ] **Step 2: Add expected-verification tests**

Fixture source metadata must prove:

- one agency primary → OFFICIAL;
- one journal → PEER_REVIEWED;
- two distinct non-wire Tier 1–2 sources → INDEPENDENT;
- duplicate source or wire reprint does not produce INDEPENDENT.

- [ ] **Step 3: Run and verify RED**

Expected: compile failure because mapped backfill classes do not exist.

- [ ] **Step 4: Implement immutable records and validator**

```java
public record BackfillReview(
        String fact,
        String rubric,
        String reviewerNote) {}

public record BackfillClaim(
        String backfillId,
        String candidateId,
        String nodeSetVersion,
        String rubricVersion,
        String nodeCode,
        String eventType,
        Integer claimedLevel,
        String actor,
        LocalDate occurredOn,
        String expectedVerificationLevel,
        String eventTitle,
        String rubricJustification,
        List<String> evidenceRefs,
        BackfillReview review) {}

public record ValidatedBackfill(
        List<BackfillClaim> claims,
        Map<String, HistoricalCandidate> candidates,
        List<String> errors) {}
```

Resolve every evidence ref to a candidate and catalog source. Construct existing `SourceEvidence` values and call `VerificationDeriver.derive`; reject mismatch with `expectedVerificationLevel`.

- [ ] **Step 5: Run focused tests and commit**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill
git commit -m "feat(tracker): validate mapped historical backfill"
```

---

### Task 3: Seed historical-only sources with V7

**Files:**
- Create: `db/migration/V7__tracker_historical_sources.sql`
- Modify: `TrackerPhase1bSchemaTest.java` or create `TrackerHistoricalSourceTest.java`

**Interfaces:**
- Consumes: approved source catalog.
- Produces: every catalog `sourceCode` as a `source_registry` row; no feed or egress activation.

- [ ] **Step 1: Write failing source parity test**

Load source codes from the catalog in the test and compare against database rows. Assert new historical-only sources have `feed_active='N'` and no active `source_domain` row.

- [ ] **Step 2: Run and verify RED**

Expected: catalog authorities such as UNOOSA, FAA, DARPA, CONGRESS, and DOE_ARCHIVE are absent.

- [ ] **Step 3: Write explicit V7 inserts**

Use one audited explicit `INSERT` statement per source, selecting literal values only when a `WHERE NOT EXISTS` query confirms the source code is absent. Set domain, type, and tier exactly from the catalog. `rss_url` is null and `feed_active='N'`.

- [ ] **Step 4: Run tests and commit**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V7__tracker_historical_sources.sql apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java
git commit -m "feat(tracker): register historical reference sources"
```

---

### Task 4: Add V8 historical evidence and import-audit schema

**Files:**
- Create: `db/migration/V8__tracker_reference_backfill.sql`
- Create: `domain/HistoricalEvidenceRow.java`
- Create: `domain/BackfillImportRow.java`
- Create: `TrackerReferenceBackfillSchemaTest.java`

**Interfaces:**
- Produces: reference-only persistence and dataset import audit.

- [ ] **Step 1: Write failing schema tests**

Assert both tables, foreign keys, unique keys, review checks, bounds, and absence of prohibited body/quote columns.

- [ ] **Step 2: Run and verify RED**

Expected: missing tables.

- [ ] **Step 3: Create V8**

```sql
CREATE TABLE historical_evidence (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  backfill_id VARCHAR2(80) NOT NULL,
  candidate_id VARCHAR2(80) NOT NULL,
  event_id NUMBER NOT NULL REFERENCES event(id),
  source_id NUMBER NOT NULL REFERENCES source_registry(id),
  url VARCHAR2(1000) NOT NULL,
  locator VARCHAR2(300) NOT NULL,
  accessed_on DATE NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  publication_path VARCHAR2(20) NOT NULL CHECK
    (publication_path IN ('PRIMARY','THIRD_PARTY','WIRE_REPRINT')),
  fact_summary VARCHAR2(500) NOT NULL,
  fact_review_status VARCHAR2(10) NOT NULL CHECK
    (fact_review_status IN ('APPROVED','REJECTED')),
  rubric_review_status VARCHAR2(10) NOT NULL CHECK
    (rubric_review_status IN ('APPROVED','REJECTED')),
  reference_status VARCHAR2(10) DEFAULT 'APPROVED' NOT NULL CHECK
    (reference_status IN ('APPROVED','STALE','REJECTED')),
  reviewer_note VARCHAR2(2000),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_historical_evidence UNIQUE (backfill_id, source_id, url)
);

CREATE TABLE backfill_import (
  dataset_version VARCHAR2(40) PRIMARY KEY,
  dataset_sha256 CHAR(64) NOT NULL UNIQUE,
  node_set_version VARCHAR2(40) NOT NULL,
  rubric_version_id NUMBER NOT NULL REFERENCES rubric_version(id),
  imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  record_count NUMBER(5) NOT NULL
);
```

Add application validation for HTTPS and lowercase SHA; CHECK constraints cannot portably express all URI/hash semantics across Oracle/H2.

- [ ] **Step 4: Run tests and commit**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V8__tracker_reference_backfill.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/HistoricalEvidenceRow.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/BackfillImportRow.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerReferenceBackfillSchemaTest.java
git commit -m "feat(tracker): add reference-only backfill schema"
```

---

### Task 5: Replace empty-database replay with idempotent dataset import

**Files:**
- Modify: `ingest/BackfillLoader.java`
- Modify: `domain/TrackerRepository.java`
- Modify: `BackfillLoaderTest.java`

**Interfaces:**
- Produces: `loadDatasetIfNeeded()` importing approved claims into populated or empty databases.

- [ ] **Step 1: Write failing importer tests**

Cover empty import, populated database import, second-import no-op, changed content under same version rejection, unknown source rollback, verification mismatch rollback, and mixed valid/invalid dataset full rollback.

- [ ] **Step 2: Add repository interfaces**

```java
Optional<BackfillImportRow> findBackfillImport(String datasetVersion);
void insertHistoricalEvidence(HistoricalEvidenceRow draft);
void recordBackfillImport(BackfillImportRow row);
long sourceIdByCode(String sourceCode);
```

- [ ] **Step 3: Implement dataset hashing**

Compute SHA-256 over LF-normalized candidate JSONL plus canonical mapped JSON bytes and version labels. Do not include filesystem paths or timestamps.

- [ ] **Step 4: Implement chronological import**

For each mapped claim by `occurredOn`, upsert the event natural key, insert approved historical evidence, derive verification, compare expected level, mark confirmed, and apply state transition. Record the dataset audit only after all claims and snapshots succeed.

- [ ] **Step 5: Remove global `countEvents()` no-op**

`countEvents()` remains usable elsewhere but no longer controls backfill. Dataset version/hash controls idempotency.

- [ ] **Step 6: Run focused tests and commit**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BackfillLoader.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java
git commit -m "feat(tracker): import reviewed historical references"
```

---

### Task 6: Replay rollback, cancellation, and dormancy history

**Files:**
- Modify: `BackfillLoader.java`
- Modify: `BackfillLoaderTest.java`
- Modify: `docs/plans/wp/tracker-pipeline-architecture.md`

- [ ] **Step 1: Write failing chronological-state tests**

Fixture sequence: advance level 6, official rollback to 4, restoration to 5, program cancellation, then later restoration. Assert node history and year-end snapshots reflect each date rather than `Math.max`.

- [ ] **Step 2: Implement event-type transitions**

- normal eligible claims may advance;
- `ROLLBACK` with OFFICIAL-or-higher evidence may lower Pillar 6;
- `SETBACK`, `PROGRAM_CANCELLATION`, `ANNOUNCEMENT_ONLY`, and `RETROSPECTIVE` never change level;
- cancellation records `program_end_date` for later dormancy processing;
- historical integration claims use the same level rules but never infer level from dependencies.

- [ ] **Step 3: Rebuild snapshots from chronological state**

Maintain per-node state at each year end, including lower levels and dormant factors when historical dates establish them.

- [ ] **Step 4: Run and commit**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BackfillLoader.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java docs/plans/wp/tracker-pipeline-architecture.md
git commit -m "feat(tracker): replay historical state transitions"
```

---

### Task 7: Expose typed live and historical evidence in backend APIs

**Files:**
- Create: `domain/EvidenceKind.java`
- Modify: `domain/ReviewEvidence.java`, `TimelineRow.java`, `TrackerRepository.java`
- Modify: `TrackerController.java`, `TrackerAdminController.java`
- Modify tests: controller and repository tests.

- [ ] **Step 1: Write failing combined-evidence tests**

Assert live evidence returns `VERBATIM` with `evidenceQuote`; historical returns `HISTORICAL_REFERENCE` with `factSummary`, URL, locator, and accessedOn; no historical quote field is populated.

- [ ] **Step 2: Extend DTOs**

```java
public enum EvidenceKind { VERBATIM, HISTORICAL_REFERENCE }

public record ReviewEvidence(
        EvidenceKind kind,
        String sourceLabel,
        String url,
        String evidenceQuote,
        String factSummary,
        String locator,
        LocalDate accessedOn) {}
```

Apply the same typed evidence object to timeline responses or add a single typed `primaryEvidence` field while preserving existing fields for backward compatibility during one release.

- [ ] **Step 3: Combine source counts without double counting**

Count distinct source IDs across verified live classifications and approved historical evidence. A source appearing in both paths counts once per event.

- [ ] **Step 4: Run focused tests and commit**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker
git commit -m "feat(tracker): expose typed historical evidence"
```

---

### Task 8: Render historical summaries without quotation styling

**Files:**
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `EventTimeline.tsx`, `ReviewCaseCard.tsx`
- Modify tests: `TrackerPage.test.tsx`, `ReviewQueue.test.tsx`
- Modify: `App.css`

- [ ] **Step 1: Write failing UI tests**

Assert `HISTORICAL_REFERENCE` renders label `인간 검수 사실 요약`, source link, locator, and summary in a `<p>` or `<div>`, with no `blockquote`. Assert `VERBATIM` keeps the quotation styling and `원문 인용` label.

- [ ] **Step 2: Extend TypeScript DTOs**

```ts
export type EvidenceKind = "VERBATIM" | "HISTORICAL_REFERENCE";

export interface TrackerEvidence {
  kind: EvidenceKind;
  sourceLabel: string;
  url: string;
  evidenceQuote: string | null;
  factSummary: string | null;
  locator: string | null;
  accessedOn: string | null;
}
```

- [ ] **Step 3: Implement explicit rendering branches**

Never fall back from missing historical summary to `evidenceQuote`. Every external link uses `target="_blank" rel="noreferrer"`.

- [ ] **Step 4: Run frontend verification**

```powershell
npm test -- --run
npm run build
```

Expected: all Vitest tests and TypeScript/Vite build pass.

- [ ] **Step 5: Commit UI**

```powershell
git add apps/frontend/react-app/src/tracker apps/frontend/react-app/src/App.css
git commit -m "feat(tracker-ui): label historical reference evidence"
```

---

### Task 9: Full regression, storage audit, and GitOps handoff

**Files:**
- Modify: `docs/runbooks/tracker-phase1b-validation.md`
- Create: `docs/runbooks/tracker-reference-backfill-validation.md`
- Modify: `.superpowers/sdd/progress.md` (ignored; do not stage)

- [ ] **Step 1: Run complete verification**

```powershell
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
cd apps/frontend/react-app
npm test -- --run
npm run build
cd ../../..
git diff --check
```

- [ ] **Step 2: Run copyright/storage audit**

Assert no prohibited fields in historical resources, no line over 8 KiB, no binary files under tracker historical resources, database fixture storage below 1 MiB for 150 claims, and no source body in API snapshots.

- [ ] **Step 3: Fresh H2 import and dashboard smoke test**

Import V1–V8 plus `backfill-v1`, verify 35 nodes, 110–150 claims, historical source counts, snapshots, ETA outputs, and a second import no-op. Start local backend/frontend and inspect one live and one historical evidence card with zero console errors.

- [ ] **Step 4: Write Flux-only rollout runbook**

Document image release, Flyway checks, tracker-disabled schema verification, dataset hash/count queries, then the later `TRACKER_ENABLED=true` GitOps change. Do not include `kubectl apply` or secret values.

- [ ] **Step 5: Commit final handoff**

```powershell
git add docs/runbooks/tracker-phase1b-validation.md docs/runbooks/tracker-reference-backfill-validation.md
git commit -m "docs(tracker): document reference backfill operations"
```
