# Tracker WP3.4 Forecast Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an honest three-track, four-estimate forecast comparison with reviewed institutional references, 90-day crowd smoothing, and a dark-launched authenticated Metaculus path.

**Architecture:** Flyway V15 adds reviewed forecast references and extends the pre-created numeric observation table. A strict immutable loader supplies institutional and crowd-question metadata; a triple-gated weekly collector can later store authorized crowd observations. A comparison service joins tracker and transport outputs without treating supporting indicators as equivalent predictions, and React renders the resulting matrix.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, H2 Oracle mode, Jackson, JDK HttpClient, JUnit 5, React 19, TypeScript, Vitest, CiliumNetworkPolicy, PowerShell verifier.

## Global Constraints

- LIVE_MODEL remains `NOT_ACTIVATED`; WP3.4 must not mutate node, event, readiness, snapshot, or ETA state.
- Do not invent a NASA or SpaceX year when the current official source is undated.
- Do not store a Metaculus community value before API authorization; metadata only is allowed in the reviewed production resource.
- Metaculus activation requires `tracker.metaculus-enabled=true`, `tracker.metaculus-terms-approved=true`, and a nonblank Vault-injected token.
- The production deployment keeps both Metaculus boolean gates `false` and does not reference a token secret.
- Network access is exact `www.metaculus.com:443`; no wildcard, plain HTTP, redirect, new pod, or Kubernetes CronJob.
- Crowd smoothing is an inclusive 90-day arithmetic mean rounded to one decimal.
- Keep `.claude/`, the three protected backend demo fixtures, and `vite.wp33.local.config.ts` untracked.
- Execute inline; the user explicitly disallowed subagents.

## Execution status (2026-07-16)

- Tasks 1–7 implementation and their test sources exist in the working tree: V15,
  reviewed 10-record reference loader, identified crowd history, 90-day smoother,
  triple-gated Metaculus client/job, comparison API, React panel, and GitOps dark launch.
- Frontend Vitest 4.1.10 focused/full tests (78/78), the installed compatibility
  TypeScript/build, GitOps verifier, resource canonical-hash audit, and repository
  hygiene checks passed. The exact package-lock Vite/plugin/TypeScript versions remain
  a verification gate because the shared node_modules is stale.
- Mock-backed browser rendering passed for the four-column labels and mobile horizontal
  containment; it is not counted as backend-connected E2E.
- The 2026 Q2 report is published as `PARTIAL`; Maven focused/full execution, kustomize,
  actual Spring API startup, and backend-connected browser verification remain blocked.
- Exact evidence: [tracker-wp34-validation-evidence.md](../../research/tracker-wp34-validation-evidence.md) ·
  [2026 Q2 G3 report](../../research/tracker-phase3-g3-coherence-report-2026-q2.md).
  Combined “run and commit” checkboxes remain open until every named action passes.

---

### Task 1: V15 forecast reference and observation schema

**Files:**

- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V15__tracker_forecast_comparison.sql`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerForecastV15SchemaTest.java`

**Interfaces:**

- Produces `forecast_reference(forecast_key PK, ...)` and `forecast_reference_import`.
- Extends `external_forecast` with `forecast_key`, `observation_sha256`, `observation_status`, and `smoothing_window_days`.
- Guarantees one identified observation per `(forecast_key, retrieved_on)` while legacy null-key rows remain readable but unused.

- [ ] **Step 1: Write the failing schema test**

Assert Flyway exposes all V15 columns and constraints, accepts all three track codes, rejects an invalid relation kind, and permits one legacy `external_forecast` row with a null key.

```java
assertColumn("FORECAST_REFERENCE", "FORECAST_KEY");
assertColumn("EXTERNAL_FORECAST", "SMOOTHING_WINDOW_DAYS");
assertConstraintRejects("UNKNOWN_TRACK");
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
mvn.cmd -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" `
  -Dtest=TrackerForecastV15SchemaTest test
