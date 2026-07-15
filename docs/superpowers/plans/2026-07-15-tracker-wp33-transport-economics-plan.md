# WP3.3 Transport Economics and B-Pair Coherence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공표 발사가격과 Falcon 계열 누적 발사량으로 독립적인 수송 경제성 시나리오 ETA를 계산하고, Layer B 수송 지표와 Layer C 필라 1 추세의 분기 정합성을 비파괴 방식으로 공개한다.

**Architecture:** V12는 감사 가능한 숫자 입력·모델 결과·분기 정합 리포트를 별도 테이블에 저장하고 기존 `layer_b_metric`을 공개 지표 표면으로 재사용한다. 순수 Wright 계산기와 정합 계산기를 데이터 접근·스케줄러·HTTP 계약에서 분리하며, 정합 경보는 API 응답 구간만 확대하고 기존 snapshot·node·event 상태를 갱신하지 않는다. 초기 3–4개 유효 가격 관측은 `PROVISIONAL` 결과를 노출하고 낮은 적합도는 `WEAK_FIT` 경고로만 표현한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway V12, H2/Oracle SQL, Jackson 2, ShedLock, JUnit 5, React 19, TypeScript, Vite, Vitest, Kubernetes GitOps/CiliumNetworkPolicy.

## Global Constraints

- 중앙 목표는 constant 2025 USD 기준 `$200/kg`, 민감도는 쉬운 목표 `$500/kg`와 어려운 목표 `$100/kg`이다.
- 가격은 `published service price / matching maximum LEO payload`인 낙관적 하한 proxy이며 내부 원가·거래가격·일반 임무 실현가격으로 표현하지 않는다.
- Falcon 9/Falcon Heavy 운용 공표가만 fit 입력으로 허용한다. Starship 목표, 사전 운용 추정, 임무별 계약 총액은 제외한다.
- 3–4개 유효 관측은 `PROVISIONAL`, 5개 이상은 `ESTABLISHED`; `R² < 0.50`은 `WEAK_FIT`를 추가하지만 유한 ETA를 제거하지 않는다.
- 모든 ETA 경계는 현재 시점부터 150년을 독립적으로 검사하며, 초과 경계는 숫자를 clamp하지 않고 `null`과 `beyondHorizon=true`로 반환한다.
- 동일 극성의 B↔C 불일치가 두 연속 UTC 분기에 확인될 때만 `DIVERGENT`, 최대 10개 결정적 감사 표본, 1.5배 API 구간 확대를 활성화한다.
- 정합 코드는 `pillar_snapshot`, `capability_node`, `event`, `node_state_history`를 수정하지 않는다.
- LIVE_MODEL은 `NOT_ACTIVATED`로 유지하며 LLM 호출·새 secret·새 egress host·새 pod·새 Kubernetes CronJob을 추가하지 않는다.
- 런타임과 GitOps 기본값은 `TRACKER_TRANSPORT_ECONOMICS_ENABLED=false`; 두 작업은 기존 backend의 조건부 `@Scheduled` + ShedLock만 사용한다.
- 외부 원문·인용·HTML·PDF·이미지·바이너리를 저장하지 않는다. URL, locator, access date, SHA-256, 숫자와 검수자 작성 사실 요약만 저장한다.
- 보호된 로컬 fixture `.claude/`, `application-demo.yml`, `application-refbackfill.yml`, `tracker/backfill-demo.json`은 수정·스테이징하지 않는다.
- 각 Task는 Red → Green → focused regression → `git diff --check` → 해당 파일만 명시적 staging → commit 순서로 끝낸다.

---

## 1. 구현 전 파일·책임 지도

### Backend 신규 파일

| 파일 | 단일 책임 |
|---|---|
| `apps/backend/springboot-app/src/main/resources/db/migration/V12__tracker_transport_economics.sql` | 가정·가격 관측·projection·정합 리포트·감사 표본·import ledger 제약 |
| `.../tracker/transport/TransportAssumption.java` | versioned 모델 가정 값 |
| `.../tracker/transport/TransportPriceObservation.java` | 계산된 constant-dollar 가격 관측과 provenance |
| `.../tracker/transport/AnnualLaunchCount.java` | 완결 연도 Falcon 계열 발사 수 |
| `.../tracker/transport/TransportProjection.java` | 저장·API에 공통인 Wright 결과 |
| `.../tracker/transport/TransportCoherenceReport.java` | 한 UTC 분기의 B↔C 정합 결과 |
| `.../tracker/transport/TransportCoherenceSample.java` | 변경 권한이 없는 event 감사 표본 |
| `.../tracker/transport/TransportEconomicsRepository.java` | V12 및 관련 Layer B 읽기/쓰기 |
| `.../tracker/transport/TransportEconomicsDatasetValidator.java` | strict JSON allowlist, 산술·출처·불변식 검증 |
| `.../tracker/transport/WrightProjectionCalculator.java` | log-log OLS와 세 cadence 시나리오 순수 계산 |
| `.../tracker/transport/TransportProjectionService.java` | 입력 조회, runtime 가정 일치 확인, 결과 저장 |
| `.../tracker/transport/TransportCoherenceCalculator.java` | 방향·streak·정합 상태 순수 계산 |
| `.../tracker/transport/TransportCoherenceService.java` | 분기 리포트·결정적 표본 저장 |
| `.../tracker/transport/TransportEtaOverlay.java` | 필라 1 API bounds의 비파괴 1.5배 확대 |
| `.../tracker/ingest/TransportEconomicsLoader.java` | immutable resource 멱등 import 및 Layer B mirror |
| `.../tracker/ingest/TransportProjectionJob.java` | 월간 projection schedule |
| `.../tracker/ingest/TransportCoherenceJob.java` | 직전 완결 분기 coherence schedule |
| `.../tracker/api/TransportEconomicsController.java` | 수송 economics·public coherence GET 계약 |
| `.../tracker/api/TransportCoherenceAdminController.java` | token 보호 report/sample 검수 계약 |
| `apps/backend/springboot-app/src/main/resources/tracker/transport-economics-v1.json` | 검수된 immutable 숫자·provenance corpus |
| `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport/TransportTestFixtures.java` | backend transport 테스트의 완전한 고정 record builders와 read-only table fingerprint |

`...`는 `apps/backend/springboot-app/src/main/java/com/aienterprise/backend`를 뜻한다. 실제 staging 명령에는 전체 경로를 사용한다.

### Backend 수정 파일

| 파일 | 변경 |
|---|---|
| `tracker/domain/TrackerRepository.java` | event 감사 표본용 read-only ID 조회만 추가 |
| `tracker/layerb/LayerBDatasetValidator.java` | 두 canonical transport metric code 허용 |
| `tracker/layerb/LaunchRecord.java` | LL2 rocket configuration 이름 보존 |
| `tracker/layerb/LaunchLibraryParser.java` | `rocket.configuration.full_name` 파싱 |
| `tracker/layerb/LaunchCadenceAggregator.java` | 완결 Falcon 9/Heavy 계열 연간 count mirror 생성 |
| `tracker/api/TrackerController.java` | pillar base/display bounds와 coherence overlay 추가 |
| `src/main/resources/application.yml` | 조건부 jobs, schedules, 세 목표의 safe defaults |

### Frontend 신규·수정 파일

| 파일 | 변경 |
|---|---|
| `apps/frontend/react-app/src/tracker/TransportEconomicsCard.tsx` | WP3.3 compact honesty card |
| `apps/frontend/react-app/src/tracker/TransportEconomicsCard.test.tsx` | provisional/weak/non-declining/horizon/alert 렌더 |
| `apps/frontend/react-app/src/tracker/api.ts` | projection/coherence/bounds 타입과 GET 함수 |
| `apps/frontend/react-app/src/tracker/TrackerPage.tsx` | economics 병렬 fetch 및 card 배치 |
| `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx` | 새 fetch 실패·성공 계약 |
| `apps/frontend/react-app/src/App.css` | compact card와 warning 스타일 |

