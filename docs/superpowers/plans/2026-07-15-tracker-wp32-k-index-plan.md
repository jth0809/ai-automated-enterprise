# Tracker WP3.2 K-지수 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검수된 연례 세계 에너지 CSV를 K-지수로 계산·저장·조회하고 Tracker 탭에 정직한 Layer A 게이지로 표시한다.

**Architecture:** 기존 V1 `k_index`를 현재 시계열 저장소로 확장하고 별도 import 감사 원장을 추가한다. CSV 검증, 수학 계산, 저장, API, UI를 분리하며 어떠한 readiness·ETA·경보도 변경하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway/H2 Oracle mode/Oracle ATP, React 19, TypeScript, Vitest.

## Global Constraints

- 설계 원본: `docs/superpowers/specs/2026-07-15-tracker-wp32-k-index-design.md`.
- 런타임 외부 API·신규 egress·secret·pod·Kubernetes CronJob을 추가하지 않는다.
- 정확한 60행·1965 시작·연속 연도를 런타임 검증에 고정하지 않는다.
- K-지수는 P4 readiness, ETA, snapshot, coherence alert를 변경하지 않는다.
- 보호된 `.claude/`, demo/refbackfill test fixture, 임시 Vite config를 stage하지 않는다.
- 모든 구현은 실패 테스트 → 최소 구현 → 통과 테스트 순서로 진행한다.

---

### Task 1: K-지수 저장·계산 기반

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V13__tracker_k_index.sql`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexObservation.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexCalculator.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/kindex/KIndexCalculatorTest.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java`

**Interfaces:**
- Produces: `KIndexCalculator.calculate(BigDecimal)` → `Calculation(long powerWatts, BigDecimal kValue, BigDecimal typeOneGap, BigDecimal typeOneMultiplier)`.
- Produces: immutable `KIndexObservation` used by repository and API.

- [ ] **Step 1: Write failing calculator and schema tests**

Assert that `176737.1 TWh` produces `20175468036530 W`, `0.7305`, `0.2695`, and `495.7`; zero and negative inputs must throw. Assert V13 columns and `k_index_import` exist.

- [ ] **Step 2: Run tests and confirm failure**

```powershell
& $mvn -o "-Dmaven.repo.local=$repo" -Dtest=KIndexCalculatorTest,TrackerSchemaTest test
```

Expected: missing K-index classes/V13 assertions fail.

- [ ] **Step 3: Implement migration, record, and calculator**

Use `BigDecimal` for input/output rounding and `Math.log10` only for the logarithm. Round watts to integer, K/gap to four decimals, multiplier to one decimal, all `HALF_UP`.

- [ ] **Step 4: Re-run focused tests**

Expected: all Task 1 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/backend/springboot-app/src/main/resources/db/migration/V13__tracker_k_index.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/kindex apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerSchemaTest.java
git commit -m "feat(tracker): add K-index persistence and calculation"
```

### Task 2: 검수 CSV와 유연한 검증기

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/tracker/k-index-energy-v1.csv`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexCsvValidator.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/kindex/KIndexCsvValidatorTest.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/kindex/KIndexProductionDatasetTest.java`

**Interfaces:**
- Produces: `KIndexCsvValidator.validate(String, Clock)` → `ValidatedDataset(List<RawObservation>, DatasetMetadata, List<String> errors)`.
- Consumes: exact CSV header from the design; no third-party CSV dependency.

- [ ] **Step 1: Write failing validator tests**

Cover valid 10-row and 12-row datasets, unsorted rows, year gaps, duplicate years, fewer than 10, more than 200, mixed basis/metadata, nonpositive/nonfinite values, unsafe URL, future access date, current/future observation year, malformed header, and quoted/comma-bearing fields rejection with an explicit message.

- [ ] **Step 2: Confirm failure**

Run `KIndexCsvValidatorTest`; expected missing class failure.

- [ ] **Step 3: Implement validator and production CSV**

Use the reviewed `World` rows for 1965–2024. The parser deliberately accepts only the six-column comma-free canonical export generated for this repository; source names use semicolons. Sort successful observations by year.

- [ ] **Step 4: Verify dataset without exact-count pinning**

Production test asserts at least 10 observations, latest year 2024, basis `SUBSTITUTION`, and computed latest K `0.7305`; it must not assert `60`.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/backend/springboot-app/src/main/resources/tracker/k-index-energy-v1.csv apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexCsvValidator.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/kindex
git commit -m "feat(tracker): validate reviewed K-index energy data"
```

