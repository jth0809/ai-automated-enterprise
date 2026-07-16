# Tracker WP3.5 Source Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded ISRO/CNSA HTML-index discovery and a reviewed UNOOSA/FAA/GovInfo governance ledger without allowing automatic Layer C scoring.

**Architecture:** Flyway V14 adds source identities, an article evaluation quarantine, and governance provenance tables. A same-host parser inserts metadata-only article candidates with evaluation disabled; a separate immutable JSON loader imports human-reviewed governance facts. Public read-only governance output and GitOps-safe disabled schedules complete the slice.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, H2 Oracle mode, Jsoup, Jackson, JUnit 5, Mockito, CiliumNetworkPolicy, PowerShell verifier.

## Global Constraints

- LIVE_MODEL remains `NOT_ACTIVATED`; no LLM call or Layer C state mutation is allowed.
- Do not add a pod, Kubernetes CronJob, secret, plain HTTP endpoint, or broad egress pattern.
- New network access is exact-host TCP 443 for `www.isro.gov.in` and `www.cnsa.gov.cn` only.
- JAXA reuses `https://global.jaxa.jp/rss/press.rdf`; do not add a duplicate collector.
- CNSA policy pages are Tier 1 `AGENCY`; CNSA hosted news is Tier 3 `GENERAL_MEDIA`.
- HTML candidates have `evaluation_allowed='N'`, `body_extraction_status='SKIPPED'`, and never reach gate/classify queries.
- The governance resource stores reviewer summaries and hashes only, never source body, quotes, HTML, PDF, image, or binary content.
- Dataset and response cardinalities are bounded ranges, never exact production row-count pins.
- Keep `.claude/`, the three protected backend demo fixtures, and `vite.wp33.local.config.ts` untracked.

## Execution status (2026-07-16)

- Tasks 1–5 implementation and their test sources exist in the working tree: V14,
  article quarantine, three fixed index channels, parser/job, 9-record governance
  resource·validator·loader·repository·API, and exact-host dark-launch GitOps.
- Static resource audit, GitOps verifier, frontend Vitest 4.1.10 78/78, installed
  compatibility TypeScript/build, `git diff --check`, and secret/workload/scoring/
  protected-file audits passed. Exact package-lock frontend verification remains open.
- Maven focused/full execution and kustomize render remain blocked by the environment;
  therefore combined “run and commit” steps and Task 6 completion remain unchecked.
- Exact evidence: [tracker-wp35-validation-evidence.md](../../research/tracker-wp35-validation-evidence.md).
  Checkboxes below record complete step gates, not merely the presence of code.

---

### Task 1: V14 source identities and article evaluation quarantine

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V14__tracker_source_expansion.sql`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSourceExpansionV14SchemaTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/domain/TrackerRepositoryTest.java`

**Interfaces:**
- Produces: `TrackerRepository.insertArticleCandidateIfNew(String url, String urlHash, long sourceId, String title, Instant publishedAt)`.
- Guarantees: candidates are metadata-only, `evaluation_allowed='N'`, extraction `SKIPPED`, and invisible to `findByStatus("INGESTED", limit)`.

- [ ] **Step 1: Write failing schema and repository tests**

Assert V14 creates `ARTICLE.EVALUATION_ALLOWED`, `GOVERNANCE_RECORD`, and
`GOVERNANCE_IMPORT`; verify source rows and tiers:

```java
assertSource("ISRO", "AGENCY", 1);
assertSource("CNSA", "AGENCY", 1);
assertSource("CNSA_HOSTED_MEDIA", "GENERAL_MEDIA", 3);
```

Insert one normal article and one candidate. Assert only the normal article is
returned by `findByStatus("INGESTED", 10)`.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
mvn.cmd -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" `
  -Dtest=TrackerSourceExpansionV14SchemaTest,TrackerRepositoryTest test
```

Expected: failure because V14 and `insertArticleCandidateIfNew` do not exist.

- [ ] **Step 3: Add V14 and the quarantine insert**

V14 must contain:

```sql
ALTER TABLE article ADD evaluation_allowed CHAR(1) DEFAULT 'Y' NOT NULL;
ALTER TABLE article ADD CONSTRAINT ck_article_evaluation_allowed
  CHECK (evaluation_allowed IN ('Y','N'));

CREATE TABLE governance_record (... record_id VARCHAR2(100) UNIQUE ...);
CREATE TABLE governance_import (... dataset_version VARCHAR2(80) UNIQUE ...);
```

Seed ISRO, CNSA, and CNSA_HOSTED_MEDIA idempotently and add exact source-domain
rows. Keep UNOOSA, FAA, GOVINFO, and JAXA unchanged.

The repository's INGESTED query must include:

```java
String pendingFilter = "INGESTED".equals(status)
        ? " AND body_extraction_status <> 'PENDING' AND evaluation_allowed = 'Y'"
        : "";
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Expected: all selected tests pass and old article behavior remains unchanged.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/backend/springboot-app/src/main/resources/db/migration/V14__tracker_source_expansion.sql `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSourceExpansionV14SchemaTest.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/domain/TrackerRepositoryTest.java
git commit -m "feat(tracker): quarantine expanded source candidates"
```