```

Expected: fail because V15 and `forecast_reference` do not exist.

- [ ] **Step 3: Add V15**

Use the following core shape:

```sql
CREATE TABLE forecast_reference (
  forecast_key VARCHAR2(100) PRIMARY KEY,
  source_type VARCHAR2(15) NOT NULL,
  source_name VARCHAR2(100) NOT NULL,
  track_code VARCHAR2(20) NOT NULL,
  question VARCHAR2(500) NOT NULL,
  target_definition VARCHAR2(1000) NOT NULL,
  display_status VARCHAR2(30) NOT NULL,
  forecast_year NUMBER(6,1),
  forecast_year_low NUMBER(6,1),
  forecast_year_high NUMBER(6,1),
  relation_kind VARCHAR2(20) NOT NULL,
  source_url VARCHAR2(1000) NOT NULL,
  source_locator VARCHAR2(300),
  accessed_on DATE NOT NULL,
  ingestion_mode VARCHAR2(20) NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  fact_summary VARCHAR2(1000) NOT NULL,
  dataset_version VARCHAR2(80) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE external_forecast ADD forecast_key VARCHAR2(100)
  REFERENCES forecast_reference(forecast_key);
ALTER TABLE external_forecast ADD observation_sha256 CHAR(64);
ALTER TABLE external_forecast ADD observation_status VARCHAR2(30);
ALTER TABLE external_forecast ADD smoothing_window_days NUMBER(3);
CREATE UNIQUE INDEX uq_external_forecast_identified
  ON external_forecast(forecast_key, retrieved_on);
```

Constrain enums and year bounds exactly as the design specifies.

- [ ] **Step 4: Run the focused test and verify GREEN**

Expected: the selected schema test passes.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- apps/backend/springboot-app/src/main/resources/db/migration/V15__tracker_forecast_comparison.sql `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerForecastV15SchemaTest.java
git commit -m "feat(tracker): add forecast comparison schema"
```

### Task 2: Strict reviewed reference dataset and loader

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastReference.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastReferenceValidator.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastRepository.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ForecastReferenceLoader.java`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/forecast-reference-v1.json`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/ForecastReferenceValidatorTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/ForecastProductionDatasetTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/ForecastReferenceLoaderTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/config/TrackerConfigTest.java`

**Interfaces:**

- `ForecastReferenceValidator.validate(byte[] bytes, Clock clock)` returns immutable records and errors.
- `ForecastRepository.upsertReference(ForecastReference value, String datasetVersion)` persists one reviewed definition.
- `ForecastReferenceLoader.loadIfNeeded()` implements version/hash locking.

- [ ] **Step 1: Write validator RED tests**

Accept flexible 6- and 10-record datasets. Reject unknown keys, duplicate keys, invalid enums, future access dates, unsafe or mismatched hosts, malformed hashes, invented current years, crowd numeric values in reviewed metadata, and prohibited nested content/scoring keys.

```java
assertContains(validate(withNested("body", "copied text")), "prohibited key body");
assertContains(validate(crowdWithYear(2045.0)), "crowd reference must not carry a value");
```

- [ ] **Step 2: Implement the record and validator**

Use exact source-host mapping:

```java
Map.of(
    "NASA", "www.nasa.gov",
    "SPACEX", "www.spacex.com",
    "METACULUS", "www.metaculus.com");
```

Allow 6 to 50 records, 40 to 1,000 normalized summary characters, years from 2026.0 to 2300.0, and only the design enums.

- [ ] **Step 3: Add the production resource**

Create reviewed rows for current NASA/SpaceX landing, return, and settlement statements; the SpaceX 2028 cargo precursor; one NASA 2030s legacy record; and two value-free Metaculus metadata rows for posts 3515 and 39073. Use locally authored summaries and hashes of the canonical fields.

- [ ] **Step 4: Run validator and production tests**

Expected: all selected tests pass and the resource contains no Metaculus community number.

- [ ] **Step 5: Write loader RED tests**

Verify first load, same-hash no-op, same-version/different-hash failure before mutation, new-version upsert, and no node/event/snapshot row-count changes.

- [ ] **Step 6: Implement repository, loader, and boot runner**

Add:

```yaml
tracker:
  forecast-reference-on-boot: ${TRACKER_FORECAST_REFERENCE_ON_BOOT:true}
  forecast-reference-resource: ${TRACKER_FORECAST_REFERENCE_RESOURCE:tracker/forecast-reference-v1.json}
  forecast-reference-dataset-version: ${TRACKER_FORECAST_REFERENCE_DATASET_VERSION:forecast-reference-v1}
```

The boot runner follows the existing immutable-loader profile rule.

- [ ] **Step 7: Run Task 2 tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/ForecastReferenceLoader.java `
  apps/backend/springboot-app/src/main/resources/tracker/forecast-reference-v1.json `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/ForecastReferenceLoaderTest.java `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java `
  apps/backend/springboot-app/src/main/resources/application.yml `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/config/TrackerConfigTest.java
git commit -m "feat(tracker): load reviewed forecast references"
```

### Task 3: Crowd observation history and 90-day smoothing

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ExternalForecastObservation.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastSmoother.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/ForecastSmootherTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastRepository.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/ForecastRepositoryTest.java`

**Interfaces:**

- `ForecastSmoother.mean90Day(List<ExternalForecastObservation>, LocalDate asOf)` returns `BigDecimal` scale 1.
- `ForecastRepository.saveCrowdObservation(...)` is idempotent on key/date/hash and rejects conflicts.
- `ForecastRepository.findCrowdWindow(String forecastKey, LocalDate start, LocalDate end)` returns identified rows only.

- [ ] **Step 1: Write smoothing RED tests**

Use observations on day 0, day -30, day -89, and day -90. Assert the first three average to one decimal and the day -90 value is excluded. Assert empty input returns empty and institutional input is rejected.

- [ ] **Step 2: Implement minimal smoothing**

```java
LocalDate start = asOf.minusDays(89);
List<BigDecimal> included = observations.stream()
    .filter(item -> !item.retrievedOn().isBefore(start))
    .filter(item -> !item.retrievedOn().isAfter(asOf))
    .map(ExternalForecastObservation::forecastYear)
    .toList();
```

Average using decimal arithmetic and `RoundingMode.HALF_UP` at scale 1.

- [ ] **Step 3: Write repository RED tests**

Verify insert, same-value no-op, same-day conflict failure, latest lookup, range lookup, and exclusion of old null-key rows.

- [ ] **Step 4: Implement observation repository methods**

Persist both raw and smoothed year, status `CURRENT`, window `90`, and a SHA-256 over forecast key, raw year, retrieved date, and API post identity.

- [ ] **Step 5: Run Task 3 tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast
git commit -m "feat(tracker): smooth crowd forecast history"
```

### Task 4: Triple-gated Metaculus client and weekly job

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/MetaculusSnapshot.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/MetaculusSnapshotParser.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/MetaculusClient.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/MetaculusForecastJob.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/MetaculusSnapshotParserTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/MetaculusClientTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/MetaculusForecastJobTest.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`

**Interfaces:**

- `MetaculusSnapshotParser.parse(int expectedPostId, byte[] json)` returns an optional date-center snapshot.
- `MetaculusClient.fetch(int postId)` performs one bounded authenticated GET.
- `MetaculusForecastJob.runOnce()` processes at most two configured crowd references independently.

- [ ] **Step 1: Write parser RED tests**

Use an API-contract fixture containing `question.type=date` and `question.aggregations.recency_weighted.latest.centers[0]`. Verify post ID, finite date conversion, missing aggregate empty result, binary question rejection, impossible timestamp rejection, and unknown shape failure without permissive guessing.

- [ ] **Step 2: Implement the fail-closed parser**

Convert the Unix timestamp to a fractional UTC year:

```java
double year = date.getYear()
    + (date.getDayOfYear() - 1.0) / date.lengthOfYear();
```

- [ ] **Step 3: Write client RED tests**

Inject a transport interface and assert exact URI `https://www.metaculus.com/api/posts/3515/?with_cp=true`, `Authorization: Token ...`, 1 MiB limit, no redirects, bounded token length, and safe error classes for 401, 403, 429, and 5xx. Assert errors never contain the token.

- [ ] **Step 4: Implement the JDK client**

Use `HttpClient.Redirect.NEVER`, connect timeout 10 seconds, request timeout 20 seconds, `Accept: application/json`, and one request only.

- [ ] **Step 5: Write job RED tests**

Verify two post mappings, one-post failure isolation, no mutation on missing aggregate, 90-day smoothing on successful insertion, max-post cap 2, schedule metadata, ShedLock, and bean absence unless both boolean gates are true.

- [ ] **Step 6: Implement the gated job and properties**

```yaml
tracker:
  metaculus-enabled: ${TRACKER_METACULUS_ENABLED:false}
  metaculus-terms-approved: ${TRACKER_METACULUS_TERMS_APPROVED:false}
  metaculus-token: ${TRACKER_METACULUS_TOKEN:}
  metaculus-cron: ${TRACKER_METACULUS_CRON:0 17 5 * * MON}
  metaculus-max-posts: ${TRACKER_METACULUS_MAX_POSTS:2}
```

Use `@ConditionalOnProperty` over `enabled`, `metaculus-enabled`, and `metaculus-terms-approved`, all requiring `true`. Validate a nonblank token in the production constructor.

- [ ] **Step 7: Run Task 4 tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/MetaculusForecastJob.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/MetaculusForecastJobTest.java `
  apps/backend/springboot-app/src/main/resources/application.yml
git commit -m "feat(tracker): dark launch crowd forecast collection"
```

### Task 5: Honest comparison service and public API

**Files:**

- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastEstimate.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastComparisonRow.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastComparison.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast/ForecastComparisonService.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/ForecastComparisonController.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast/ForecastComparisonServiceTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/ForecastComparisonControllerTest.java`

**Interfaces:**

- `ForecastComparisonService.current()` returns exactly three ordered rows and four estimate groups per row.
- Produces `GET /api/tracker/forecast-comparison`.

- [ ] **Step 1: Write service RED tests**

Assert the exact semantic map from design section 3. Verify model ETA appears only on `SETTLEMENT`; transport values are marked `SUPPORTING`; 100-person crowd data is `PROXY`; NASA/SpaceX undated targets remain null; legacy records are visibly legacy; crowd staleness is 45 days; and disabled crowd data yields `PARTIAL`, not a fabricated year.

- [ ] **Step 2: Implement immutable DTOs and service**

Use `TrackerRepository.findLatestSnapshot(0)`, `TransportEconomicsRepository.findLatestProjection()`, and `ForecastRepository` reads. Keep all explanatory labels in the service contract so UI clients cannot silently reinterpret values.

- [ ] **Step 3: Write controller RED tests**

Verify status codes, field names, exactly three tracks, the four estimate keys, provenance links, null preservation, and absence of raw response, token, body, quote, node mutation, score, and readiness fields.

- [ ] **Step 4: Implement the controller**

Return the service DTO directly from a read-only conditional controller at `/api/tracker/forecast-comparison`.

- [ ] **Step 5: Run Task 5 tests and commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/ForecastComparisonController.java `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/forecast `
  apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/ForecastComparisonControllerTest.java
git commit -m "feat(tracker): expose forecast comparison matrix"
```

### Task 6: Four-estimate React panel

**Files:**

- Create: `apps/frontend/react-app/src/tracker/ForecastComparisonPanel.tsx`
- Create: `apps/frontend/react-app/src/tracker/ForecastComparisonPanel.test.tsx`
- Create: `apps/frontend/react-app/src/tracker/ForecastComparisonResponsiveCss.test.ts`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`
- Modify: `apps/frontend/react-app/vitest.config.ts`

**Interfaces:**

- `getForecastComparison(): Promise<ForecastComparison>` fetches the new API.
- `<ForecastComparisonPanel comparison={value} />` renders three rows and four estimate columns.

- [ ] **Step 1: Write component RED tests**

Assert the four column headings, three track rows, null-state Korean labels, provenance links, legacy badge, proxy/supporting copy, $200/kg assumption copy, and absence of a false equivalence phrase.

- [ ] **Step 2: Add API types and implement the panel**

Use semantic `<table>`, `<caption>`, `<th scope="row">`, and source links with `target="_blank" rel="noreferrer"`. Format point years to one decimal only when needed and ranges as `low–high`.

- [ ] **Step 3: Write TrackerPage RED integration test**

Add the seventh fetch stub and verify an unavailable comparison endpoint degrades only the panel while the tracker dashboard still renders.

- [ ] **Step 4: Integrate with independent failure handling**

Fetch core tracker data as before, then load forecast comparison into a separate nullable/error state. Do not place the new request in the existing all-or-nothing `Promise.all` failure boundary.

- [ ] **Step 5: Add responsive styles**

Add `.forecast-comparison`, `.forecast-table-wrap`, `.forecast-status`, and estimate cell styles. Preserve horizontal scrolling below 900px and current visual language.

The scroll wrapper and each parent in its min-content chain must be shrinkable
(`min-width: 0`) so the 920px table does not move the whole dashboard off-screen.

- [ ] **Step 6: Run frontend tests and commit**

```powershell
npm test -- --run
npm run build
git add -- apps/frontend/react-app/src/tracker/ForecastComparisonPanel.tsx `
  apps/frontend/react-app/src/tracker/ForecastComparisonPanel.test.tsx `
  apps/frontend/react-app/src/tracker/ForecastComparisonResponsiveCss.test.ts `
  apps/frontend/react-app/src/tracker/api.ts `
  apps/frontend/react-app/src/tracker/TrackerPage.tsx `
  apps/frontend/react-app/src/tracker/TrackerPage.test.tsx `
  apps/frontend/react-app/src/App.css `
  apps/frontend/react-app/vitest.config.ts
git commit -m "feat(tracker): show honest forecast comparison"
```

### Task 7: Exact-host GitOps dark launch

**Files:**

- Modify: `gitops/apps/backend-springboot/network-policy.yaml`
- Modify: `gitops/apps/backend-springboot/deployment.yaml`
- Modify: `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1`

**Interfaces:**

- Allows `www.metaculus.com` TCP 443 exactly once.
- Ships both activation gates false, weekly cron, and max posts 2.
- Does not add a token environment variable or secret reference.

- [ ] **Step 1: Extend the verifier and observe RED**

Require exact host/port and values:

```text
TRACKER_METACULUS_ENABLED=false
TRACKER_METACULUS_TERMS_APPROVED=false
TRACKER_METACULUS_CRON=0 17 5 * * MON
TRACKER_METACULUS_MAX_POSTS=2
```

Reject wildcard Metaculus hostnames, plain HTTP, `TRACKER_METACULUS_TOKEN`, new ExternalSecret keys, CronJobs, and workloads.

- [ ] **Step 2: Add the CNP and deployment values**

Add the exact FQDN to the existing tracker source egress block and only the four nonsecret environment values.

- [ ] **Step 3: Run verifier and render**

```powershell
pwsh -File gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
kubectl kustomize gitops/apps/backend-springboot
```

Expected: verifier exits 0; render contains no new workload and no token reference.

- [ ] **Step 4: Commit Task 7**

```powershell
git add -- gitops/apps/backend-springboot/network-policy.yaml `
  gitops/apps/backend-springboot/deployment.yaml `
  gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
git commit -m "chore(gitops): dark launch forecast source access"
```

### Task 8: WP3.5 closure, G3 report, and complete verification

**Files:**

- Create: `docs/research/tracker-wp35-validation-evidence.md`
- Create: `docs/research/tracker-wp34-validation-evidence.md`
- Create: `docs/research/tracker-phase3-g3-coherence-report-2026-q2.md`
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md`
- Modify: `docs/superpowers/plans/2026-07-15-tracker-wp35-source-expansion-plan.md`
- Modify: this plan after evidence exists.

**Interfaces:**

- Produces auditable WP3.5 and WP3.4 evidence and the first Phase 3 quarterly coherence report.
- Leaves LIVE_MODEL, LL2 Layer C promotion, WP3.5 live polling, and Metaculus live polling disabled.

- [ ] **Step 1: Run focused and full backend verification**

Run all new WP3.5/WP3.4 focused tests, then the complete Maven suite. Record exact counts and failures from the fresh output.

- [ ] **Step 2: Run frontend, build, and browser verification**

Run complete Vitest and production build. Start local backend/frontend with reviewed datasets, open the Tracker tab, verify the new panel visually, and record browser console errors.

- [ ] **Step 3: Run GitOps and repository hygiene checks**

Run the verifier, kustomize render, `git diff --check`, placeholder scan, secret scan, and exact protected-file index check.

- [ ] **Step 4: Publish the 2026 Q2 coherence report**

Record actual states for model ETA, transport central/easy/hard scenarios, transport B-to-C coherence, K-index, crowd authorization, institutional targets, and source coverage. Use `PARTIAL` or `INSUFFICIENT_DATA` where required; do not convert absence into a year.

- [ ] **Step 5: Update plans and commit documentation**

Mark WP3.5 and WP3.4 complete only if their actual verification gates pass. Mark G3 approved only if the panel and report are verifiably available. Otherwise record the exact blocked checks and leave the gate open.

```powershell
git add -- docs/research/tracker-wp35-validation-evidence.md `
  docs/research/tracker-wp34-validation-evidence.md `
  docs/research/tracker-phase3-g3-coherence-report-2026-q2.md `
  docs/plans/multiplanetary-tracker-execution-plan.md `
  docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md `
  docs/superpowers/plans/2026-07-15-tracker-wp35-source-expansion-plan.md `
  docs/superpowers/plans/2026-07-15-tracker-wp34-forecast-comparison-plan.md
git commit -m "docs(tracker): complete Phase 3 evidence"
```

- [ ] **Step 6: Push the branch and update PR 40**

Push only after fresh verification and exact staging. Update the existing PR description with WP3.2, WP3.5, WP3.4, G3 status, disabled live gates, and the Metaculus authorization prerequisite.
