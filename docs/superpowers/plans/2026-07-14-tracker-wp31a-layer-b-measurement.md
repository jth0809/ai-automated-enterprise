# WP3.1-A Layer B 계측 기반 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 상위: [Phase 3 실행준비 계획](2026-07-14-tracker-phase3-kickoff-plan.md) · [마스터 실행계획](../../plans/multiplanetary-tracker-execution-plan.md)

**Goal:** 비-AI 실측 지표(Layer B)를 저작권 안전 방식으로 저장·수집·조회·표시하는 기반을 만든다. 라이브 외부 API(Launch Library 2·Metaculus)는 egress가 필요하므로 WP3.1-B로 분리하고, 본 계획은 네트워크 독립적으로 지금 빌드·검증한다.

**Architecture:** 기존 참조형 백필 패턴(버전 스탬프 import + 정적 검증기 + 멱등 로더)을 재사용한다. `layer_b_metric` 테이블에 수치 지표만 저장(외부 본문·인용·바이너리 미저장). 저작권/저장 위험은 URL·라벨·접근일·SHA-256·직접 작성 요약만 저장해 통제한다. $/kg는 "공표 가격 기반 추정"으로 플래그한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway V11, H2(Oracle mode)/Oracle ATP, JdbcClient, JUnit 5, JSON.

## Global Constraints

- 작업 위치 `.claude/worktrees/tracker-mvp`, 브랜치 `feat/tracker-mvp`.
- 보호 미추적 파일(`.claude/`, `application-demo.yml`, `application-refbackfill.yml`, `backfill-demo.json`) 미스테이징.
- Bash 셸이 이 환경에서 깨졌으므로 **PowerShell로 실행**한다. Maven은 캐시 바이너리를 unsandboxed로:
  `& "C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\3477a4f1\bin\mvn.cmd" -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" test "-Dtest=..."`
- 외부 원문·인용문·HTML·PDF·이미지·바이너리 저장 금지. Layer B는 수치 지표 + 출처 메타데이터만 저장한다.
- 실제 도달 조건·`Params.defaults()`·`Readiness`·`LogitEta`·35노드·`r2.0`을 변경하지 않는다. Layer B는 Layer A(모델)와 분리된 관측 레이어다.
- 정직성 표기(v2.10): $/kg=공표 가격 기반, 측정 지표 vs 구성 지수 구분.
- 작업별 독립 검증 후 한 커밋.

## 파일 구조

- Create `.../resources/db/migration/V11__tracker_layer_b.sql` — `layer_b_metric`, `layer_b_metric_import` 테이블.
- Create `.../tracker/domain/LayerBMetric.java` — 지표 도메인 레코드.
- Modify `.../tracker/domain/TrackerRepository.java` — insert/조회 메서드.
- Create `.../tracker/layerb/LayerBDatasetValidator.java` — 정적 검증기(허용 metric code·pillar·published-price·provenance·금지 키).
- Create `.../resources/tracker/layer-b-metrics-v1.json` — 직접 작성한 실측 지표 시드.
- Create `.../tracker/ingest/LayerBLoader.java` — 멱등 버전 스탬프 로더.
- Create 대응 테스트 4종(schema/validator/repository/loader).

---

### Task 1: V11 Layer B 스키마

