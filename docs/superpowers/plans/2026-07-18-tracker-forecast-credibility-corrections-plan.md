# Tracker Forecast Credibility Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute inline; the user prohibited subagents.

**Goal:** Make readiness and ETA bottlenecks fully data-driven and distinct while publishing honest provenance, baseline skill, and evidence coverage for the Phase 4 forecast.

**Architecture:** A pure backend indicator service derives complete six-pillar state and feeds a backward-compatible summary contract. Versioned backtest v2 evaluations and a read-only evidence coverage service add credibility diagnostics without changing active parameters; React renders the new contracts near the forecast and in the methodology section.

**Tech Stack:** Java 25, Spring Boot 4.1, JdbcClient, Flyway, H2/Oracle-compatible SQL, JUnit 5, React 19, TypeScript 7, Vitest, Testing Library, Vite.

## Global Constraints

- Do not activate `LIVE_MODEL`, external polling, automatic prediction publication, or automatic parameter promotion.
- Preserve all Phase 4 v1 reports and existing API fields; add versioned/compatible fields only.
- Do not delete existing files or touch `.claude/`, `application-demo.yml`, `application-refbackfill.yml`, `backfill-demo.json`, or `vite.wp33.local.config.ts`.
- No new dependency, network connection, secret, workload, or egress rule.
- Use TDD for every behavioral change and commit each independently testable task.
- Render 10th-to-90th percentile output as “모델 내부 민감도 80% 구간”, not an externally calibrated confidence interval.

---

### Task 1: Automatic readiness and ETA indicators

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerIndicators.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerIndicatorService.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerIndicatorServiceTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerController.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerControllerTest.java`

**Interfaces:**
- Consumes: six `Optional<SnapshotRow>` values supplied by `TrackerRepository.findLatestSnapshot(int)`.
- Produces: `TrackerIndicators derive(List<SnapshotRow> snapshots)` with status, bottleneck arrays, missing arrays, and shared snapshot metadata.

- [ ] **Step 1: Write failing pure service tests**

Cover these exact cases in `TrackerIndicatorServiceTest`:

```java
assertEquals(List.of(3), result.readinessBottleneckPillars());
assertEquals(List.of(4), result.etaBottleneckPillars());
assertEquals(List.of(), result.unresolvedEtaPillars());

assertEquals(List.of(2, 3), tied.readinessBottleneckPillars());
assertEquals(List.of(1, 6), unresolved.etaBottleneckPillars());
assertEquals(List.of(1, 6), unresolved.unresolvedEtaPillars());

assertEquals(TrackerIndicators.Status.INCOMPLETE_SNAPSHOT, partial.status());
assertEquals(List.of(5), partial.missingPillars());
assertEquals(List.of(), partial.readinessBottleneckPillars());
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `apps/backend/springboot-app`:

```powershell
.\mvnw.cmd -Dtest=TrackerIndicatorServiceTest test
```

Expected: compilation failure because `TrackerIndicatorService` does not exist.

- [ ] **Step 3: Implement the pure derivation**

Use constants `READINESS_TOLERANCE = 1e-9`, `ETA_TOLERANCE_YEARS = 0.05`, and `PILLARS = 1..6`. Reject duplicate pillar rows. For complete data, require one shared latest `snapshotDate`; otherwise return `INCOMPLETE_SNAPSHOT`. Sort every output array.

- [ ] **Step 4: Extend the summary contract through the service**

Replace `TrackerController.bottleneckPillar()` with one load of six rows and one service call. Add:

```java
body.put("indicatorStatus", indicators.status().name());
body.put("readinessBottleneckPillars", indicators.readinessBottleneckPillars());
body.put("etaBottleneckPillars", indicators.etaBottleneckPillars());
body.put("unresolvedEtaPillars", indicators.unresolvedEtaPillars());
body.put("missingPillars", indicators.missingPillars());
body.put("snapshotDate", indicators.snapshotDate());
body.put("paramsVersion", indicators.paramsVersion());
body.put("graphVersion", indicators.graphVersion());
body.put("bottleneckPillar", indicators.legacyBottleneckPillar());
```

Change `TrackerController.LABEL` to `현 추세 지속 시나리오 · 모델 내부 민감도 80% 구간`.

- [ ] **Step 5: Prove controller values change with repository data**

Update `TrackerControllerTest` to assert the expanded key set and exact new label. Add a transaction-local test that inserts six same-date snapshots with P3 lowest readiness and P4 latest ETA, asserts `[3]` and `[4]`, replaces the rows with P2/P6 values, then asserts the arrays change without changing code.