### Task 2: Bounded official HTML-index discovery

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/collection/OfficialIndexChannel.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/collection/OfficialIndexEntry.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/collection/OfficialIndexParser.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/OfficialIndexJob.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/collection/OfficialIndexParserTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/OfficialIndexJobTest.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`

**Interfaces:**
- `OfficialIndexChannel.defaults()` returns ISRO Press, CNSA policy, CNSA hosted-news channels.
- `OfficialIndexParser.parse(FetchedPage page, OfficialIndexChannel channel, int maxLinks)` returns immutable `List<OfficialIndexEntry>`.
- `OfficialIndexJob.runOnce()` isolates channel failures and inserts quarantined candidates.

- [ ] **Step 1: Write parser RED tests**

Test representative ISRO and CNSA fixtures inline. Assert same-host eligible HTML
links and explicit dates are returned. Assert these are rejected: external host,
IP literal, `http`, PDF, fragment-only navigation, malformed `chinadaily:` URL,
index self-link, non-content CNSA path, duplicate URL, and the 41st link.

- [ ] **Step 2: Implement channel and parser minimally**

Use exact patterns:

```java
ISRO("ISRO", URI.create("https://www.isro.gov.in/Press.html"), ISRO_PRESS);
CNSA_POLICY("CNSA", URI.create(
    "https://www.cnsa.gov.cn/english/n6465645/n6465648/index.html"), CNSA_CONTENT);
CNSA_NEWS("CNSA_HOSTED_MEDIA", URI.create(
    "https://www.cnsa.gov.cn/english/"), CNSA_CONTENT);
```

Normalize URLs, require exact host and HTTPS/443, and parse only unambiguous date
formats. Return null date if no format matches.

- [ ] **Step 3: Run parser tests and verify GREEN**

Expected: parser tests pass without a network call.

- [ ] **Step 4: Write job RED tests**

Use an injected fetch function and mocked repository. Verify one failed channel
does not stop later channels, repeated runs use idempotent repository insertion,
and every insert calls `insertArticleCandidateIfNew`.

- [ ] **Step 5: Implement the disabled scheduled job**

```java
@Component
@ConditionalOnProperty(prefix="tracker", name={"enabled", "official-index-enabled"}, havingValue="true")
final class OfficialIndexJob {
  @Scheduled(cron="${tracker.official-index-cron:0 23 4 * * WED}", zone="UTC")
  @SchedulerLock(name="tracker-official-index", lockAtMostFor="PT20M")
  public void runOnce() { ... }
}
```

Bound `maxLinks` to `1..40` even if configuration is larger.

- [ ] **Step 6: Run Task 2 tests and commit**

Expected: all parser/job tests pass.

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/collection `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/OfficialIndexJob.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/collection `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/OfficialIndexJobTest.java `
  apps/backend/springboot-app/src/main/resources/application.yml
git commit -m "feat(tracker): collect bounded official index candidates"
```

### Task 3: Reviewed governance ledger and idempotent loader

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/tracker/governance-ledger-v1.json`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/governance/GovernanceRecord.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/governance/GovernanceLedgerValidator.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/governance/GovernanceRepository.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/GovernanceLedgerLoader.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/governance/GovernanceLedgerValidatorTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/governance/GovernanceProductionDatasetTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/GovernanceLedgerLoaderTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`

**Interfaces:**
- `GovernanceLedgerValidator.validate(byte[] json)` returns immutable reviewed records.
- `GovernanceRepository.findImport(String version)`, `upsert(GovernanceRecord)`, and `recordImport(...)` implement version/hash semantics.
- `GovernanceLedgerLoader.loadIfNeeded()` is transactional and ShedLock-protected.

- [ ] **Step 1: Write validator RED tests**

Cover valid 6- and 9-record resources to prove no exact count pin. Reject unknown
keys, duplicates, invalid enum, unsafe URL/host, uppercase/short hash, prohibited
content/scoring keys at nested depth, non-primary path, and non-reviewed status.

- [ ] **Step 2: Implement strict validator and model**

Use Jackson tree allowlists before mapping. The exact source host map is:

```java
Map.of("UNOOSA", "www.unoosa.org", "FAA", "www.faa.gov", "GOVINFO", "www.govinfo.gov")
```

Accept 6–100 records and summaries of 40–1000 normalized characters.

- [ ] **Step 3: Add reviewed production resource**

Include the 2026 UNOOSA status compilation, 2025 accessions visible on the
official status page, FAA current licensing framework, GovInfo Part 450 rule,
and recent FAA/GovInfo regulatory notices. Every summary is reviewer-authored;
do not copy source paragraphs.

- [ ] **Step 4: Run validator/production tests and verify GREEN**

Expected: both a flexible synthetic dataset and the production resource pass.

- [ ] **Step 5: Write loader RED tests**

Verify first import, same hash no-op, same version different hash failure, and
new version upsert without duplicate record IDs.

- [ ] **Step 6: Implement repository, loader, and boot runner**

Properties:

```yaml
tracker:
  governance-on-boot: ${TRACKER_GOVERNANCE_ON_BOOT:true}
  governance-resource: ${TRACKER_GOVERNANCE_RESOURCE:tracker/governance-ledger-v1.json}
  governance-dataset-version: ${TRACKER_GOVERNANCE_DATASET_VERSION:governance-ledger-v1}