### Task 3: 멱등 import와 조회 저장소

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexRepository.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/KIndexLoader.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/KIndexLoaderTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`

**Interfaces:**
- Produces: `findAll()`, `findImportSha(version)`, `upsert(observation)`, `recordImport(...)`.
- Produces: `KIndexLoader.loadIfNeeded()` with same-version hash lock.

- [ ] **Step 1: Write failing loader integration tests**

Cover first import, same-hash no-op, same-version changed-hash failure, new-version historical correction/upsert, invalid CSV rollback, and tracker-disabled context compatibility.

- [ ] **Step 2: Confirm failure**

Run `KIndexLoaderTest`; expected missing beans/classes.

- [ ] **Step 3: Implement repository, loader, and boot runner**

Loader computes SHA-256 over raw bytes, validates before writes, calculates derived fields server-side, and executes transactionally under `tracker-k-index-import` ShedLock. Defaults are resource `tracker/k-index-energy-v1.csv`, version `k-index-energy-v1`, boot import enabled.

- [ ] **Step 4: Re-run loader and context tests**

Expected: focused tests pass with tracker enabled and disabled.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/kindex/KIndexRepository.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/KIndexLoader.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java apps/backend/springboot-app/src/main/resources/application.yml apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/KIndexLoaderTest.java
git commit -m "feat(tracker): import K-index observations idempotently"
```

### Task 4: 공개 K-지수 API

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/KIndexController.java`
- Create: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/KIndexControllerTest.java`

**Interfaces:**
- Produces: `GET /api/tracker/k-index` response defined in the design.

- [ ] **Step 1: Write failing API tests**

Cover `CURRENT`, `STALE`, and `INSUFFICIENT_DATA`; assert at most 80 series points and no readiness/ETA fields.

- [ ] **Step 2: Confirm failure**

Run `KIndexControllerTest`; expected 404 or missing controller.

- [ ] **Step 3: Implement read-only controller**

Use `Clock.systemUTC()` in production and injectable clock in tests. Annual delta uses the two latest observations. Empty data returns HTTP 200 with null scalar fields.

- [ ] **Step 4: Re-run focused API tests**

Expected: all API tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/KIndexController.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/KIndexControllerTest.java
git commit -m "feat(tracker): expose K-index gauge API"
```

### Task 5: Tracker K-지수 카드

**Files:**
- Create: `apps/frontend/react-app/src/tracker/KIndexCard.tsx`
- Create: `apps/frontend/react-app/src/tracker/KIndexCard.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- Consumes: `getKIndex(): Promise<KIndexSummary>`.
- Produces: accessible `region` labelled `카르다쇼프 K-지수`.

- [ ] **Step 1: Write failing component/page tests**

Assert four-decimal K, Type I gap/multiplier, basis, source link, honesty copy, stale warning, insufficient state, and no arrival-year text. Assert TrackerPage fetches and renders the card.

- [ ] **Step 2: Confirm failure**

```powershell
npm test -- --run src/tracker/KIndexCard.test.tsx src/tracker/TrackerPage.test.tsx
```

- [ ] **Step 3: Implement API contract, card, sparkline, and styling**

The SVG uses the last 20 points, `role="img"`, and a text alternative. It must not imply linear physical progress or display a Type I ETA.

- [ ] **Step 4: Run focused and full frontend verification**

```powershell
npm test -- --run
npm run build
```

Expected: all tests and production build pass.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/frontend/react-app/src/tracker apps/frontend/react-app/src/App.css
git commit -m "feat(tracker): show annual K-index gauge"
```

### Task 6: WP3.2 마감 검증과 계획 갱신

**Files:**
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md`
- Create: `docs/research/tracker-wp32-validation-evidence.md`

- [ ] **Step 1: Run full backend regression**

Run the full Maven test suite offline and record exact totals.

- [ ] **Step 2: Run full frontend and build again**

Record exact test/file counts and build artifact sizes.

- [ ] **Step 3: Run local backend/frontend and browser verification**

Verify Tracker tab, K card values, source link, honesty labels, stale/empty behavior through tests, and console error count 0.

- [ ] **Step 4: Update evidence and master plans**

Mark only WP3.2 complete. Keep G3 incomplete and identify WP3.5 as next.

- [ ] **Step 5: Commit**

```powershell
git add -- docs/plans/multiplanetary-tracker-execution-plan.md docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md docs/research/tracker-wp32-validation-evidence.md
git commit -m "docs(tracker): complete WP3.2 K-index gauge"
```