- [ ] **Step 6: Run focused backend tests and commit**

```powershell
.\mvnw.cmd -Dtest=TrackerIndicatorServiceTest,TrackerControllerTest test
git diff --check
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerIndicators.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerIndicatorService.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerController.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerIndicatorServiceTest.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerControllerTest.java
git commit -m "feat(tracker): derive forecast bottlenecks automatically"
```

Expected: focused tests pass and only the listed files are committed.

---

### Task 2: Dual bottleneck and snapshot status UI

**Files:**
- Create: `apps/frontend/react-app/src/tracker/ForecastStatusBar.tsx`
- Create: `apps/frontend/react-app/src/tracker/ForecastStatusBar.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/PillarRadar.tsx`
- Modify: `apps/frontend/react-app/src/tracker/PillarRadar.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/PillarEtaList.tsx`
- Modify: `apps/frontend/react-app/src/tracker/PillarEtaList.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/Countdown.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- Consumes: expanded `Summary` arrays/status/metadata and existing `PillarSummary[]`.
- Produces: accessible dual labels and array-based highlighting with no hard-coded pillar.

- [ ] **Step 1: Write failing component tests**

Use fixtures where `readinessBottleneckPillars=[3]` and `etaBottleneckPillars=[4]`. Assert:

```tsx
expect(screen.getByText(/현재 준비도 병목 P3 거주 인프라/)).toBeInTheDocument();
expect(screen.getByText(/전체 ETA 병목 P4 자원·에너지/)).toBeInTheDocument();
expect(screen.getByText(/스냅샷 2026-07-18/)).toBeInTheDocument();
```

Add tie fixtures `[2,3]`, unresolved fixtures `[1,6]`, and an incomplete fixture with `missingPillars=[5]`.

- [ ] **Step 2: Run focused frontend tests and verify RED**

Run from `apps/frontend/react-app`:

```powershell
npx vitest run src/tracker/ForecastStatusBar.test.tsx src/tracker/PillarRadar.test.tsx src/tracker/PillarEtaList.test.tsx src/tracker/TrackerPage.test.tsx
```

Expected: failure because the status bar and array props are absent.

- [ ] **Step 3: Extend TypeScript API validation**

Add to `Summary`:

```ts
indicatorStatus: "COMPLETE" | "INCOMPLETE_SNAPSHOT";
readinessBottleneckPillars: number[];
etaBottleneckPillars: number[];
unresolvedEtaPillars: number[];
missingPillars: number[];
snapshotDate: string | null;
paramsVersion: string | null;
graphVersion: string | null;
```

Validate pillar arrays as unique integers from 1 through 6. Keep `bottleneckPillar` for old clients but do not use it for new rendering.

- [ ] **Step 4: Implement accessible automatic rendering**

Pass `readinessBottleneckPillars` to `PillarRadar` and `etaBottleneckPillars` plus `unresolvedEtaPillars` to `PillarEtaList`. Use `Set<number>` membership. Add visible “준비도 병목”, “전체 ETA 병목”, and “추세 미해결” text. Render `ForecastStatusBar` directly below `Countdown`.

- [ ] **Step 5: Add responsive styles and verify GREEN**

Add compact wrapping styles for `.forecast-status`, `.forecast-status-item`, `.pillar-eta-bottleneck`, and `.pillar-eta-unresolved`; ensure 375 px width does not require document-level horizontal scrolling.

```powershell
npx vitest run src/tracker/ForecastStatusBar.test.tsx src/tracker/PillarRadar.test.tsx src/tracker/PillarEtaList.test.tsx src/tracker/Countdown.test.tsx src/tracker/TrackerPage.test.tsx
npm run build
```

- [ ] **Step 6: Commit frontend automatic indicators**

```powershell
git diff --check
git add apps/frontend/react-app/src/tracker/api.ts apps/frontend/react-app/src/tracker/TrackerPage.tsx apps/frontend/react-app/src/tracker/TrackerPage.test.tsx apps/frontend/react-app/src/tracker/ForecastStatusBar.tsx apps/frontend/react-app/src/tracker/ForecastStatusBar.test.tsx apps/frontend/react-app/src/tracker/PillarRadar.tsx apps/frontend/react-app/src/tracker/PillarRadar.test.tsx apps/frontend/react-app/src/tracker/PillarEtaList.tsx apps/frontend/react-app/src/tracker/PillarEtaList.test.tsx apps/frontend/react-app/src/tracker/Countdown.test.tsx apps/frontend/react-app/src/App.css
git commit -m "feat(tracker): distinguish automatic forecast bottlenecks"
```

---

### Task 3: Backtest v2 skill and baseline evaluation

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V20__tracker_credibility_corrections.sql`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest/BacktestCandidate.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest/BacktestFingerprint.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest/BacktestHarness.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest/BacktestReport.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest/BacktestRepository.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/CredibilityController.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backtest/BacktestHarnessTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backtest/BacktestRepositoryTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backtest/BacktestReportCodecTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backtest/BacktestServiceTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/CredibilityControllerTest.java`

**Interfaces:**
- Produces: v2 immutable `BacktestReport.ModelEvaluation` entries for `SELECTED`, `ACTIVE`, `PERSISTENCE`, and `ALWAYS_NO_CHANGE`.
- Produces: public `skillStatus`, `readinessMaeRatioVsPersistence`, and `selectedMatchesActive` fields.

- [ ] **Step 1: Write failing graph-coordinate and baseline tests**

Extend `BacktestHarnessTest` to prove:

```java
assertEquals("backtest-report-v2", report.reportVersion());
assertEquals("backtest-candidates-v2", report.candidateRegistryVersion());
assertEquals(Set.of(SELECTED, ACTIVE, PERSISTENCE, ALWAYS_NO_CHANGE), roles);
assertEquals(.0, persistencePrediction - currentReadiness, 1e-12);
```

Add a non-default delta-scale fixture where the target readiness differs between the central and candidate graphs, and assert the reported actual readiness uses the candidate graph.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\mvnw.cmd -Dtest=BacktestHarnessTest,BacktestRepositoryTest,CredibilityControllerTest test
```

