# Tracker WP3.1 궤도 체류 인일 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검수된 전 세계 궤도 인구 변화 CSV에서 완료 연도의 체류 인일과 최대 동시 인원을 계산·적재하고 Tracker Layer B 패널에 정직하게 표시한다.

**Architecture:** 기존 V11 Layer B 원장을 재사용하고 별도 Flyway 변경 없이 검수 CSV→엄격 검증→순수 구간 적분→해시 잠금 import 경로를 추가한다. 생성된 두 지표는 Pillar 2 `MEASURED` 관측으로만 저장하며 이벤트·노드·readiness·ETA에는 연결하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, H2 Oracle mode/Oracle ATP, React 19, TypeScript, Vitest.

## Global Constraints

- 설계 원본: `docs/superpowers/specs/2026-07-16-tracker-wp31-human-presence-design.md`.
- 전 세계 궤도 체류만 포함하고 준궤도는 제외한다.
- 완료 연도만 공개하며 진행 중 연도 변화점은 집계하지 않는다.
- 런타임 네트워크·신규 egress·secret·workload·Kubernetes CronJob을 추가하지 않는다.
- LIVE_MODEL, LL2 Layer C, 공식 인덱스, Metaculus live polling은 활성화하지 않는다.
- 35노드, r2.0, event, readiness, snapshot, ETA 코드를 변경하지 않는다.
- `.claude/`, demo/refbackfill fixture, 임시 Vite config를 stage하지 않는다.
- 모든 프로덕션 동작은 실패 테스트→실패 이유 확인→최소 구현→통과 순서로 만든다.

---

### Task 1: 검수 CSV 계약과 결정론적 연간 집계

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/HumanPresenceTransition.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/HumanPresenceDataset.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/HumanPresenceYear.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/HumanPresenceCsvValidator.java`
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/HumanPresenceAggregator.java`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/human-presence-transitions-v1.csv`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/HumanPresenceCsvValidatorTest.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/HumanPresenceAggregatorTest.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/HumanPresenceProductionDatasetTest.java`

**Interfaces:**
- `HumanPresenceCsvValidator.validate(byte[]) -> ValidatedDataset(HumanPresenceDataset dataset, List<String> errors)`.
- `HumanPresenceAggregator.aggregate(HumanPresenceDataset) -> List<HumanPresenceYear>`.
- `HumanPresenceYear(int year, BigDecimal personDays, int maxOrbitPopulation)`.

- [x] **Step 1: Write failing validator tests**

Add tests for a canonical two-year dataset and separate failures for unknown/missing metadata,
unsafe source URL, malformed header, duplicate or descending timestamps, population outside
`0..50`, missing Jan-1 boundary, and a completion cutoff that has no matching row. Add one
test proving rows after `complete_through_utc` validate but are excluded from output.

Canonical test input:

```text
# dataset_version=human-presence-v9
# source_label=Reviewed orbital population history
# source_url=https://planet4589.org/space/astro/web/pop.html
# accessed_on=2026-07-16
# complete_through_utc=2025-01-01T00:00:00Z
timestamp_utc,orbit_population
2024-01-01T00:00:00Z,2
2024-07-01T00:00:00Z,4
2025-01-01T00:00:00Z,3
2025-02-01T00:00:00Z,5
```

- [x] **Step 2: Run the validator test and confirm RED**

```powershell
& $mvn -o "-Dmaven.repo.local=$repo" -Dtest=HumanPresenceCsvValidatorTest test
```

Expected: missing `HumanPresenceCsvValidator`/domain types compilation failure.

- [x] **Step 3: Implement the strict parser and domain records**

Use a dependency-free, comma-free canonical parser. Decode UTF-8 strictly, reject files over
256 KiB and more than 5000 transitions, parse exact metadata/header, require UTC `Z`, strictly
increasing timestamps, explicit annual boundaries through the completion cutoff, and return no
dataset when any error exists. Permit later draft rows but preserve the explicit cutoff.

- [x] **Step 4: Re-run validator tests and confirm GREEN**

Expected: all validator cases pass with no warning output from product code.

- [x] **Step 5: Write failing aggregation tests**

Cover a leap year split, interval clipping at Jan 1, persisted population between transitions,
maximum population, four-decimal `HALF_UP`, and exclusion after the cutoff. The canonical leap-year
test above must produce `1100.0000` person-days and maximum `4`.

- [x] **Step 6: Run aggregation tests and confirm RED**

Expected: missing `HumanPresenceAggregator` failure.

- [x] **Step 7: Implement pure interval aggregation**