### GitOps·문서 수정 파일

| 파일 | 변경 |
|---|---|
| `gitops/apps/backend-springboot/deployment.yaml` | transport flag false, cron, 세 target env |
| `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1` | safe defaults·no secret·no new host 검증 |
| `docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md` | WP3.3 계획 링크·진행 증거 |
| `docs/plans/multiplanetary-tracker-execution-plan.md` | WP3.3 상태와 검증 수치만 요약 |
| `docs/research/tracker-wp33-source-evidence.md` | 초기 숫자별 공식 source locator·검수 기록 |

## 2. 고정 인터페이스

아래 이름과 타입은 Task 간 계약이며 구현 중 임의 변경하지 않는다.

```java
public record TransportAssumption(
        String version, String modelVersion,
        BigDecimal centralTargetUsdPerKg,
        BigDecimal easyTargetUsdPerKg,
        BigDecimal hardTargetUsdPerKg,
        int priceBasisYear, int horizonYears,
        BigDecimal weakFitR2, BigDecimal wideningFactor) {}

public record AnnualLaunchCount(int year, long launches) {}

public record TransportPriceObservation(
        long id, int observationYear, String vehicleFamily, String vehicleVariant,
        BigDecimal publishedPriceUsd, BigDecimal maxLeoPayloadKg,
        BigDecimal nominalUsdPerKg, BigDecimal cpiObservationValue,
        BigDecimal cpiBasisValue, BigDecimal realBasisUsdPerKg,
        long cumulativeFamilyLaunches,
        String sourceLabel, String sourceUrl, String sourceLocator,
        LocalDate accessedOn, String contentSha256, String factSummary) {}
```

```java
public record TransportProjection(
        long id, LocalDate asOfDate, String assumptionVersion, String modelVersion,
        String status, String sufficiencyTier, List<String> qualificationFlags,
        int observationCount, Double alpha, Double beta, Double rSquared,
        long currentCumulativeLaunches,
        Double centralCadence, Double fastCadence, Double slowCadence,
        BigDecimal centralTargetUsdPerKg, BigDecimal easyTargetUsdPerKg,
        BigDecimal hardTargetUsdPerKg,
        Double centralRequiredLaunches, Double easyRequiredLaunches,
        Double hardRequiredLaunches,
        Double centralEtaYear, Double earliestEtaYear, Double latestEtaYear,
        boolean centralBeyondHorizon, boolean earliestBeyondHorizon,
        boolean latestBeyondHorizon,
        int priceBasisYear, int horizonYears, String intervalKind,
        String basis, String priceMeaning, String projectionLabel,
        String reasonCode) {}
```

```java
public record TransportCoherenceReport(
        long id, LocalDate reportPeriodEnd, LocalDate layerCSnapshotDate,
        String priceDirection, String cadenceDirection, String layerBDirection,
        String layerCDirection, String state, String polarity,
        int consecutiveQuarterStreak, boolean alertActive,
        BigDecimal wideningFactor, LocalDate firstDivergentPeriod) {}

public record TransportCoherenceSample(
        long id, long reportId, long eventId, String status,
        String reviewerNote, Instant reviewedAt) {}
```

Repository와 service 계약:

```java
public final class WrightProjectionCalculator {
    public TransportProjection calculate(
            LocalDate asOfDate,
            TransportAssumption assumption,
            List<TransportPriceObservation> observations,
            List<AnnualLaunchCount> annualCounts);
}

public final class TransportCoherenceCalculator {
    public TransportCoherenceReport calculate(
            LocalDate reportPeriodEnd,
            TransportProjection projection,
            List<AnnualLaunchCount> annualCounts,
            SnapshotRow layerCSnapshot,
            TransportCoherenceReport previous);
}

public record EtaBounds(
        Double baseEtaLow, Double baseEtaHigh,
        Double etaLow, Double etaHigh,
        boolean coherenceAdjusted, LocalDate coherenceReportPeriod) {}
```

---

### Task 1: V12 schema, transport records, and focused repository

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V12__tracker_transport_economics.sql`
- Create: six record files and `TransportEconomicsRepository.java` under `.../tracker/transport/`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport/TransportEconomicsRepositoryTest.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerTransportV12SchemaTest.java`
- Test helper: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport/TransportTestFixtures.java`

**Interfaces:**
- Consumes: existing `JdbcClient`, `SnapshotRow`, `layer_b_metric`, `event`, `capability_node`.
- Produces: section 2 records plus repository methods used by every later Task.

- [ ] **Step 1: Write migration and repository contract tests first**

The repository test must prove same-hash import idempotence support, observation natural-key stability, projection round trip, latest report lookup, and one-way sample review:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportEconomicsRepositoryTest {
    @Autowired TransportEconomicsRepository repository;
    @Autowired JdbcClient jdbc;

    @Test
    void roundTripsAssumptionObservationProjectionAndReport() {
        TransportAssumption assumption = TransportTestFixtures.assumption();
        repository.insertAssumption(assumption);
        repository.insertObservation(TransportTestFixtures.observation(2018, 68));
        repository.saveProjection(TransportTestFixtures.projection(LocalDate.of(2026, 7, 15)));
        repository.saveCoherenceReport(
                TransportTestFixtures.watchReport(LocalDate.of(2026, 6, 30)));

        assertEquals(assumption, repository.findAssumption(assumption.version()).orElseThrow());
        assertEquals(1, repository.findPriceObservations().size());
        assertEquals("PROVISIONAL", repository.findLatestProjection().orElseThrow().status());
        assertEquals("WATCH", repository.findLatestCoherenceReport().orElseThrow().state());
    }

    @Test
    void reviewIsOneWayAndDoesNotTouchTheEvent() {
        long reportId = repository.saveCoherenceReport(
                TransportTestFixtures.watchReport(LocalDate.of(2026, 6, 30)));
        long eventId = TransportTestFixtures.insertConfirmedPillarOneEvent(jdbc);
        long sampleId = repository.insertSample(reportId, eventId);
        String before = TransportTestFixtures.eventFingerprint(
                jdbc, eventId);
        assertTrue(repository.reviewSample(sampleId, "검수 완료"));
        assertFalse(repository.reviewSample(sampleId, "두 번째 결정"));
        assertEquals(before, TransportTestFixtures.eventFingerprint(
                jdbc, eventId));
    }
}
```

- [ ] **Step 2: Run the focused tests and confirm the Red state**