**Files:**
- Create: `apps/backend/springboot-app/src/main/resources/db/migration/V11__tracker_layer_b.sql`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/LayerBSchemaTest.java`

**Interfaces:**
- Produces: 테이블 `layer_b_metric(id, metric_code, pillar, observed_on, value, unit, basis, source_label, source_url, accessed_on, content_sha256, fact_summary, created_at)` 와 `layer_b_metric_import(dataset_version PK, dataset_sha256, record_count, loaded_at)`.
- `basis` 는 `MEASURED` | `PUBLISHED_PRICE` | `CONSTRUCTED` (정직성 구분).

- [ ] **Step 1: 실패 스키마 테스트를 작성한다**

```java
package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    private void insert(String code, int pillar, String basis) {
        jdbc.sql("""
                INSERT INTO layer_b_metric
                  (metric_code, pillar, observed_on, value, unit, basis,
                   source_label, source_url, accessed_on, content_sha256, fact_summary)
                VALUES
                  (:code, :pillar, DATE '2026-01-01', 2600, 'USD_PER_KG', :basis,
                   'Provider price sheet', 'https://example.test/price', DATE '2026-07-14',
                   :hash, 'Published price per kilogram to LEO.')
                """)
                .param("code", code).param("pillar", pillar).param("basis", basis)
                .param("hash", "a".repeat(64)).update();
    }

    @Test
    void acceptsValidMetricAndRejectsBadBasisAndPillar() {
        insert("LAUNCH_PRICE_LEO", 1, "PUBLISHED_PRICE");
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("BAD_BASIS", 1, "GUESS"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("BAD_PILLAR", 9, "MEASURED"));
    }

    @Test
    void importAuditEnforcesPositiveCountAndHashUniqueness() {
        jdbc.sql("INSERT INTO layer_b_metric_import (dataset_version, dataset_sha256, record_count) VALUES ('lb-v1', :h, 3)")
                .param("h", "b".repeat(64)).update();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("INSERT INTO layer_b_metric_import (dataset_version, dataset_sha256, record_count) VALUES ('lb-empty', :h, 0)")
                        .param("h", "c".repeat(64)).update());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인 (PowerShell)**

Run: `& $Maven -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" test "-Dtest=LayerBSchemaTest"`
Expected: FAIL — `layer_b_metric` 테이블 없음.

- [ ] **Step 3: V11 마이그레이션을 작성한다**

```sql
-- Phase 3 Layer B: measured non-AI indicators (numeric only).
-- No external source body, quote, excerpt, HTML, PDF, image, or binary is
-- stored. Only numeric values plus provenance metadata and an authored summary.

CREATE TABLE layer_b_metric (
  id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  metric_code     VARCHAR2(60) NOT NULL,
  pillar          NUMBER(1) NOT NULL,
  observed_on     DATE NOT NULL,
  value           NUMBER(20,4) NOT NULL,
  unit            VARCHAR2(30) NOT NULL,
  basis           VARCHAR2(20) NOT NULL,
  source_label    VARCHAR2(200) NOT NULL,
  source_url      VARCHAR2(600) NOT NULL,
  accessed_on     DATE NOT NULL,
  content_sha256  CHAR(64) NOT NULL,
  fact_summary    VARCHAR2(500) NOT NULL,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_layer_b_pillar CHECK (pillar BETWEEN 1 AND 6),
  CONSTRAINT ck_layer_b_basis CHECK (basis IN ('MEASURED','PUBLISHED_PRICE','CONSTRUCTED')),
  CONSTRAINT uq_layer_b_natural UNIQUE (metric_code, observed_on)
);

COMMENT ON COLUMN layer_b_metric.value IS
  'Numeric indicator only; NO EXTERNAL SOURCE BODY, QUOTE, OR BINARY';
COMMENT ON COLUMN layer_b_metric.basis IS
  'MEASURED=observed count/rate; PUBLISHED_PRICE=list price estimate; CONSTRUCTED=composite index';

CREATE INDEX ix_layer_b_pillar ON layer_b_metric(pillar, metric_code, observed_on);

CREATE TABLE layer_b_metric_import (
  dataset_version  VARCHAR2(80) PRIMARY KEY,
  dataset_sha256   CHAR(64) NOT NULL,
  record_count     NUMBER(5) NOT NULL,
  loaded_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_layer_b_import_sha UNIQUE (dataset_sha256),
  CONSTRAINT ck_layer_b_import_count CHECK (record_count > 0)
);
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `& $Maven -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" test "-Dtest=LayerBSchemaTest"`
Expected: PASS 2/2.

- [ ] **Step 5: 커밋**

```powershell
git add apps/backend/springboot-app/src/main/resources/db/migration/V11__tracker_layer_b.sql apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/LayerBSchemaTest.java
git commit -m "feat(tracker): add Layer B measurement schema (V11)"
```

---