- [ ] **Step 3: Add the append-only schema**

Create `backtest_model_evaluation` keyed by `(run_id, model_role, metric_code, pillar)` with role checks, nullable candidate tuple for baselines, calibration/holdout values, counts, and `OK`/`INSUFFICIENT_DATA` status checks. Also add nullable `candidate_record_count` to `backfill_import` with a positive-count constraint for Task 4. Do not alter V18 or any stored v1 row.

- [ ] **Step 4: Fix the historical evaluation coordinate system**

Build each candidate history through `maxTarget`, use that same history for cutoff-visible trend input and target truth, and remove the central-graph truth lookup from candidate error calculation. Retain the central graph only as the input from which a candidate graph is deterministically derived.

- [ ] **Step 5: Build independent active and naive evaluations**

Derive the active tuple from `input.model().params().windowM()`, `kShrink()`, and delta scale `1.0`. Reuse selected evaluation only when tuples match. Build persistence metrics from `predictedReadiness=currentReadiness` and always-no-change direction from `predictedAdvance=false`. Store aggregate and per-pillar comparisons in the report and V20 table.

- [ ] **Step 6: Version fingerprints and expose skill status**

Set fingerprint input, candidate registry, and report constants to v2 so v1 hashes cannot be reused. In the API compute:

```text
ratio = selected holdout READINESS_MAE / persistence holdout READINESS_MAE
ratio < 1  -> OUTPERFORMS_PERSISTENCE
ratio >= 1 -> NO_SKILL_VS_PERSISTENCE
missing/zero baseline -> INSUFFICIENT_DATA
v1 report -> LEGACY_NOT_EVALUATED
```

Never update `parameter_set.active`.

- [ ] **Step 7: Verify persistence, legacy reads, and focused tests**

Add repository assertions for evaluation row counts, idempotent reuse, atomic replacement, and an empty-evaluation legacy v1 decode. Then run:

```powershell
.\mvnw.cmd -Dtest=BacktestHarnessTest,BacktestRepositoryTest,BacktestReportCodecTest,BacktestServiceTest,CredibilityControllerTest test
```

- [ ] **Step 8: Commit backtest v2**

```powershell
git diff --check
git add apps/backend/springboot-app/src/main/resources/db/migration/V20__tracker_credibility_corrections.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backtest apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/CredibilityController.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backtest apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/CredibilityControllerTest.java
git commit -m "feat(tracker): add backtest baseline skill diagnostics"
```

---