```

The runner follows the existing test/demo profile rule used by other immutable
loaders.

- [ ] **Step 7: Run Task 3 tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/resources/tracker/governance-ledger-v1.json `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/governance `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/GovernanceLedgerLoader.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/governance `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/GovernanceLedgerLoaderTest.java `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java `
  apps/backend/springboot-app/src/main/resources/application.yml
git commit -m "feat(tracker): import reviewed governance ledger"
```

### Task 4: Read-only governance API

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/GovernanceController.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/GovernanceControllerTest.java`

**Interfaces:**
- Produces: `GET /api/tracker/governance` with `CURRENT`, `STALE`, or `INSUFFICIENT_DATA` and at most 50 records.

- [ ] **Step 1: Write controller RED tests**

Cover no data, current import, stale import, output cap, source provenance, and
absence of `nodeCode`, `score`, `readiness`, `eta`, `quote`, and `body` fields.

- [ ] **Step 2: Implement minimal controller**

Status uses the latest import timestamp and a 400-day threshold. Sort records by
effective date descending then record ID. Never calculate a P6 score.

- [ ] **Step 3: Run tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/GovernanceController.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/GovernanceControllerTest.java
git commit -m "feat(tracker): expose reviewed governance ledger"
```

### Task 5: GitOps exact-host policy and dark launch

**Files:**
- Modify: `gitops/apps/backend-springboot/network-policy.yaml`
- Modify: `gitops/apps/backend-springboot/deployment.yaml`
- Modify: `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1`

**Interfaces:**
- Adds exact TCP 443 only for `www.isro.gov.in` and `www.cnsa.gov.cn`.
- Ships official-index polling disabled with weekly cron and max 40.

- [ ] **Step 1: Extend verifier first and observe RED**

Require each new host exactly once, bound to TCP 443, and exact env values:

```text
TRACKER_OFFICIAL_INDEX_ENABLED=false
TRACKER_OFFICIAL_INDEX_CRON=0 23 4 * * WED
TRACKER_OFFICIAL_INDEX_MAX_LINKS=40
```

Also assert no UNOOSA/FAA/GovInfo new FQDN block, secret, CronJob, or workload.

- [ ] **Step 2: Add policy and deployment values**

Place both hosts in the existing tracker source exact-host block. Add only the
three non-secret env values to the existing backend container.

- [ ] **Step 3: Run verifier and render**

```powershell
pwsh -File gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
kubectl kustomize gitops/apps/backend-springboot
```

Expected: verifier exits 0; render contains no new workload kind.

- [ ] **Step 4: Commit**

```powershell
git add -- gitops/apps/backend-springboot/network-policy.yaml `
  gitops/apps/backend-springboot/deployment.yaml `
  gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
git commit -m "chore(gitops): allow bounded WP3.5 source hosts"
```

### Task 6: Documentation and complete verification

**Files:**
- Create: `docs/research/tracker-wp35-validation-evidence.md`
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md`
- Modify: this plan, checking completed steps only after evidence exists

**Interfaces:**
- Produces an auditable handoff to WP3.4 without claiming G3 complete.

- [ ] **Step 1: Run focused and full backend verification**

Run Task 1–4 focused tests, then the complete Maven suite. Record exact totals;
do not reuse WP3.2 totals.

- [ ] **Step 2: Run frontend and production build**

WP3.5 has no UI change, but run the complete frontend suite and build to detect
integration regressions.

- [ ] **Step 3: Run GitOps and hygiene checks**

Run the egress verifier, `kubectl kustomize`, `git diff --check`, placeholder
scan, and secret scan. Confirm protected fixtures are absent from the index.

- [ ] **Step 4: Record evidence and update plans**

Document actual counts, parser boundaries, governance records, API state,
disabled flags, egress host set, and LIVE_MODEL state. Mark WP3.5 complete and
WP3.4 next; leave WP3.1 live promotion and G3 incomplete.

- [ ] **Step 5: Commit**

```powershell
git add -- docs/research/tracker-wp35-validation-evidence.md `
  docs/plans/multiplanetary-tracker-execution-plan.md `
  docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md `
  docs/superpowers/plans/2026-07-15-tracker-wp35-source-expansion-plan.md
git commit -m "docs(tracker): complete WP3.5 source expansion"
```