### Task 2: LayerBMetric 도메인 + repository

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/LayerBMetric.java`
- Modify: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/domain/LayerBMetricRepositoryTest.java`

**Interfaces:**
- Produces: `record LayerBMetric(long id, String metricCode, int pillar, LocalDate observedOn, java.math.BigDecimal value, String unit, String basis, String sourceLabel, String sourceUrl, LocalDate accessedOn, String contentSha256, String factSummary)`.
- Repository: `long upsertLayerBMetric(LayerBMetric draft)` (멱등, `(metric_code, observed_on)` 자연 키), `List<LayerBMetric> findLatestLayerBByPillar()` (필라별 metric_code 최신 1건), `int countLayerBMetrics()`.

- [ ] **Step 1: 실패 repository 테스트를 작성한다**

```java
package com.aienterprise.backend.tracker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBMetricRepositoryTest {

    @Autowired
    private TrackerRepository repository;

    private LayerBMetric draft(String code, LocalDate on, String value) {
        return new LayerBMetric(0, code, 1, on, new BigDecimal(value), "USD_PER_KG",
                "PUBLISHED_PRICE", "Provider price sheet", "https://example.test/p",
                LocalDate.of(2026, 7, 14), "a".repeat(64), "Published price per kg to LEO.");
    }

    @Test
    void upsertIsIdempotentByNaturalKey() {
        long first = repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        long second = repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        assertEquals(first, second);
        assertEquals(1, repository.countLayerBMetrics());
    }

    @Test
    void findLatestByPillarReturnsMostRecentPerCode() {
        repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2024, 1, 1), "6000"));
        repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        var latest = repository.findLatestLayerBByPillar().stream()
                .filter(m -> m.metricCode().equals("LAUNCH_PRICE_LEO")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2600").compareTo(latest.value()));
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `... "-Dtest=LayerBMetricRepositoryTest"`; Expected: 컴파일 실패(`LayerBMetric`/메서드 없음).

- [ ] **Step 3: 도메인 레코드와 repository 메서드를 구현한다**

`LayerBMetric.java`:

```java
package com.aienterprise.backend.tracker.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LayerBMetric(
        long id, String metricCode, int pillar, LocalDate observedOn, BigDecimal value,
        String unit, String basis, String sourceLabel, String sourceUrl,
        LocalDate accessedOn, String contentSha256, String factSummary) {
}
```

`TrackerRepository.java` 에 추가(기존 `date(...)`/`localDate(...)` 헬퍼 재사용):

```java
public long upsertLayerBMetric(LayerBMetric draft) {
    Optional<Long> existing = jdbc.sql(
            "SELECT id FROM layer_b_metric WHERE metric_code = :code AND observed_on = :on")
            .param("code", draft.metricCode()).param("on", date(draft.observedOn()))
            .query(Long.class).optional();
    if (existing.isPresent()) {
        return existing.get();
    }
    jdbc.sql("""
            INSERT INTO layer_b_metric
              (metric_code, pillar, observed_on, value, unit, basis,
               source_label, source_url, accessed_on, content_sha256, fact_summary)
            VALUES
              (:code, :pillar, :on, :value, :unit, :basis,
               :label, :url, :accessed, :hash, :summary)
            """)
            .param("code", draft.metricCode()).param("pillar", draft.pillar())
            .param("on", date(draft.observedOn())).param("value", draft.value())
            .param("unit", draft.unit()).param("basis", draft.basis())
            .param("label", draft.sourceLabel()).param("url", draft.sourceUrl())
            .param("accessed", date(draft.accessedOn())).param("hash", draft.contentSha256())
            .param("summary", draft.factSummary()).update();
    return jdbc.sql("SELECT id FROM layer_b_metric WHERE metric_code = :code AND observed_on = :on")
            .param("code", draft.metricCode()).param("on", date(draft.observedOn()))
            .query(Long.class).single();
}

public int countLayerBMetrics() {
    return jdbc.sql("SELECT COUNT(*) FROM layer_b_metric").query(Integer.class).single();
}