### Task 4: Dynamic evidence coverage and provenance UI

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/EvidenceCoverage.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/EvidenceCoverageService.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/EvidenceCoverageServiceTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/BackfillImportRow.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BackfillLoader.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/CredibilityController.java`
- Create: `apps/frontend/react-app/src/tracker/forecastAlignment.ts`
- Create: `apps/frontend/react-app/src/tracker/forecastAlignment.test.ts`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/MethodologyCredibility.tsx`
- Modify: `apps/frontend/react-app/src/tracker/MethodologyCredibility.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/MethodologyCredibilityCss.test.ts`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- Produces: `EvidenceCoverage` inside `MethodologyResponse`.
- Consumes: optional `Summary` in `MethodologyCredibility` to compare the top snapshot with the latest projection.

- [ ] **Step 1: Write failing H2 coverage tests**

After loading the test backfill, assert candidate count metadata, distinct claims/candidates, active/directly mapped nodes, single-evidence claims, and verification-level counts. Add an empty-data test that returns zeros and empty maps.

- [ ] **Step 2: Persist candidate corpus size and aggregate coverage**

Extend `BackfillImportRow` with nullable `candidateRecordCount` plus a compatibility constructor. Store `validated.candidates().size()` on new imports and repair only the nullable count on an existing hash-matching import. Implement aggregate SQL in `EvidenceCoverageService`; never parse documentation or hard-code production totals.

- [ ] **Step 3: Publish coverage with methodology**

Inject the service into `CredibilityController` and add non-null `evidenceCoverage` to `MethodologyResponse`. Change the first honesty label to:

```text
ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델 내부 민감도의 80%다. 모형족 오류·자료 선택 오차·목표 임계값 불확실성·외부 충격은 포함하지 않는다.
```

- [ ] **Step 4: Write failing frontend provenance and credibility tests**

Test all alignment states with pure `forecastAlignment(summary, projection)`: `ALIGNED`, `DIFFERENT_RUN`, `VERSION_MISMATCH`, and `UNAVAILABLE`. Test that the methodology card renders coverage ratios, simulation limitations, selected/active/baseline rows, “계산 완료”, and no green validation wording.

- [ ] **Step 5: Implement projection alignment and credibility rendering**

Pass `summary` into `MethodologyCredibility`. Show snapshot date/version next to projection completion/version and render a warning for non-aligned states. Show evidence coverage counts and ratios. Render backtest model evaluations and skill status; legacy reports show “기준선 미평가(v1 기록)”.

- [ ] **Step 6: Verify focused backend and frontend tests**

```powershell
.\mvnw.cmd -Dtest=EvidenceCoverageServiceTest,CredibilityControllerTest,BackfillLoaderTest test
npx vitest run src/tracker/forecastAlignment.test.ts src/tracker/MethodologyCredibility.test.tsx src/tracker/TrackerPage.test.tsx
npm run build
```

- [ ] **Step 7: Commit coverage and provenance**

```powershell
git diff --check
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/BackfillImportRow.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BackfillLoader.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java apps/frontend/react-app/src/tracker apps/frontend/react-app/src/App.css
git commit -m "feat(tracker): publish forecast provenance and evidence coverage"
```

---

### Task 5: Full regression and browser acceptance

**Files:**
- Modify only if a failing test reveals an in-scope defect in files already named above.

**Interfaces:**
- Consumes: completed Tasks 1 through 4.
- Produces: verified local tracker with no live-model activation.

- [ ] **Step 1: Run all backend tests**

```powershell
.\mvnw.cmd test
```

Expected: all Maven tests pass with no failures or errors.

- [ ] **Step 2: Run all frontend tests and production build**

```powershell
npm test
npm run build
```

Expected: all Vitest tests and TypeScript/Vite build pass.

- [ ] **Step 3: Run repository hygiene checks**

```powershell
git diff --check
git status --short
```

Expected: only protected pre-existing untracked fixtures remain; no secret or generated build output is staged.

- [ ] **Step 4: Start local backend and frontend without live features**

Backend profile remains `test,demo,refbackfill`; frontend uses the existing local Vite configuration. Confirm all Phase 4 automatic/live flags remain false.

- [ ] **Step 5: Browser-smoke the tracker at 375 px and desktop width**

Verify visually and through DOM inspection:

- readiness and ETA bottlenecks differ when data says P3/P4;
- labels change automatically after API fixture changes;
- ties and unresolved pillars are textual;
- countdown and Monte Carlo source metadata are visible;
- non-aligned runs show a warning;
- backtest skill versus persistence is not presented as a pass badge;
- evidence counts are visible;
- no horizontal document overflow at 375 px.

- [ ] **Step 6: Commit only any final in-scope correction and report results**

If no correction was needed, do not create an empty commit. Record exact test counts, build result, server URLs, remaining model limitations, and `LIVE_MODEL=false` in the handoff.