Iterate adjacent transitions, clip each interval to each closed calendar year, sum exact seconds
as `BigDecimal(population * seconds) / 86400`, round only the final annual result, and calculate
the maximum from non-empty clipped intervals. Return years in ascending order.

- [x] **Step 8: Add the reviewed production CSV and production contract test**

The CSV contains only the 2024-01-01 through 2026-01-01 UTC boundaries and reviewed
`orbit_population` changes from the approved source. The test must validate it without fixing an
exact transition count and assert exactly these completed-year results:

```text
2024 -> 4241.8711 person-days, max 19
2025 -> 3922.2028 person-days, max 14
```

- [x] **Step 9: Run all Task 1 tests**

```powershell
& $mvn -o "-Dmaven.repo.local=$repo" -Dtest=HumanPresenceCsvValidatorTest,HumanPresenceAggregatorTest,HumanPresenceProductionDatasetTest test
```

Expected: all Task 1 tests pass.

- [x] **Step 10: Commit Task 1**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb apps/backend/springboot-app/src/main/resources/tracker/human-presence-transitions-v1.csv apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb docs/superpowers/specs/2026-07-16-tracker-wp31-human-presence-design.md docs/superpowers/plans/2026-07-16-tracker-wp31-human-presence-plan.md
git commit -m "feat(tracker): calculate reviewed orbital person-days"
```

### Task 2: 해시 잠금 import와 Layer B API

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/HumanPresenceLoader.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/HumanPresenceLoaderTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidator.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidatorTest.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerController.java`
- Modify: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/LayerBApiTest.java`
- Modify: `gitops/apps/backend-springboot/deployment.yaml`

**Interfaces:**
- `HumanPresenceLoader.loadIfNeeded()` imports four generated metrics from the production v1
  resource under `human-presence-v1`.
- Existing `GET /api/tracker/layer-b` adds `accessedOn` and returns the two Pillar 2 metrics.

- [x] **Step 1: Write failing loader tests**

Test first import (four metrics, one import audit), same-version/same-hash no-op,
same-version/different-hash failure, invalid CSV all-or-nothing rollback, and pre-existing
conflicting natural-key failure before any new metric is written. Assert no event, history,
snapshot, or node level row changes.

- [x] **Step 2: Run loader tests and confirm RED**

```powershell
& $mvn -o "-Dmaven.repo.local=$repo" -Dtest=HumanPresenceLoaderTest test
```

Expected: missing loader compilation failure.

- [x] **Step 3: Implement generated metrics and transactional loader**

Map each `HumanPresenceYear` to:

```text
ANNUAL_ORBITAL_HUMAN_PERSON_DAYS | pillar 2 | PERSON_DAYS | MEASURED
MAX_SIMULTANEOUS_HUMANS_IN_ORBIT | pillar 2 | PEOPLE      | MEASURED
```

Use Dec 31 as `observed_on`, the CSV metadata as source provenance, and the raw CSV SHA-256 as
`content_sha256`. Validate every natural-key collision against all value/provenance fields before
calling existing upserts. Run transactionally under lock `tracker-human-presence-import` and
record generated metric count in `layer_b_metric_import`.

- [x] **Step 4: Wire the boot import without enabling a live path**

Add an ApplicationRunner guarded by `tracker.human-presence-on-boot` with default true and the
same `!test | demo` profile expression as other local resources. Add defaults for resource and
dataset version. Set `TRACKER_HUMAN_PRESENCE_ON_BOOT=true` explicitly in GitOps; tracker itself
remains false and there is no egress or secret.

- [x] **Step 5: Extend the Layer B metric registry and API test**

Add the two codes to `LayerBDatasetValidator`'s allowlist. Extend `LayerBApiTest` to load the
human resource manually and assert Pillar 2, values, units, basis, source URL, and `accessedOn`.
The controller change is additive only.

- [x] **Step 6: Run Task 2 focused tests**

```powershell
& $mvn -o "-Dmaven.repo.local=$repo" -Dtest=HumanPresenceLoaderTest,LayerBDatasetValidatorTest,LayerBApiTest,TrackerConfigTest test
```

Expected: all Task 2 tests pass.

- [x] **Step 7: Commit Task 2**

```powershell
git add -- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/HumanPresenceLoader.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidator.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/config/TrackerConfig.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerController.java apps/backend/springboot-app/src/main/resources/application.yml apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker gitops/apps/backend-springboot/deployment.yaml
git commit -m "feat(tracker): import orbital human presence metrics"
```

### Task 3: 사람이 읽는 Layer B 표시

**Files:**
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/LayerBPanel.tsx`
- Modify: `apps/frontend/react-app/src/tracker/LayerBPanel.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- `LayerBMetric` adds `accessedOn: string`.
- `LayerBPanel` localizes approved metric names/units and preserves person-day precision.

- [x] **Step 1: Write failing component tests**

Add a Pillar 2 fixture and assert `연간 궤도 인류 체류`, `4,241.8711 인일`,
`연중 최대 동시 궤도 인원`, `19 명`, observed/accessed dates, safe source link, and the exact
copy `전 세계 궤도 기준 · 준궤도 제외 · 자동 점수 효과 없음`.

- [x] **Step 2: Run focused Vitest and confirm RED**

```powershell
npm test -- --run src/tracker/LayerBPanel.test.tsx
```

Expected: raw metric code/default three-decimal formatting causes assertion failures.

- [x] **Step 3: Implement localized labels, units, provenance, and styling**

Keep the exact code visible as secondary metadata, format `PERSON_DAYS` with exactly four
fraction digits, all other counts without invented precision, render the source as
`target="_blank" rel="noreferrer"`, and retain the existing basis distinction.

- [x] **Step 4: Run focused and full frontend verification**

```powershell
npm test -- --run src/tracker/LayerBPanel.test.tsx src/tracker/TrackerPage.test.tsx
npm test -- --run
npm run build
```

Expected: focused/full tests and production build pass.

- [x] **Step 5: Commit Task 3**

```powershell
git add -- apps/frontend/react-app/src/tracker/api.ts apps/frontend/react-app/src/tracker/LayerBPanel.tsx apps/frontend/react-app/src/tracker/LayerBPanel.test.tsx apps/frontend/react-app/src/App.css
git commit -m "feat(tracker): show orbital human presence metrics"
```

### Task 4: Phase 3 통합 검증과 G3 마감

**Files:**
- Modify: `docs/plans/multiplanetary-tracker-execution-plan.md`
- Modify: `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md`
- Create: `docs/research/tracker-wp31-human-presence-validation-evidence.md`
- Modify: `docs/research/tracker-wp34-validation-evidence.md`
- Modify: `docs/research/tracker-wp35-validation-evidence.md`
- Modify: `docs/research/tracker-phase3-g3-coherence-report-2026-q2.md`

- [x] **Step 1: Run focused and full backend verification**

Run WP3.1/3.4/3.5 focused tests, then the complete offline Maven suite. Record exact totals,
failures, errors, skips, and any environment warning.

- [x] **Step 2: Run exact frontend toolchain verification**

Install/use lockfile versions without editing the lockfile, then run full Vitest, TypeScript, and
Vite production build. If dependency retrieval is unavailable, report it as an open gate rather
than substituting stale-toolchain evidence.

- [x] **Step 3: Verify GitOps and static safety invariants**

Run `tracker-egress-policy.ps1`, `git diff --check`, secret scan, live-flag scan, workload scan,
and protected-file staging audit. Attempt kustomize once with the approved platform command; if
the known Windows symlink ACL failure repeats, record the exact environment limitation without
circumvention.

- [x] **Step 4: Run actual Spring backend and browser smoke**

Start the `test,demo,refbackfill` backend with human-presence import enabled and Vite. Verify
`/api/tracker/layer-b`, the Tracker tab, both Pillar 2 rows, the 4-column forecast panel, governance
API, responsive Layer B/forecast sections, and browser console error count zero. Distinguish this
from any mock-backed screen evidence.

- [x] **Step 5: Update evidence and gate status truthfully**

Mark WP3.1 complete except the explicitly deferred LIVE_MODEL Layer C promotion. Mark WP3.4,
WP3.5, and G3 complete only if their required backend/API/browser checks pass; otherwise retain
the exact open gate. Record that user approval does not replace test evidence.

- [ ] **Step 6: Commit docs, push, and update PR 40**

Stage only planned Phase 3 files, excluding all protected fixtures. Push `feat/tracker-mvp` and
update existing PR 40. LIVE_MODEL and all live polling flags remain false.

## Self-Review

- Spec coverage: global orbital scope, suborbital exclusion, completed-year cutoff, deterministic
  integration, provenance, idempotency, API/UI, and non-scoring boundary each map to a task.
- Placeholder scan: no implementation placeholder or unfixed row-count contract is used.
- Type consistency: validator→dataset→aggregator→year→loader signatures and metric codes are
  identical across tasks.
- Migration check: V11 already supports both metrics; no schema change is justified.
- Strictness check: integrity failures are fail-closed, while valid partial monthly data after the
  completion boundary is retained as draft instead of being rejected.