public List<LayerBMetric> findLatestLayerBByPillar() {
    return jdbc.sql("""
            SELECT m.id, m.metric_code, m.pillar, m.observed_on, m.value, m.unit, m.basis,
                   m.source_label, m.source_url, m.accessed_on, m.content_sha256, m.fact_summary
              FROM layer_b_metric m
             WHERE m.observed_on = (SELECT MAX(m2.observed_on) FROM layer_b_metric m2
                                     WHERE m2.metric_code = m.metric_code)
             ORDER BY m.pillar, m.metric_code
            """)
            .query((rs, rowNum) -> new LayerBMetric(
                    rs.getLong("id"), rs.getString("metric_code"), rs.getInt("pillar"),
                    localDate(rs.getDate("observed_on")), rs.getBigDecimal("value"),
                    rs.getString("unit"), rs.getString("basis"), rs.getString("source_label"),
                    rs.getString("source_url"), localDate(rs.getDate("accessed_on")),
                    rs.getString("content_sha256"), rs.getString("fact_summary")))
            .list();
}
```

- [ ] **Step 4: 통과 확인** — Run: `... "-Dtest=LayerBMetricRepositoryTest"`; Expected: PASS 2/2.

- [ ] **Step 5: 커밋**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/LayerBMetric.java apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/domain/LayerBMetricRepositoryTest.java
git commit -m "feat(tracker): add Layer B metric repository"
```

---

### Task 3: 정적 검증기 + 실측 지표 시드

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidator.java`
- Create: `apps/backend/springboot-app/src/main/resources/tracker/layer-b-metrics-v1.json`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidatorTest.java`

**Interfaces:**
- Produces: `LayerBDatasetValidator.validate(Resource) -> ValidatedLayerB(List<LayerBMetric> metrics, List<String> errors)`. 허용 metric code(예: `LAUNCH_PRICE_LEO`, `ANNUAL_LAUNCH_COUNT`, `ANNUAL_UPMASS_TONNES`)·basis·pillar 범위·`content_sha256` 64hex·금지 키(quote/body/html/pdf/image) 검사. 값이 음수면 거부.

- [ ] **Step 1: 실패 검증기 테스트를 작성한다**

```java
package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LayerBDatasetValidatorTest {

    @Test
    void productionSeedValidatesCleanly() {
        var result = new LayerBDatasetValidator()
                .validate(new ClassPathResource("tracker/layer-b-metrics-v1.json"));
        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertTrue(result.metrics().size() >= 3);
    }

    @Test
    void rejectsUnknownCodeBadBasisAndProhibitedKey() {
        var result = new LayerBDatasetValidator().validateJson("""
                [{"metricCode":"UNKNOWN","pillar":9,"observedOn":"2026-01-01","value":-1,
                  "unit":"X","basis":"GUESS","sourceLabel":"L","sourceUrl":"https://x.test",
                  "accessedOn":"2026-07-14","contentSha256":"zz","factSummary":"s","body":"leak"}]
                """);
        assertEquals(false, result.errors().isEmpty());
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `... "-Dtest=LayerBDatasetValidatorTest"`; Expected: 컴파일/실행 실패.

- [ ] **Step 3: 시드 JSON을 작성한다** (직접 작성 요약, 공표가 플래그)

`layer-b-metrics-v1.json` (예시 3건 — 실행 시 공표 자료로 값·URL·해시 확정):

```json
[
  {"metricCode":"LAUNCH_PRICE_LEO","pillar":1,"observedOn":"2024-01-01","value":2720,"unit":"USD_PER_KG","basis":"PUBLISHED_PRICE","sourceLabel":"Provider published price sheet","sourceUrl":"https://www.spacex.com/","accessedOn":"2026-07-14","contentSha256":"<64hex>","factSummary":"Published list price per kilogram to low Earth orbit for a reusable medium-lift vehicle."},
  {"metricCode":"ANNUAL_LAUNCH_COUNT","pillar":1,"observedOn":"2024-12-31","value":259,"unit":"LAUNCHES","basis":"MEASURED","sourceLabel":"Agency launch log","sourceUrl":"https://www.faa.gov/","accessedOn":"2026-07-14","contentSha256":"<64hex>","factSummary":"Count of orbital launch attempts recorded for the calendar year."},
  {"metricCode":"ANNUAL_UPMASS_TONNES","pillar":1,"observedOn":"2024-12-31","value":1900,"unit":"TONNES","basis":"MEASURED","sourceLabel":"Industry upmass report","sourceUrl":"https://brycetech.com/","accessedOn":"2026-07-14","contentSha256":"<64hex>","factSummary":"Estimated total mass delivered to orbit during the calendar year."}
]
```

> 실행 시 `Get-FileHash` 등으로 각 출처 페이지의 실제 SHA-256을 채운다. 값은 공표 자료 기준으로 확정하고 `basis`를 정직하게 표기한다.

- [ ] **Step 4: 검증기를 구현한다** (기존 `BackfillDatasetValidator`의 금지 키·해시 패턴 재사용, 허용 code/basis allowlist)

```java
package com.aienterprise.backend.tracker.layerb;

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