Run from `apps/backend/springboot-app`:

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TrackerTransportV12SchemaTest,TransportEconomicsRepositoryTest' test
```

Expected: compilation fails because the V12 records/repository do not exist, or schema assertions fail because V12 tables do not exist.

- [ ] **Step 3: Add the complete V12 invariant set**

The migration must create exactly these six tables and no trigger or scheduled database object:

```sql
CREATE TABLE transport_economics_assumption (
  assumption_version VARCHAR2(80) PRIMARY KEY,
  model_version VARCHAR2(80) NOT NULL,
  central_target_usd_per_kg NUMBER(14,4) NOT NULL CHECK (central_target_usd_per_kg > 0),
  easy_target_usd_per_kg NUMBER(14,4) NOT NULL CHECK (easy_target_usd_per_kg > central_target_usd_per_kg),
  hard_target_usd_per_kg NUMBER(14,4) NOT NULL CHECK (hard_target_usd_per_kg < central_target_usd_per_kg),
  price_basis_year NUMBER(4) NOT NULL,
  horizon_years NUMBER(3) NOT NULL CHECK (horizon_years BETWEEN 1 AND 150),
  weak_fit_r2 NUMBER(6,5) NOT NULL CHECK (weak_fit_r2 BETWEEN 0 AND 1),
  widening_factor NUMBER(5,2) NOT NULL CHECK (widening_factor >= 1),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE transport_price_observation (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  observation_year NUMBER(4) NOT NULL,
  vehicle_family VARCHAR2(40) NOT NULL CHECK (vehicle_family = 'FALCON'),
  vehicle_variant VARCHAR2(80) NOT NULL,
  published_price_usd NUMBER(18,2) NOT NULL CHECK (published_price_usd > 0),
  max_leo_payload_kg NUMBER(14,2) NOT NULL CHECK (max_leo_payload_kg > 0),
  nominal_usd_per_kg NUMBER(14,4) NOT NULL CHECK (nominal_usd_per_kg > 0),
  cpi_observation_value NUMBER(10,3) NOT NULL CHECK (cpi_observation_value > 0),
  cpi_basis_value NUMBER(10,3) NOT NULL CHECK (cpi_basis_value > 0),
  real_basis_usd_per_kg NUMBER(14,4) NOT NULL CHECK (real_basis_usd_per_kg > 0),
  cumulative_family_launches NUMBER(8) NOT NULL CHECK (cumulative_family_launches > 0),
  source_label VARCHAR2(200) NOT NULL,
  source_url VARCHAR2(600) NOT NULL,
  source_locator VARCHAR2(300) NOT NULL,
  accessed_on DATE NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  fact_summary VARCHAR2(500) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_price_observation UNIQUE
    (observation_year, vehicle_family, vehicle_variant)
);

CREATE TABLE transport_economics_projection (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  as_of_date DATE NOT NULL,
  assumption_version VARCHAR2(80) NOT NULL REFERENCES transport_economics_assumption(assumption_version),
  model_version VARCHAR2(80) NOT NULL,
  status VARCHAR2(30) NOT NULL CHECK (status IN
    ('INSUFFICIENT_DATA','PROVISIONAL','ESTABLISHED','NON_DECLINING','REACHED','BEYOND_HORIZON')),
  sufficiency_tier VARCHAR2(30) NOT NULL CHECK (sufficiency_tier IN
    ('INSUFFICIENT_DATA','PROVISIONAL','ESTABLISHED')),
  qualification_flags VARCHAR2(300) NOT NULL,
  observation_count NUMBER(4) NOT NULL CHECK (observation_count >= 0),
  alpha NUMBER(20,10), beta NUMBER(20,10), r_squared NUMBER(12,10),
  current_cumulative_launches NUMBER(8) NOT NULL CHECK (current_cumulative_launches >= 0),
  central_cadence NUMBER(12,4), fast_cadence NUMBER(12,4), slow_cadence NUMBER(12,4),
  central_target_usd_per_kg NUMBER(14,4) NOT NULL,
  easy_target_usd_per_kg NUMBER(14,4) NOT NULL,
  hard_target_usd_per_kg NUMBER(14,4) NOT NULL,
  central_required_launches NUMBER(20,4),
  easy_required_launches NUMBER(20,4),
  hard_required_launches NUMBER(20,4),
  central_eta_year NUMBER(7,1), earliest_eta_year NUMBER(7,1), latest_eta_year NUMBER(7,1),
  central_beyond_horizon CHAR(1) NOT NULL CHECK (central_beyond_horizon IN ('Y','N')),
  earliest_beyond_horizon CHAR(1) NOT NULL CHECK (earliest_beyond_horizon IN ('Y','N')),
  latest_beyond_horizon CHAR(1) NOT NULL CHECK (latest_beyond_horizon IN ('Y','N')),
  price_basis_year NUMBER(4) NOT NULL,
  horizon_years NUMBER(3) NOT NULL,
  interval_kind VARCHAR2(40) NOT NULL CHECK (interval_kind = 'ASSUMPTION_SENSITIVITY'),
  basis VARCHAR2(30) NOT NULL CHECK (basis = 'PUBLISHED_PRICE'),
  price_meaning VARCHAR2(120) NOT NULL,
  projection_label VARCHAR2(200) NOT NULL,
  reason_code VARCHAR2(80) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_projection UNIQUE (as_of_date, assumption_version)
);

CREATE TABLE transport_coherence_report (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  report_period_end DATE NOT NULL UNIQUE,
  layer_c_snapshot_date DATE,
  price_direction VARCHAR2(20) NOT NULL,
  cadence_direction VARCHAR2(20) NOT NULL,
  layer_b_direction VARCHAR2(20) NOT NULL,
  layer_c_direction VARCHAR2(20) NOT NULL,
  coherence_state VARCHAR2(30) NOT NULL CHECK (coherence_state IN
    ('INSUFFICIENT_DATA','COHERENT','WATCH','DIVERGENT','MIXED')),
  polarity VARCHAR2(20) NOT NULL CHECK (polarity IN ('NONE','B_AHEAD','C_AHEAD')),
  consecutive_quarter_streak NUMBER(3) NOT NULL CHECK (consecutive_quarter_streak >= 0),
  alert_active CHAR(1) NOT NULL CHECK (alert_active IN ('Y','N')),
  widening_factor NUMBER(5,2) NOT NULL CHECK (widening_factor >= 1),
  first_divergent_period DATE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE transport_coherence_sample (
  id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  report_id NUMBER NOT NULL REFERENCES transport_coherence_report(id),
  event_id NUMBER NOT NULL REFERENCES event(id),
  review_status VARCHAR2(20) DEFAULT 'PENDING' NOT NULL CHECK (review_status IN ('PENDING','REVIEWED')),
  reviewer_note VARCHAR2(2000),
  reviewed_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_transport_coherence_sample UNIQUE (report_id, event_id)
);

CREATE TABLE transport_economics_import (
  dataset_version VARCHAR2(80) PRIMARY KEY,
  dataset_sha256 CHAR(64) NOT NULL UNIQUE,
  price_observation_count NUMBER(5) NOT NULL CHECK (price_observation_count > 0),
  annual_launch_record_count NUMBER(5) NOT NULL CHECK (annual_launch_record_count >= 3),
  cpi_record_count NUMBER(5) NOT NULL CHECK (cpi_record_count >= 2),
  loaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

Add lowercase 64-hex checks through validator tests rather than Oracle-specific regex SQL, and add indexes on projection date, report period, sample report/status, and price year.

- [ ] **Step 4: Implement records and focused JdbcClient repository**

Required methods:

```java
Optional<TransportAssumption> findAssumption(String version);
void insertAssumption(TransportAssumption assumption);
void insertObservation(TransportPriceObservation observation);
List<TransportPriceObservation> findPriceObservations();
List<AnnualLaunchCount> findAnnualFalconLaunchCounts();
void saveProjection(TransportProjection projection);
Optional<TransportProjection> findLatestProjection();
long saveCoherenceReport(TransportCoherenceReport report);
Optional<TransportCoherenceReport> findLatestCoherenceReport();
Optional<TransportCoherenceReport> findPreviousCoherenceReport(LocalDate beforePeriodEnd);
long insertSample(long reportId, long eventId);
List<TransportCoherenceSample> findSamples(long reportId);
boolean reviewSample(long sampleId, String note);
Optional<String> findImportSha(String datasetVersion);
void recordImport(String version, String sha256, int priceCount, int launchCount, int cpiCount);
```

`reviewSample` SQL includes `WHERE id=:id AND review_status='PENDING'`; it returns `updateCount == 1` and never updates `event`.

`TransportTestFixtures` provides concrete constructors for all six records and the
following event fingerprint; it never writes production state:

```java
static String eventFingerprint(JdbcClient jdbc, long eventId) {
    return jdbc.sql("""
            SELECT natural_key || '|' || event_status || '|' || state_advanced || '|'
                   || COALESCE(CAST(impact_score AS VARCHAR), '')
              FROM event WHERE id = :id
            """).param("id", eventId).query(String.class).single();
}

static String tableFingerprint(JdbcClient jdbc, String table, String orderBy) {
    Set<String> allowed = Set.of(
            "pillar_snapshot|pillar, snapshot_date, id",
            "capability_node|id", "event|id");
    if (!allowed.contains(table + "|" + orderBy)) {
        throw new IllegalArgumentException("unsupported fingerprint table");
    }
    return jdbc.sql("SELECT * FROM " + table + " ORDER BY " + orderBy)
            .query((rs, rowNum) -> {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                    row.append(column).append('=').append(rs.getObject(column)).append('|');
                }
                return row.toString();
            }).list().toString();
}
```

`saveProjection` and `saveCoherenceReport` are idempotent for their unique natural
keys: an exact rerun returns the existing row; a rerun with different calculated
contents throws before replacing the stored evidence.

- [ ] **Step 5: Run focused and migration regression tests**

Run the Step 2 command. Expected: both test classes pass, V1–V12 migrate on H2, and the second review attempt returns false.

- [ ] **Step 6: Commit Task 1**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V12__tracker_transport_economics.sql apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerTransportV12SchemaTest.java
git commit -m "feat(tracker): add transport economics persistence"
```

### Task 2: Strict immutable input validator, reviewed corpus, and Layer B mirror

**Files:**
- Create: `.../tracker/transport/TransportEconomicsDatasetValidator.java`
- Create: `.../tracker/ingest/TransportEconomicsLoader.java`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/transport-economics-v1.json`
- Create: `docs/research/tracker-wp33-source-evidence.md`
- Modify: `.../tracker/layerb/LayerBDatasetValidator.java`
- Test: `.../tracker/transport/TransportEconomicsDatasetValidatorTest.java`
- Test: `.../tracker/ingest/TransportEconomicsLoaderTest.java`
- Test: `.../tracker/transport/TransportEconomicsProductionCorpusTest.java`

**Interfaces:**
- Consumes: Task 1 repository and records.
- Produces: immutable `transport-economics-v1`, assumption row, calculated observation rows, annual Layer B mirrors.

- [ ] **Step 1: Write failing strict-validation tests**

Use a valid in-memory dataset builder and mutate one invariant per test. Required cases are exact-key allowlists at every level, HTTPS, 64 lowercase hex, positive numerics, central/easy/hard ordering, 2025 basis CPI, unique `(observationYear, vehicleFamily, vehicleVariant)` keys, strictly positive annual counts, at least three complete years, Falcon-only operational rows, same configuration price/payload declaration, and prohibited content keys recursively. Multiple eligible variants in one year are retained for audit and reduced to the lowest real-price annual frontier for fitting.

```java
@Test
void keepsThreeValidObservationsAsProvisionalEligibleEvidence() {
    ValidatedTransportDataset result = validator.validateJson(validDataset(3));
    assertTrue(result.errors().isEmpty());
    assertEquals(3, result.observations().size());
}

@ParameterizedTest
@ValueSource(strings = {"quote", "body", "html", "pdf", "image", "attachment", "rawHtml"})
void rejectsStoredSourceContentAtAnyDepth(String key) {
    ValidatedTransportDataset result = validator.validateJson(withNestedField(validDataset(3), key));
    assertTrue(result.errors().stream().anyMatch(error -> error.contains("prohibited field")));
}

@Test
void recomputesRatherThanTrustingDerivedValues() {
    ValidatedTransportDataset result = validator.validateJson(validDatasetWithFakeDerivedPrice());
    assertTrue(result.errors().stream().anyMatch(error -> error.contains("derived field is not allowed")));
}

@Test
void choosesTheLowestEligibleRealPriceAsTheAnnualFrontier() {
    ValidatedTransportDataset result = validator.validateJson(
            validDatasetWithTwoVariantsIn2018());
    assertTrue(result.errors().isEmpty());
    assertEquals("FALCON_9_EXPENDABLE",
            result.annualFrontier().get(2018).vehicleVariant());
}
```

- [ ] **Step 2: Confirm validator tests fail**

Run:

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TransportEconomicsDatasetValidatorTest' test
```

Expected: compilation fails because validator types are absent.

- [ ] **Step 3: Implement canonical parse and arithmetic**

The only accepted top-level keys are:

```java
private static final Set<String> ROOT_KEYS = Set.of(
        "datasetVersion", "assumption", "cpi", "annualLaunchCounts", "priceObservations");
private static final Set<String> ASSUMPTION_KEYS = Set.of(
        "version", "modelVersion", "centralTargetUsdPerKg", "easyTargetUsdPerKg",
        "hardTargetUsdPerKg", "priceBasisYear", "horizonYears", "weakFitR2",
        "wideningFactor");
private static final Set<String> CPI_KEYS = Set.of(
        "year", "value", "seriesId", "sourceLabel", "sourceUrl", "sourceLocator",
        "accessedOn", "contentSha256", "factSummary");
private static final Set<String> COUNT_KEYS = Set.of(
        "year", "count", "sourceLabel", "sourceUrl", "sourceLocator", "accessedOn",
        "contentSha256", "factSummary");
private static final Set<String> PRICE_KEYS = Set.of(
        "observationYear", "vehicleFamily", "vehicleVariant", "operational",
        "configurationMatch", "publishedPriceUsd", "maxLeoPayloadKg", "sourceLabel",
        "sourceUrl", "sourceLocator", "accessedOn", "contentSha256", "factSummary");
```

For every price row, compute with `MathContext.DECIMAL64` and persist scale 4, `HALF_UP`:

```java
BigDecimal nominal = publishedPrice.divide(maxPayload, 8, RoundingMode.HALF_UP);
BigDecimal real = nominal.multiply(cpiBasis)
        .divide(cpiObservation, 4, RoundingMode.HALF_UP);
long cumulative = annualCounts.stream()
        .filter(row -> row.year() <= observationYear)
        .mapToLong(AnnualLaunchCount::launches)
        .sum();
```

Reject `operational=false` and `configurationMatch=false`; do not reject valid sparse evidence merely because there are fewer than five observations.

Build `annualFrontier` by grouping valid observations by `observationYear` and
selecting `realBasisUsdPerKg` ascending, then `vehicleVariant` ascending as the
deterministic tie-break. Persist every valid observation for audit, but mirror and
fit only one frontier observation per year.

- [ ] **Step 4: Build and review the initial production corpus**

Use only these primary-source facts for the first price frontier; each resource row stores a reviewer-written summary, not quoted source text:

| Observation year | Numeric fact | Official source and locator |
|---|---|---|
| 2016 | Falcon 9 FT expendable, `$61.2M`, `22,800 kg` | FAA 2017 Annual Compendium, Falcon 9 fact sheet |
| 2017 | Falcon 9 expendable, `$62M`, `22,800 kg` | FAA 2018 Annual Compendium, Falcon 9 fact sheet |
| 2018 | Falcon 9 advertised `$62M`, `22,800 kg` | NASA NTRS citation `20180007067`, abstract/data sentence |
| 2024 | Falcon 9 standard price `$69.75M`, max LEO `22,000 kg` | SpaceX `Capabilities & Services`, Falcon 9 price/capability panel and expendable-performance footnote |

CPI rows are `CUUR0000SA0` annual averages `2016=240.007`, `2017=245.120`, `2018=251.107`, `2024=313.689`, `2025=321.943` from the BLS annual table. Annual Falcon-family counts cover each complete year from 2010 through 2024 and are generated from LL2 completed `Falcon 9`/`Falcon Heavy` orbital records, counting Success, Failure, and Partial Failure only. Before committing, compare 2016–2018 totals against the FAA compendia and record any count-definition difference in `tracker-wp33-source-evidence.md`; the model uses the LL2 completed-orbital definition consistently.

Fingerprint each official URL with the existing copyright-safe helper and copy its returned lowercase SHA-256 into the numeric resource:

```powershell
& scripts/backfill/Get-SourceFingerprint.ps1 -Url 'https://www.faa.gov/about/office_org/headquarters_offices/ast/media/2017_ast_compendium.pdf'
& scripts/backfill/Get-SourceFingerprint.ps1 -Url 'https://www.faa.gov/sites/faa.gov/files/space/additional_information/2018_AST_Compendium.pdf'
& scripts/backfill/Get-SourceFingerprint.ps1 -Url 'https://ntrs.nasa.gov/citations/20180007067'
& scripts/backfill/Get-SourceFingerprint.ps1 -Url 'https://www.spacex.com/media/Capabilities%26Services.pdf'
& scripts/backfill/Get-SourceFingerprint.ps1 -Url 'https://www.bls.gov/regions/mid-atlantic/data/ConsumerPriceIndexAnnualandSemiAnnual_Table.htm'
```

If an official URL cannot be fingerprinted, omit that observation and let the model return `PROVISIONAL` or `INSUFFICIENT_DATA`; do not substitute a secondary mirror silently.

- [ ] **Step 5: Write loader tests, then implement transactional immutable import**

Tests must prove same version/same hash is no-op, same version/different hash throws before writes, any validation error writes zero rows, and valid input mirrors:

```text
LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025 | pillar 1 | USD_PER_KG | PUBLISHED_PRICE
ANNUAL_FALCON_FAMILY_LAUNCH_COUNT     | pillar 1 | LAUNCHES   | MEASURED
```

The loader transaction order is assumption → observations → annual Layer B rows → annual frontier Layer B rows → import ledger. Runtime resource version must equal `transport-economics-v1`.

- [ ] **Step 6: Run validator, loader, and production-corpus tests**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TransportEconomicsDatasetValidatorTest,TransportEconomicsLoaderTest,TransportEconomicsProductionCorpusTest,LayerBDatasetValidatorTest' test
```

Expected: all selected tests pass; production corpus has 3–4 valid frontier years, at least 15 annual count rows, five CPI rows, no prohibited key, and exact targets 200/500/100.

- [ ] **Step 7: Commit Task 2**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/TransportEconomicsDatasetValidator.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TransportEconomicsLoader.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidator.java apps/backend/springboot-app/src/main/resources/tracker/transport-economics-v1.json apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/TransportEconomicsLoaderTest.java docs/research/tracker-wp33-source-evidence.md
git commit -m "feat(tracker): import reviewed transport economics inputs"
```

### Task 3: Pure Wright projection with provisional partial evidence

**Files:**
- Create: `.../tracker/transport/WrightProjectionCalculator.java`
- Test: `.../tracker/transport/WrightProjectionCalculatorTest.java`

**Interfaces:**
- Consumes: Task 1 records and validated observations/counts.
- Produces: deterministic `TransportProjection calculate(...)`.

- [ ] **Step 1: Write parameterized Red tests for every status and edge**

Required tests:

```java
@Test void exactSyntheticPowerLawRecoversAlphaBetaAndRSquared();
@Test void threePointsProduceVisibleProvisionalEta();
@Test void weakFitAddsFlagButKeepsFiniteEta();
@Test void fivePointsProduceEstablishedTier();
@Test void nonNegativeBetaProducesNonDecliningWithoutEta();
@Test void alreadyMetCentralTargetProducesReachedYear();
@Test void eachSensitivityEdgeCrossesTheHorizonIndependently();
@Test void cadenceUsesFiveYearsCentrallyAndThreeWhenSparse();
@Test void zeroOrNonFiniteInputsFailClosedAsInsufficient();
```

The critical anti-overstrict assertion is:

```java
TransportProjection result = calculator.calculate(
        AS_OF, assumption(), noisyThreePointDecline(), annualCounts());
assertEquals("PROVISIONAL", result.status());
assertTrue(result.qualificationFlags().contains("WEAK_FIT"));
assertNotNull(result.centralEtaYear());
```

- [ ] **Step 2: Run and confirm Red**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=WrightProjectionCalculatorTest' test
```

Expected: class or method missing.

- [ ] **Step 3: Implement log-log OLS and scenario conversion**

Use population sums consistently:

```java
double meanX = points.stream().mapToDouble(Point::x).average().orElseThrow();
double meanY = points.stream().mapToDouble(Point::y).average().orElseThrow();
double sxx = points.stream().mapToDouble(p -> square(p.x() - meanX)).sum();
double sxy = points.stream().mapToDouble(p -> (p.x() - meanX) * (p.y() - meanY)).sum();
double beta = sxy / sxx;
double alpha = meanY - beta * meanX;
double sst = points.stream().mapToDouble(p -> square(p.y() - meanY)).sum();
double sse = points.stream().mapToDouble(p -> square(p.y() - (alpha + beta * p.x()))).sum();
double rSquared = sst == 0.0 ? 1.0 : 1.0 - sse / sst;
```

Before creating log points, reduce audited variants to the annual frontier:

```java
Map<Integer, TransportPriceObservation> byYear = new TreeMap<>();
for (TransportPriceObservation observation : observations) {
    byYear.merge(observation.observationYear(), observation,
            (left, right) -> Comparator
                    .comparing(TransportPriceObservation::realBasisUsdPerKg)
                    .thenComparing(TransportPriceObservation::vehicleVariant)
                    .compare(left, right) <= 0 ? left : right);
}
List<TransportPriceObservation> frontier = List.copyOf(byYear.values());
```

Use `frontier.size()` as `observationCount` and the sufficiency input. The raw
observation count remains available through the admin/import ledger only.

For each target:

```java
double required = Math.exp((Math.log(target.doubleValue()) - alpha) / beta);
double eta = asOfDate.getYear() + Math.max(0.0, required - currentCumulative) / cadence;
boolean beyond = !Double.isFinite(eta) || eta > asOfDate.getYear() + assumption.horizonYears();
Double visibleEta = beyond ? null : roundOneDecimal(eta);
```

Central cadence is the 5-year mean when available, otherwise 3-year. Fast is maximum and slow is minimum of available 3/5/10-year complete-window means. `qualificationFlags` is stored in stable alphabetical order joined by comma in SQL and exposed as a list in Java/API.

- [ ] **Step 4: Run tests and focused regression**

Run Step 2 plus `TransportEconomicsDatasetValidatorTest`. Expected: all pass, including the visible weak-fit provisional ETA.

- [ ] **Step 5: Commit Task 3**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/WrightProjectionCalculator.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport/WrightProjectionCalculatorTest.java
git commit -m "feat(tracker): project transport economics scenarios"
```

### Task 4: Projection service, schedules, and future Falcon-family LL2 counts

**Files:**
- Create: `.../tracker/transport/TransportProjectionService.java`
- Create: `.../tracker/ingest/TransportProjectionJob.java`
- Modify: `.../tracker/layerb/LaunchRecord.java`
- Modify: `.../tracker/layerb/LaunchLibraryParser.java`
- Modify: `.../tracker/layerb/LaunchCadenceAggregator.java`
- Modify: `apps/backend/springboot-app/src/main/resources/application.yml`
- Test: corresponding service/job/parser/aggregator tests.

**Interfaces:**
- Consumes: Task 2 imported data and Task 3 calculator.
- Produces: latest projection and annual `ANNUAL_FALCON_FAMILY_LAUNCH_COUNT` updates.

- [ ] **Step 1: Write Red tests for runtime mismatch, job gating, and family counts**

```java
@Test
void runtimeTargetMismatchFailsBeforeReplacingLatestProjection() {
        repository.saveProjection(existingProjection());
    assertThrows(IllegalStateException.class,
            () -> service.run(AS_OF, new BigDecimal("201"), EASY, HARD));
    assertEquals(existingProjection(), repository.findLatestProjection().orElseThrow());
}

@Test
void falconMetricCountsCompletedFalconNineAndHeavyOnly() {
    List<LayerBMetric> metrics = aggregator.aggregate(2025,
            List.of(success("Falcon 9 Block 5"), failure("Falcon Heavy"),
                    success("Starship"), scheduled("Falcon 9 Block 5")), ACCESSED);
    assertEquals(new BigDecimal("2"), value(metrics,
            "ANNUAL_FALCON_FAMILY_LAUNCH_COUNT"));
}
```

Also assert `TransportProjectionJob.runMonthly` has cron `${tracker.transport-projection-cron:0 47 3 8 * *}`, UTC, lock `tracker-transport-projection`, `PT10M`, and bean absence unless both tracker flags are true.

- [ ] **Step 2: Extend LL2 metadata without changing existing global cadence semantics**

Change the record and parser exactly:

```java
public record LaunchRecord(
        String id, String name, Instant net, String provider, String status,
        boolean successful, String vehicleConfiguration) {}
```

```java
item.path("rocket").path("configuration").path("full_name").asText("")
```

Falcon-family membership is case-insensitive exact prefix `FALCON 9` or `FALCON HEAVY`; do not match `FALCON 1` or Starship. Existing global `ANNUAL_LAUNCH_COUNT` and success-rate outputs remain byte-for-byte equivalent apart from the record constructor update.

- [ ] **Step 3: Implement service fail-closed ordering and monthly job**

```java
@Transactional
public TransportProjection run(
        LocalDate asOfDate, BigDecimal central, BigDecimal easy, BigDecimal hard) {
    TransportAssumption assumption = repository.findAssumption(ASSUMPTION_VERSION)
            .orElseThrow(() -> new IllegalStateException("transport assumption missing"));
    requireRuntimeMatch(assumption, central, easy, hard);
    TransportProjection projection = calculator.calculate(
            asOfDate, assumption, repository.findPriceObservations(),
            repository.findAnnualFalconLaunchCounts());
    repository.saveProjection(projection);
    return projection;
}
```

Job annotations:

```java
@Component
@ConditionalOnProperty(prefix = "tracker",
        name = {"enabled", "transport-economics-enabled"}, havingValue = "true")
public class TransportProjectionJob {
    @Scheduled(cron = "${tracker.transport-projection-cron:0 47 3 8 * *}", zone = "UTC")
    @SchedulerLock(name = "tracker-transport-projection", lockAtMostFor = "PT10M")
    public void runMonthly() { service.run(LocalDate.now(clock), central, easy, hard); }
}
```

- [ ] **Step 4: Add application safe defaults**

```yaml
tracker:
  transport-economics-enabled: ${TRACKER_TRANSPORT_ECONOMICS_ENABLED:false}
  transport-projection-cron: ${TRACKER_TRANSPORT_PROJECTION_CRON:0 47 3 8 * *}
  coherence-cron: ${TRACKER_COHERENCE_CRON:0 0 3 1 */3 *}
  transport-target-usd-per-kg: ${TRACKER_TRANSPORT_TARGET_USD_PER_KG:200}
  transport-target-easy-usd-per-kg: ${TRACKER_TRANSPORT_TARGET_EASY_USD_PER_KG:500}
  transport-target-hard-usd-per-kg: ${TRACKER_TRANSPORT_TARGET_HARD_USD_PER_KG:100}
```

- [ ] **Step 5: Run focused tests**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TransportProjectionServiceTest,TransportProjectionJobTest,LaunchLibraryParserTest,LaunchCadenceAggregatorTest,LaunchLibraryJobTest' test
```

Expected: selected tests pass; no test performs network I/O.

- [ ] **Step 6: Commit Task 4**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/TransportProjectionService.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TransportProjectionJob.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb apps/backend/springboot-app/src/main/resources/application.yml apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport/TransportProjectionServiceTest.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/TransportProjectionJobTest.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb
git commit -m "feat(tracker): schedule gated transport projections"
```

### Task 5: Quarterly coherence, deterministic audit, and non-destructive ETA overlay

**Files:**
- Create: `.../tracker/transport/TransportCoherenceCalculator.java`
- Create: `.../tracker/transport/TransportCoherenceService.java`
- Create: `.../tracker/transport/TransportEtaOverlay.java`
- Create: `.../tracker/ingest/TransportCoherenceJob.java`
- Modify: `.../tracker/domain/TrackerRepository.java`
- Test: calculator/service/overlay/job tests.

**Interfaces:**
- Consumes: latest projection, 3/5-year annual counts, latest Pillar 1 snapshot, previous report.
- Produces: report, at most 10 sample IDs, read-time `EtaBounds`.

- [ ] **Step 1: Write the full Red behavior matrix**

Tests cover:

```text
price beta < -1e-6 / > 1e-6 / otherwise -> ADVANCING / REGRESSING / FLAT
cadence log slope > .01 / < -.01 / otherwise -> ADVANCING / REGRESSING / FLAT
B inputs opposed -> MIXED
missing projection/count/snapshot -> INSUFFICIENT_DATA
same direction -> COHERENT and streak 0
first opposite direction -> WATCH and streak 1
same polarity next consecutive quarter -> DIVERGENT and streak 2
quarter gap or polarity change -> WATCH and streak 1
coherent/mixed/insufficient successor -> streak reset and no alert
```

Non-destructive proof records fingerprints before and after:

```java
String snapshotsBefore = TransportTestFixtures.tableFingerprint(
        jdbc, "pillar_snapshot", "pillar, snapshot_date, id");
String nodesBefore = TransportTestFixtures.tableFingerprint(
        jdbc, "capability_node", "id");
String eventsBefore = TransportTestFixtures.tableFingerprint(
        jdbc, "event", "id");
service.runForQuarter(LocalDate.of(2026, 6, 30));
assertEquals(snapshotsBefore, TransportTestFixtures.tableFingerprint(
        jdbc, "pillar_snapshot", "pillar, snapshot_date, id"));
assertEquals(nodesBefore, TransportTestFixtures.tableFingerprint(
        jdbc, "capability_node", "id"));
assertEquals(eventsBefore, TransportTestFixtures.tableFingerprint(
        jdbc, "event", "id"));
```

- [ ] **Step 2: Implement directions and consecutive-quarter logic**

```java
boolean consecutive = previous != null
        && previous.reportPeriodEnd().plusMonths(3).equals(reportPeriodEnd);
boolean samePolarity = consecutive && polarity.equals(previous.polarity());
int streak = polarity.equals("NONE") ? 0 : samePolarity
        ? previous.consecutiveQuarterStreak() + 1 : 1;
String state = layerB.equals("MIXED") ? "MIXED"
        : insufficient ? "INSUFFICIENT_DATA"
        : polarity.equals("NONE") ? "COHERENT"
        : streak >= 2 ? "DIVERGENT" : "WATCH";
```

`DIVERGENT` alone sets `alertActive=true`, widening factor 1.5, and preserves the first divergent period. Every other state uses factor 1.0.
When a report first enters `DIVERGENT`, emit one structured `WARN` containing only
the period, polarity, streak, and sample count. The admin coherence response is the
operations-facing alert projection; no pager, message, or external side effect is
added.

- [ ] **Step 3: Add deterministic read-only event selection and sample persistence**

Add this bounded query to `TrackerRepository`:

```sql
SELECT e.id
  FROM event e
  JOIN capability_node n ON n.id = e.node_id
 WHERE n.pillar = 1
   AND e.event_status = 'CONFIRMED'
   AND e.occurred_on > :startExclusive
   AND e.occurred_on <= :endInclusive
 ORDER BY COALESCE(e.impact_score, 0) DESC, e.occurred_on DESC, e.id
 FETCH FIRST 10 ROWS ONLY
```

The watched interval starts at the first `WATCH` report period exclusive and ends at the current divergent period inclusive. Re-running the same quarter relies on unique keys and produces no duplicate report or sample.

- [ ] **Step 4: Implement the API-only bounds overlay**

```java
double low = Math.max(nowYear,
        eta - factor.doubleValue() * (eta - baseLow));
double high = Math.min(nowYear + 150.0,
        eta + factor.doubleValue() * (baseHigh - eta));
return new EtaBounds(baseLow, baseHigh, roundOneDecimal(low), roundOneDecimal(high),
        true, report.reportPeriodEnd());
```

If pillar is not 1, no active divergent report exists, or any base value is null, return base values unchanged and `coherenceAdjusted=false`.

- [ ] **Step 5: Implement quarterly job**

The job runs at `${tracker.coherence-cron:0 0 3 1 */3 *}` UTC with lock `tracker-coherence`, `PT10M`; on 2026-07-01 it reports 2026-06-30. It shares the same two-property gate as the projection job.

- [ ] **Step 6: Run focused coherence tests**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TransportCoherenceCalculatorTest,TransportCoherenceServiceTest,TransportEtaOverlayTest,TransportCoherenceJobTest' test
```

Expected: all selected tests pass, deterministic sample order is exact, and all four protected domain-table fingerprints are unchanged.

- [ ] **Step 7: Commit Task 5**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/TransportCoherenceCalculator.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/TransportCoherenceService.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/transport/TransportEtaOverlay.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/TransportCoherenceJob.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/transport apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/TransportCoherenceJobTest.java
git commit -m "feat(tracker): report transport coherence non-destructively"
```

### Task 6: Public and admin HTTP contracts

**Files:**
- Create: `.../tracker/api/TransportEconomicsController.java`
- Create: `.../tracker/api/TransportCoherenceAdminController.java`
- Modify: `.../tracker/api/TrackerController.java`
- Test: `.../tracker/api/TransportEconomicsControllerTest.java`
- Test: `.../tracker/api/TransportCoherenceAdminControllerTest.java`
- Modify/Test: existing `TrackerControllerTest.java`.

**Interfaces:**
- Produces: `/api/tracker/transport-economics`, `/api/tracker/coherence/transport`, admin report/review endpoints, expanded pillar DTO.

- [ ] **Step 1: Write MockMvc contract tests before controllers**

Assert exact honesty constants:

```java
assertEquals("PUBLISHED_PRICE", json.get("basis").asText());
assertEquals("PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
        json.get("priceMeaning").asText());
assertEquals("Declared-assumption scenario; not provider internal cost",
        json.get("projectionLabel").asText());
assertEquals("ASSUMPTION_SENSITIVITY", json.get("intervalKind").asText());
assertEquals(200, json.get("centralTargetUsdPerKg").asInt());
assertEquals(500, json.get("easyTargetUsdPerKg").asInt());
assertEquals(100, json.get("hardTargetUsdPerKg").asInt());
```

Admin tests prove missing/wrong token → 401, blank or >2000 note → 400, unknown sample → 404, already reviewed → 409, first valid review → 200.

- [ ] **Step 2: Implement public DTO mapping without fabricating missing ETA**

If no projection/report exists, return HTTP 200 with `status=INSUFFICIENT_DATA`, null ETA fields, fixed assumptions/labels, and no alert. This keeps the dashboard available before the first scheduled run.

- [ ] **Step 3: Extend pillar output with base and displayed bounds**

Each pillar entry adds:

```java
entry.put("baseEtaLow", bounds.baseEtaLow());
entry.put("baseEtaHigh", bounds.baseEtaHigh());
entry.put("etaLow", bounds.etaLow());
entry.put("etaHigh", bounds.etaHigh());
entry.put("coherenceAdjusted", bounds.coherenceAdjusted());
entry.put("coherenceReportPeriod", bounds.coherenceReportPeriod());
```

- [ ] **Step 4: Implement admin sample review with the existing constant-time token pattern**

Use `MessageDigest.isEqual`, trim the note, accept 1..2000 characters, and perform the one-way repository update. Return sample state only; never return or mutate the referenced event body.

- [ ] **Step 5: Run controller tests**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' '-Dtest=TransportEconomicsControllerTest,TransportCoherenceAdminControllerTest,TrackerControllerTest' test
```

Expected: all selected tests pass and null projection returns an honest 200 response.

- [ ] **Step 6: Commit Task 6**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api
git commit -m "feat(tracker): expose transport economics and coherence"
```

### Task 7: Compact transport economics card and adjusted pillar bounds

**Files:**
- Create: `apps/frontend/react-app/src/tracker/TransportEconomicsCard.tsx`
- Create: `apps/frontend/react-app/src/tracker/TransportEconomicsCard.test.tsx`
- Modify: `apps/frontend/react-app/src/tracker/api.ts`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.tsx`
- Modify: `apps/frontend/react-app/src/tracker/TrackerPage.test.tsx`
- Modify: `apps/frontend/react-app/src/App.css`

**Interfaces:**
- Consumes: Task 6 JSON.
- Produces: compact WP3.3 card; WP3.4 four-column panel remains absent.

- [ ] **Step 1: Write Vitest Red tests for all honest states**

```tsx
it("shows a provisional weak-fit estimate without hiding it", () => {
  render(<TransportEconomicsCard projection={provisionalWeakFit} />);
  expect(screen.getByText(/잠정 3개 관측/)).toBeInTheDocument();
  expect(screen.getByText(/적합도 낮음/)).toBeInTheDocument();
  expect(screen.getByText(/공표 가격.*실제 원가 아님/)).toBeInTheDocument();
  expect(screen.getByText(/\$200\/kg/)).toBeInTheDocument();
  expect(screen.getByText(/\$100–\$500\/kg/)).toBeInTheDocument();
});

it("does not invent a year for non-declining or insufficient data", () => {
  const { rerender } = render(<TransportEconomicsCard projection={nonDeclining} />);
  expect(screen.getByText("하락 추세 미확인")).toBeInTheDocument();
  rerender(<TransportEconomicsCard projection={insufficient} />);
  expect(screen.getByText("자료 부족")).toBeInTheDocument();
});
```

Define the three fixtures in the same test through one complete builder so no
wire field is implicit:

```tsx
function projection(
  overrides: Partial<TransportProjection> = {},
): TransportProjection {
  return {
    status: "PROVISIONAL",
    sufficiencyTier: "PROVISIONAL",
    qualificationFlags: [],
    observationCount: 3,
    centralTargetUsdPerKg: 200,
    easyTargetUsdPerKg: 500,
    hardTargetUsdPerKg: 100,
    centralEtaYear: 2098.4,
    earliestEtaYear: 2074.2,
    latestEtaYear: null,
    centralBeyondHorizon: false,
    earliestBeyondHorizon: false,
    latestBeyondHorizon: true,
    priceBasisYear: 2025,
    basis: "PUBLISHED_PRICE",
    priceMeaning: "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
    projectionLabel: "Declared-assumption scenario; not provider internal cost",
    intervalKind: "ASSUMPTION_SENSITIVITY",
    coherenceState: "COHERENT",
    coherenceAlertActive: false,
    ...overrides,
  };
}
const provisionalWeakFit = projection({ qualificationFlags: ["WEAK_FIT"] });
const nonDeclining = projection({ status: "NON_DECLINING", centralEtaYear: null });
const insufficient = projection({
  status: "INSUFFICIENT_DATA", sufficiencyTier: "INSUFFICIENT_DATA",
  observationCount: 2, centralEtaYear: null,
});
```

Also cover central `2175+`, individual sensitivity edge `2175+`, active coherence warning, and no warning for `WATCH`.

- [ ] **Step 2: Run frontend tests and confirm Red**

```powershell
npm test -- --run src/tracker/TransportEconomicsCard.test.tsx src/tracker/TrackerPage.test.tsx
```

Expected: missing component/types/functions.

- [ ] **Step 3: Add exact TypeScript contracts and GET functions**

```ts
export interface TransportProjection {
  status: "INSUFFICIENT_DATA" | "PROVISIONAL" | "ESTABLISHED" |
    "NON_DECLINING" | "REACHED" | "BEYOND_HORIZON";
  sufficiencyTier: "INSUFFICIENT_DATA" | "PROVISIONAL" | "ESTABLISHED";
  qualificationFlags: string[];
  observationCount: number;
  centralTargetUsdPerKg: number;
  easyTargetUsdPerKg: number;
  hardTargetUsdPerKg: number;
  centralEtaYear: number | null;
  earliestEtaYear: number | null;
  latestEtaYear: number | null;
  centralBeyondHorizon: boolean;
  earliestBeyondHorizon: boolean;
  latestBeyondHorizon: boolean;
  priceBasisYear: number;
  basis: "PUBLISHED_PRICE";
  priceMeaning: "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD";
  projectionLabel: string;
  intervalKind: "ASSUMPTION_SENSITIVITY";
  coherenceState: string;
  coherenceAlertActive: boolean;
}
```

Extend `PillarSummary` with nullable `baseEtaLow`, `baseEtaHigh`, `etaLow`, `etaHigh`, `coherenceReportPeriod` and boolean `coherenceAdjusted`.

- [ ] **Step 4: Implement card and page integration**

Fetch `getTransportEconomics()` in the existing `Promise.all`; place the card immediately after `LayerBPanel`. Use an `<section aria-labelledby="transport-economics-heading">`, semantic `<dl>`, and text labels rather than color-only status.

- [ ] **Step 5: Run frontend tests and production build**

```powershell
npm test -- --run
npm run build
```

Expected: all Vitest tests pass, `tsc && vite build` exits 0, and no new dependency is added.

- [ ] **Step 6: Commit Task 7**

```powershell
git add apps/frontend/react-app/src/tracker/TransportEconomicsCard.tsx apps/frontend/react-app/src/tracker/TransportEconomicsCard.test.tsx apps/frontend/react-app/src/tracker/api.ts apps/frontend/react-app/src/tracker/TrackerPage.tsx apps/frontend/react-app/src/tracker/TrackerPage.test.tsx apps/frontend/react-app/src/App.css
git commit -m "feat(tracker): show transport economics scenarios"
```

### Task 8: GitOps safe defaults, full regression, local first report, and Phase 3 evidence

**Files:**
- Modify: `gitops/apps/backend-springboot/deployment.yaml`
- Modify: `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1`
- Modify: Phase 3 and master plans listed in the file map.
- Create: `docs/research/tracker-wp33-validation-evidence.md`

**Interfaces:**
- Consumes: complete WP3.3 implementation.
- Produces: deploy-safe disabled configuration, reproducible verification evidence, visible local report.

- [ ] **Step 1: Write failing GitOps assertions, then add exact environment defaults**

Verifier assertions:

```powershell
$transportDefaults = @{
    TRACKER_TRANSPORT_ECONOMICS_ENABLED = 'false'
    TRACKER_TRANSPORT_PROJECTION_CRON = '0 47 3 8 * *'
    TRACKER_COHERENCE_CRON = '0 0 3 1 */3 *'
    TRACKER_TRANSPORT_TARGET_USD_PER_KG = '200'
    TRACKER_TRANSPORT_TARGET_EASY_USD_PER_KG = '500'
    TRACKER_TRANSPORT_TARGET_HARD_USD_PER_KG = '100'
}
```

For each key, require one manifest occurrence and exact value. Also compare all `matchName` hosts before/after: no transport-specific host is allowed because WP3.3 reuses the already approved LL2 path and immutable resource.

- [ ] **Step 2: Run GitOps verification and render**

```powershell
& gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
kubectl kustomize gitops/clusters/prod
```

Expected: verifier prints `OK`, render exits 0, no `CronJob`, transport secret, or new external FQDN appears.

- [ ] **Step 3: Run complete backend, frontend, build, and whitespace regression**

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
npm test -- --run
npm run build
git diff --check
```

Expected: every command exits 0. Record exact backend/frontend totals, build output summary, and GitOps render line count in `tracker-wp33-validation-evidence.md`.

- [ ] **Step 4: Produce the first local projection and completed-quarter report**

Run backend with only local process overrides:

```powershell
$env:SPRING_PROFILES_ACTIVE='test,demo,refbackfill'
$env:TRACKER_ENABLED='true'
$env:TRACKER_TRANSPORT_ECONOMICS_ENABLED='true'
$env:TRACKER_TRANSPORT_PROJECTION_CRON='*/15 * * * * *'
$env:TRACKER_COHERENCE_CRON='5/15 * * * * *'
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd' -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' spring-boot:test-run
```

Then verify:

```powershell
Invoke-RestMethod 'http://localhost:8080/api/tracker/transport-economics' | ConvertTo-Json -Depth 8
Invoke-RestMethod 'http://localhost:8080/api/tracker/coherence/transport' | ConvertTo-Json -Depth 8
Invoke-RestMethod 'http://localhost:8080/api/tracker/pillars' | ConvertTo-Json -Depth 8
```

Accept `COHERENT`, `WATCH`, `DIVERGENT`, or `INSUFFICIENT_DATA`; do not alter data to force a preferred state. Confirm LIVE_MODEL and LL2 live polling remain false.

- [ ] **Step 5: Browser-inspect the Tracker tab**

Open `http://localhost:5174`, select Tracker, and verify the card shows `$200/kg`, `$100–$500/kg`, 2025 USD, observation count, public-price/not-cost wording, the correct finite/null/horizon state, and any coherence warning. Confirm browser console error count is zero and the existing Tracker tab, Layer B panel, timeline, review, and ops sections remain present.

- [ ] **Step 6: Update Phase 3 evidence and commit GitOps/docs**

Mark WP3.3 complete only after Step 3–5 evidence exists. Keep the master plan to status, gate, exact totals, and links; keep command transcripts and first report values in the detailed evidence document.

```powershell
git add gitops/apps/backend-springboot/deployment.yaml gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1 docs/plans/multiplanetary-tracker-execution-plan.md docs/superpowers/plans/2026-07-14-tracker-phase3-kickoff-plan.md docs/research/tracker-wp33-validation-evidence.md
git commit -m "docs(tracker): complete WP3.3 transport economics"
```

- [ ] **Step 7: Verify protected fixtures, push, and update the existing PR**

```powershell
git status --short
git diff --cached --name-only
git push origin feat/tracker-mvp
```

Expected: the four protected untracked fixture paths are absent from the index, push succeeds, and the existing Phase 3 PR includes every WP3.3 commit. Do not activate `TRACKER_ENABLED`, LL2, transport economics, or LIVE_MODEL in GitOps.

---

## 3. Acceptance traceability

| Approved design requirement | Implementing Task |
|---|---|
| 2025 USD, 200 central, 100–500 sensitivity | 2, 3, 4, 6, 7, 8 |
| Published-price lower-bound proxy labels | 2, 6, 7 |
| Falcon operational observations only | 2, 4 |
| 3–4 provisional, 5+ established, weak fit visible | 2, 3, 7 |
| Wright log-log OLS and constant cadence scenarios | 3 |
| Independent 150-year edges | 3, 6, 7 |
| Immutable resource/hash/import semantics | 1, 2 |
| Annual Falcon-family LL2 future updates | 4 |
| Quarterly two-strike matched-pair coherence | 5 |
| Deterministic max-10 read-only audit sample | 1, 5, 6 |
| Non-destructive 1.5 Pillar 1 overlay | 5, 6 |
| Public/admin API and compact UI | 6, 7 |
| Conditional in-process jobs, default false, no egress/secret | 4, 8 |
| First local report and full regression | 8 |

## 4. Execution selection

The user has explicitly disallowed subagents for this Phase 3 continuation. Execute this plan inline with `superpowers:executing-plans`, one Task at a time, stopping only at a real external-authority blocker or a failed invariant that cannot be resolved safely. Do not wait for another execution-mode choice.