public class LayerBDatasetValidator {

    public record ValidatedLayerB(List<LayerBMetric> metrics, List<String> errors) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> CODES =
            Set.of("LAUNCH_PRICE_LEO", "ANNUAL_LAUNCH_COUNT", "ANNUAL_UPMASS_TONNES");
    private static final Set<String> BASES = Set.of("MEASURED", "PUBLISHED_PRICE", "CONSTRUCTED");
    private static final Set<String> PROHIBITED =
            Set.of("quote", "body", "bodyhtml", "html", "pdf", "image", "excerpt", "attachment");

    public ValidatedLayerB validate(Resource resource) {
        try (var in = resource.getInputStream()) {
            return validateNode(JSON.readTree(in));
        } catch (Exception e) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: cannot read resource"));
        }
    }

    public ValidatedLayerB validateJson(String json) {
        try {
            return validateNode(JSON.readTree(json));
        } catch (Exception e) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: invalid json"));
        }
    }

    private ValidatedLayerB validateNode(JsonNode root) {
        List<String> errors = new ArrayList<>();
        List<LayerBMetric> metrics = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: root must be an array"));
        }
        int i = 0;
        for (JsonNode item : root) {
            String at = "metric[" + i++ + "]";
            item.fieldNames().forEachRemaining(name -> {
                if (PROHIBITED.contains(name.toLowerCase())) {
                    errors.add(at + ": prohibited field " + name);
                }
            });
            String code = item.path("metricCode").asText("");
            if (!CODES.contains(code)) errors.add(at + ": unknown metricCode " + code);
            int pillar = item.path("pillar").asInt(0);
            if (pillar < 1 || pillar > 6) errors.add(at + ": pillar out of range");
            String basis = item.path("basis").asText("");
            if (!BASES.contains(basis)) errors.add(at + ": bad basis " + basis);
            String hash = item.path("contentSha256").asText("");
            if (!SHA256.matcher(hash).matches()) errors.add(at + ": bad content_sha256");
            BigDecimal value = item.path("value").decimalValue();
            if (value.signum() < 0) errors.add(at + ": negative value");
            if (errors.isEmpty() || errors.stream().noneMatch(e -> e.startsWith(at))) {
                metrics.add(new LayerBMetric(0, code, pillar,
                        LocalDate.parse(item.path("observedOn").asText()), value,
                        item.path("unit").asText(), basis, item.path("sourceLabel").asText(),
                        item.path("sourceUrl").asText(), LocalDate.parse(item.path("accessedOn").asText()),
                        hash, item.path("factSummary").asText()));
            }
        }
        return new ValidatedLayerB(metrics, errors);
    }
}
```

- [ ] **Step 5: 통과 확인** — Run: `... "-Dtest=LayerBDatasetValidatorTest"`; Expected: PASS 2/2.

- [ ] **Step 6: 커밋**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidator.java apps/backend/springboot-app/src/main/resources/tracker/layer-b-metrics-v1.json apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/layerb/LayerBDatasetValidatorTest.java
git commit -m "feat(tracker): validate Layer B measurement seed"
```

---

### Task 4: 멱등 로더 + 전체 회귀

**Files:**
- Create: `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/LayerBLoader.java`
- Test: `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/LayerBLoaderTest.java`

**Interfaces:**
- Consumes: `LayerBDatasetValidator`, `TrackerRepository.upsertLayerBMetric`, `countLayerBMetrics`.
- Produces: `LayerBLoader.loadIfNeeded()` — `layer_b_metric_import` 에 버전(`layer-b-v1`)+해시가 없으면 검증·적재하고 audit 행을 기록. 재실행 no-op. `BackfillLoader` 패턴을 따른다(@Component, @ConditionalOnProperty, @Autowired 생성자, @Transactional, @SchedulerLock).

- [ ] **Step 1: 실패 로더 테스트를 작성한다**

```java
package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBLoaderTest {

    @Autowired private TrackerRepository repository;
    @Autowired private JdbcClient jdbc;

    @Test
    void loadsSeedOnceAndIsIdempotent() {
        LayerBLoader loader = new LayerBLoader(repository,
                new org.springframework.core.io.ClassPathResource("tracker/layer-b-metrics-v1.json"),
                "layer-b-v1");
        loader.loadIfNeeded();
        int afterFirst = repository.countLayerBMetrics();
        loader.loadIfNeeded();
        assertEquals(afterFirst, repository.countLayerBMetrics());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM layer_b_metric_import WHERE dataset_version = 'layer-b-v1'")
                .query(Integer.class).single());
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `... "-Dtest=LayerBLoaderTest"`; Expected: 컴파일 실패.

- [ ] **Step 3: 로더를 구현한다** (`BackfillLoader.java` 를 참고해 동일 패턴; 생성자 시그니처는 테스트와 일치: `LayerBLoader(TrackerRepository, Resource, String datasetVersion)`; sha256 계산 후 `layer_b_metric_import` upsert, 검증 통과 시 각 metric upsert).

- [ ] **Step 4: 통과 확인** — Run: `... "-Dtest=LayerBLoaderTest"`.

- [ ] **Step 5: 전체 백엔드 회귀** — Run: `& $Maven -o "-Dmaven.repo.local=C:\Users\jang\.m2\repository" test`; Expected: 기존 379 + 신규 8 내외 모두 green.

- [ ] **Step 6: 커밋**

```powershell
git add apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/LayerBLoader.java apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/LayerBLoaderTest.java
git commit -m "feat(tracker): load Layer B measurement seed idempotently"
```

---

## 후속 실행 상태

- **WP3.1-A Task 5~6 완료:** 공개 API `/api/tracker/layer-b` + 프런트 Layer B
  패널(정직성 라벨 "공표 가격 기반 추정", "측정 vs 구성 지수") — `e79773b`,
  `c17b3bd`.
- **WP3.1-B Layer B 경로 완료:** LL2 클라이언트·연간 집계·조건부 월간
  in-process 잡 + exact-host CNP. 신규 Kubernetes CronJob은 만들지 않았다.
  상세: [WP3.1-B 계획](2026-07-14-tracker-wp31b-launch-library.md).
- **LL2 → tracker event 승격 보류:** LIVE_MODEL이 `NOT_ACTIVATED`인 동안에는
  자동 역량 사건을 만들지 않는다.

## Self-Review

- 스펙 커버리지: Layer B 저장(Task 1)·모델/조회(Task 2)·검증/시드(Task 3)·멱등 적재(Task 4) 매핑됨. API/UI·LL2 라이브는 명시적으로 범위 밖으로 분리.
- 플레이스홀더: 시드 JSON의 `<64hex>`는 실행 시 실제 해시로 채우는 의도적 표기(Step 3 지시 포함). 그 외 코드 스텝은 완전 코드.
- 타입 일관성: `LayerBMetric` 필드/`upsertLayerBMetric`/`findLatestLayerBByPillar`/`countLayerBMetrics`/`LayerBLoader(TrackerRepository, Resource, String)` 시그니처가 Task 간 일치.
