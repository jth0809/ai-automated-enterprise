# Tracker Phase 2 WP2.1/WP2.2-A 상세 구현 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 상태: 2026-07-14 완료. 실행 커밋 `305734b`, `8d3c91b`, `af43164`.

## 확정 결정

- `nodes-v1.0`의 35개 코드·가중치·29개 필수 간선과 `r2.0`은 사용자
  승인값으로 동결한다. 문서화되지 않은 AHP 행렬을 역산하지 않으며, 향후
  재보정은 새 노드 버전 제안으로만 수행한다.
- WP2.2-A에서는 실제 도달 조건, `Params.defaults()`, `Readiness`,
  `LogitEta`를 변경하지 않는다. 감사 전 P1 8개 노드도 모두 L3 이상
  부분점수가 있었고 준비도는 `0.3814`였다.
- `2175+`는 정보 없는 P1 노드가 아니라 희소한 계단형 상태, 10년 창 로그릿
  기울기와 150년 표시 상한의 결과다. 유한 ETA는 이번 완료 조건이 아니며
  계산 구조 변경은 WP4.2에서 다룬다.
- 후보 코퍼스는 노드별 6건 할당표가 아닌 노드 중립 사실 목록이다. 후보 하나가
  여러 노드 청구를 지지할 수 있고, 근거가 부족한 후보는 매핑하지 않는다.
  최종 기준은 후보 212, 청구 146, 고유 사용 후보 140, P1 청구 32다.
- Apollo 17은 중립 `PROGRAM_END` 전이가 없는 현행 어휘 때문에
  `PROGRAM_CANCELLATION`을 프로그램 종료 전이로 사용한다. 제목과 사실
  요약은 최종 달 착륙 임무의 완료로 기록하고, L8은 보존한 채 종료일
  `1972-12-19`와 휴면 시작일 `1987-12-19`만 설정한다.
- 감사 후 P1 상태는 `REUSE L5`, `REFUEL L8`, `DEEP-PROP L5 DORMANT`,
  `EDL L5`, `SURFACE-ASCENT L8 DORMANT`, `CREW-SAFE L8`,
  `ORBIT-LOGISTICS L8`, `TRANSPORT-INTEGRATION L3`이며 준비도는
  `0.4624`다. 휴면으로 점수가 낮아져도 사실 판정을 유지한다.
- ESA는 기존 Tier 1 `source_registry`와 CNP를 재사용한다. Flyway와
  egress 정책은 변경하지 않고, 백필 import는 로컬 classpath만 읽는다.
- 저작권·저장 위험을 제한하기 위해 HTTPS URL, locator, 접근일, SHA-256,
  직접 작성한 500자 이하 사실 요약만 저장한다. 원문·인용문·HTML·PDF·
  이미지·첨부·WARC·응답 본문과 바이너리 저장 열은 추가하지 않는다.
- `backfill-v1`은 G2 전 미출시 코퍼스이므로 같은 버전에 감사를 반영한다.
  이전 해시를 import한 개발 DB는 우회하지 않고 깨끗이 재시드하며, G2 승인
  뒤에는 v1을 불변으로 동결한다.
- WP2.2-A는 P1 1차분이다. P2~P6 현재상태·휴면 감사와 1957~현재 전 필라
  주간 스냅샷 완료 전에는 WP2.2와 G2를 닫지 않는다.

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans`로
> 아래 작업을 순서대로 실행한다. 사용자가 서브에이전트 사용을 금지했으므로
> `subagent-driven-development`는 사용하지 않는다. 각 단계는 체크박스로
> 진행상황을 기록한다.

**Goal:** `nodes-v1.0` 구조를 폐쇄 감사하고, P1의 누락된 역사 근거와 Apollo
휴면을 참조형 백필에 반영해 현재상태를 재현한다.

**Architecture:** 기존 35개 노드·`r2.0`·준비도/ETA 수학은 동결한다. 두 개의
공식 후보와 여섯 개의 P1 청구만 기존 `backfill-v1`에 추가하고, 생산
validator와 fresh-H2 재생 테스트가 개수·상태·휴면·준비도를 함께 잠근다.
데이터 검증과 실행 기록은 기존 문서·런북에 동기화한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, JdbcClient, Flyway V1~V8,
H2 Oracle mode, JSON/JSONL, PowerShell 5.1+, Git.

#### 전역 제약

- 작업 위치는 `.claude/worktrees/tracker-mvp`, 브랜치는
  `feat/tracker-mvp`이다.
- `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`은 사용자의 추적 제외 파일이므로 스테이징하지 않는다.
- `nodes-v1.0` 35개 코드·가중치·29개 간선, `r2.0`, 실제 도달 조건,
  `Params.defaults()`, `Readiness`, `LogitEta`를 변경하지 않는다.
- 최종 생산 데이터는 후보 212건, READY 212건, REJECTED 0건, 청구 146건,
  고유 사용 후보 140건, P1 청구 32건이다.
- 새 근거는 URL·locator·접근일·SHA-256·직접 작성한 사실 요약만 저장한다.
  원문, 인용문, HTML, PDF, 이미지, 첨부, WARC와 응답 본문은 저장하지 않는다.
- ESA는 기존 `source_registry`와 CNP를 재사용한다. Flyway와 네트워크 정책을
  변경하지 않으며 CI와 애플리케이션 import는 외부 사이트를 호출하지 않는다.
- 모든 Java 변경은 테스트를 먼저 실패시키고 최소 변경으로 통과시킨다.
- Maven은 캐시된 실행 파일을 `$Maven`에 해석한 뒤
  `-o '-Dmaven.repo.local=C:\Users\jang\.m2\repository'`로 실행한다.
- 작업별로 독립 검증 후 한 커밋을 만든다.

#### 파일 구조

- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`: WP2.1과
  WP2.2-A의 진행 요약·게이트 상태·이 상세 계획 링크만 기록한다.
- Maintain `docs/superpowers/plans/2026-07-14-tracker-phase2-wp21-wp22a-plan.md`:
  확정 결정, 단계별 실행 절차와 검증 증거를 보존한다.
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java`:
  생산 후보 정확 개수를 212로 고정한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`:
  기존 ESA 레지스트리 메타데이터를 검증 카탈로그에 노출한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`:
  Jules Verne와 Apollo 17 참조형 후보 두 행을 추가한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json`:
  `BF-P1-027`~`BF-P1-032` 여섯 청구를 추가한다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java`:
  212/212/0 생산 개수를 잠근다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java`:
  146개 청구와 140개 고유 후보를 잠근다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java`:
  ESA 카탈로그/DB 일치를 검증하되 역사 전용 egress 금지 집합은 유지한다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java`:
  생산 P1 레벨·휴면·`0.4624` 준비도·재수입 no-op을 검증한다.
- Modify `docs/research/tracker-backfill-mapping-review.md`: 146개 매핑과 P1
  감사 판정을 기록한다.
- Modify `docs/runbooks/tracker-reference-backfill-validation.md`: 최종 개수,
  파일 해시/크기, import 로그·SQL 기대값을 갱신한다.
- Append `.superpowers/sdd/progress.md`: 로컬 실행 증거를 기록하되 Git에는
  추가하지 않는다.

#### Task 1: WP2.1 구조 폐쇄 감사

**Files:**

- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`
- Append `.superpowers/sdd/progress.md` (Git 제외)
- Test `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`

**Interfaces:**

- Consumes: Flyway V6 `nodes-v1.0`, `r2.0` classifier resource와 기존
  `TrackerNodesV1Test` 13개 계약.
- Produces: WP2.1 구조 동결 실행 기록. WP2.2의 데이터 변경은 이 구조를
  입력으로만 사용한다.

- [x] **Step 1: 작업트리와 캐시 Maven을 확인한다**

```powershell
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp status --short --branch
$Maven = (Get-ChildItem -LiteralPath "$HOME\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction Stop |
  Sort-Object LastWriteTimeUtc -Descending |
  Select-Object -First 1).FullName
& $Maven -version
```

Expected: 브랜치 `feat/tracker-mvp`, 보호 대상 파일만 untracked, Maven 3.x와
프로젝트 `pom.xml` 기준 Java 21이 출력된다.

- [x] **Step 2: 기존 WP2.1 구조 계약을 fresh 실행한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test'
  if ($LASTEXITCODE -ne 0) { throw "TrackerNodesV1Test failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
```

Expected: `TrackerNodesV1Test` 13개, failures/errors 0. 실패하면 WP2.2로
넘어가지 않고 실제 구조 결함을 진단한다.

- [x] **Step 3: 마스터 계획에 폐쇄 감사 결과를 기록한다**

WP2.1의 2026-07-14 항목 뒤에 다음 문단을 추가한다.

```markdown
  - 2026-07-14 폐쇄 감사: `TrackerNodesV1Test`의 35개 코드·정확 가중치·
    필라별 합 1.0·통합 노드 6개·필수 간선 29개·`r2.0` registry/해시 계약이
    fresh 실행에서 모두 통과했다. `nodes-v1.0` 구조를 동결한다. P2~P6
    현재상태 감사와 G2는 계속 열어 둔다.
```

`.superpowers/sdd/progress.md`에도 동일 사실과 실행 명령을 한 줄로 남긴다.

- [x] **Step 4: 문서 diff를 검증하고 단독 커밋한다**

```powershell
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- docs/plans/multiplanetary-tracker-execution-plan.md
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "docs(tracker): record WP2.1 closure audit"
```

Expected: staged/committed 파일은 마스터 실행계획 하나뿐이다.

#### Task 2: P1 참조형 데이터와 생산 재생 회귀

**Files:**

- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java`

**Interfaces:**

- Consumes: `BackfillDatasetValidator.validate(Resource, Resource)`,
  `BackfillLoader.loadDatasetIfNeeded()`, `TrackerRepository.findAllNodes()`,
  `Readiness.pillarReadiness(List<NodeRow>, Params)`.
- Produces: 212개 후보/146개 청구 생산 리소스와 P1 현재상태
  `5,8,5D,5,8D,8,8,3`, 준비도 `0.4624`.

- [x] **Step 1: 생산 개수·ESA·P1 재생 기대값을 먼저 테스트에 쓴다**

`HistoricalProductionCorpusTest`의 두 기대값을 212로 바꾼다.

```java
assertEquals(212, report.totalCount());
assertEquals(212, report.readyCount());
assertEquals(0, report.rejectedCount());
```

`BackfillDatasetValidatorTest`의 생산 기대값을 다음처럼 바꾼다.

```java
assertEquals(146, result.claims().size());
assertEquals(140, result.candidates().size());
assertHasError(result, "production corpus must contain exactly 212 candidates");
assertHasError(result, "production corpus must contain exactly 212 READY candidates");
```

`TrackerHistoricalSourceTest`의 DB 조회 집합과 카탈로그 기대 집합에 ESA를
추가한다. 역사 전용 집합은 변경하지 않는다.

```java
private static final Set<String> HISTORICAL_ONLY =
        Set.of("FAA", "UNOOSA", "GOVINFO", "LSA");
private static final Set<String> CATALOG_CODES =
        Set.of("NASA", "ESA", "FAA", "UNOOSA", "GOVINFO", "LSA");

// sourceRegistryMatchesApprovedHistoricalCatalog() query
WHERE code IN ('NASA','ESA','FAA','UNOOSA','GOVINFO','LSA')

// readCatalog()
assertEquals(CATALOG_CODES, result.keySet());
```

`BackfillLoaderTest`에 다음 imports와 생산 재생 테스트/helper를 추가한다.

```java
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.Readiness;

@Test
void productionP1AuditReplaysApprovedLevelsDormancyAndReadiness() {
    BackfillLoader production = loader(
            new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
            new ClassPathResource("tracker/backfill-v1.json"),
            "backfill-v1");

    production.loadDatasetIfNeeded();

    assertNodeState("P1-REUSE-LV", 5, "ACTIVE");
    assertNodeState("P1-ORBIT-REFUEL", 8, "ACTIVE");
    assertNodeState("P1-DEEP-PROP", 5, "DORMANT");
    assertNodeState("P1-EDL-HEAVY", 5, "ACTIVE");
    assertNodeState("P1-SURFACE-ASCENT", 8, "DORMANT");
    assertNodeState("P1-CREW-SAFE", 8, "ACTIVE");
    assertNodeState("P1-ORBIT-LOGISTICS", 8, "ACTIVE");
    assertNodeState("P1-TRANSPORT-INTEGRATION", 3, "ACTIVE");

    var surfaceAscent = repository.findNodeByCode("P1-SURFACE-ASCENT");
    assertEquals(LocalDate.of(1972, 12, 19), surfaceAscent.programEndDate());
    assertEquals(LocalDate.of(1987, 12, 19), surfaceAscent.dormantSince());

    var p1Nodes = repository.findAllNodes().stream()
            .filter(node -> node.pillar() == 1)
            .toList();
    assertEquals(0.4624,
            Readiness.pillarReadiness(p1Nodes, Params.defaults()), 1e-9);
    assertEquals(146, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
            .query(Integer.class).single());
    assertEquals(146, jdbc.sql("""
            SELECT record_count FROM backfill_import
             WHERE dataset_version = 'backfill-v1'
            """).query(Integer.class).single());

    production.loadDatasetIfNeeded();

    assertEquals(146, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
            .query(Integer.class).single());
    assertEquals(1, jdbc.sql("""
            SELECT COUNT(*) FROM backfill_import
             WHERE dataset_version = 'backfill-v1'
            """).query(Integer.class).single());
}

private void assertNodeState(String code, int level, String status) {
    var node = repository.findNodeByCode(code);
    assertEquals(level, node.currentLevel(), code);
    assertEquals(status, node.nodeStatus(), code);
}
```

- [x] **Step 2: 새 테스트가 기존 데이터에서 실패하는지 확인한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=HistoricalProductionCorpusTest,BackfillDatasetValidatorTest,TrackerHistoricalSourceTest,BackfillLoaderTest'
  if ($LASTEXITCODE -eq 0) { throw 'Expected the pre-data audit tests to fail' }
}
finally {
  Pop-Location
}
```

Expected failures: 실제 210/140/136과 기대 212/146/140 불일치, ESA 카탈로그
누락, P1 refuel/logistics L5 및 surface-ascent ACTIVE와 새 기대 상태 불일치.

- [x] **Step 3: 공식 페이지 지문이 검토 시점 값과 일치하는지 확인한다**

```powershell
& scripts/backfill/Get-SourceFingerprint.ps1 -Uri 'https://www.esa.int/Science_Exploration/Human_and_Robotic_Exploration/ATV/Premiere_for_Europe_Jules_Verne_refuels_the_ISS'
& scripts/backfill/Get-SourceFingerprint.ps1 -Uri 'https://www.nasa.gov/mission/apollo-17/'
```

2026-07-14 검토값:

```text
ESA  f520cc250af221e2906e4d09ef40eb3e9283c9353af2871c3192425d3a6b0b31  20,989 bytes
NASA 16252989b63672c56a0ba6dc1c5bfa7465bc99e5444071514cb1215893243065 327,213 bytes
```

도구 출력에는 원문이 없어야 한다. 페이지가 갱신돼 지문만 달라졌다면 실제
페이지에서 같은 사실을 재확인하고 새 지문만 사용한다.

- [x] **Step 4: 생산 후보 정확 개수를 212로 올린다**

`BackfillDatasetValidator`에 상수를 추가하고 두 검사를 교체한다.

```java
private static final int PRODUCTION_CANDIDATE_COUNT = 212;

if (corpusReport.totalCount() != PRODUCTION_CANDIDATE_COUNT) {
    errors.add("candidates: production corpus must contain exactly "
            + PRODUCTION_CANDIDATE_COUNT + " candidates");
}
if (corpusReport.readyCount() != PRODUCTION_CANDIDATE_COUNT) {
    errors.add("candidates: production corpus must contain exactly "
            + PRODUCTION_CANDIDATE_COUNT + " READY candidates");
}
```

- [x] **Step 5: ESA 메타데이터와 후보 두 행을 추가한다**

`historical-source-catalog-v1.json`에서 NASA 다음에 다음 객체를 추가한다.

```json
{
  "sourceCode": "ESA",
  "name": "European Space Agency",
  "domain": "esa.int",
  "sourceType": "AGENCY",
  "tier": 1,
  "feedActive": true
}
```

`historical-candidates-v1.jsonl` 끝에 다음 두 JSONL 행을 추가한다.

```json
{"candidateId":"HC-2008-JULES-VERNE-ISS-REFUEL","eventTitle":"Jules Verne transferred mission propellant to the ISS","candidateTopics":["orbital refueling","automated propellant transfer","station logistics"],"actor":"ESA","occurredOn":"2008-06-17","occurredOnPrecision":"DAY","evidence":[{"sourceCode":"ESA","url":"https://www.esa.int/Science_Exploration/Human_and_Robotic_Exploration/ATV/Premiere_for_Europe_Jules_Verne_refuels_the_ISS","locator":"Article opening and Automatic sections","accessedOn":"2026-07-13","contentSha256":"f520cc250af221e2906e4d09ef40eb3e9283c9353af2871c3192425d3a6b0b31","publicationPath":"PRIMARY","factSummary":"ESA's Jules Verne ATV automatically transferred 811 kilograms of propellant through its docking interface into the ISS propulsion tanks."}],"discoveryStatus":"READY_FOR_MAPPING","discoveryNote":"Operational in-orbit transfer at useful quantity; it does not establish routine multi-client tanker or depot service."}
{"candidateId":"HC-1972-APOLLO17-FINAL-LUNAR-MISSION","eventTitle":"Apollo 17 completed the final Apollo lunar landing mission","candidateTopics":["lunar surface ascent","Apollo program lifecycle","crewed lunar return"],"actor":"NASA","occurredOn":"1972-12-19","occurredOnPrecision":"DAY","evidence":[{"sourceCode":"NASA","url":"https://www.nasa.gov/mission/apollo-17/","locator":"Mission summary and launch/splashdown facts","accessedOn":"2026-07-13","contentSha256":"16252989b63672c56a0ba6dc1c5bfa7465bc99e5444071514cb1215893243065","publicationPath":"PRIMARY","factSummary":"Apollo 17 was the Apollo program's final lunar landing mission and ended with splashdown on December 19, 1972."}],"discoveryStatus":"READY_FOR_MAPPING","discoveryNote":"Lifecycle evidence closes the Apollo lunar-ascent operating line while preserving its demonstrated level."}
```

- [x] **Step 6: P1 청구 여섯 건을 추가한다**

`backfill-v1.json`에서 `BF-P1-026` 뒤에 다음 객체를 삽입한다. 기존 행의
식별자나 순서는 바꾸지 않는다.

```json
{
  "backfillId": "BF-P1-027",
  "candidateId": "HC-1981-STS2-ORBITER-REFLIGHT",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-REUSE-LV",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 5,
  "actor": "NASA",
  "occurredOn": "1981-11-12",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Columbia completed a second orbital mission",
  "rubricJustification": "Orbiter reflight demonstrates partial-stack reuse in flight and remains capped at L5 because the launch architecture was not fully reusable.",
  "evidenceRefs": ["HC-1981-STS2-ORBITER-REFLIGHT#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Second orbiter flight supports capped partial-reuse credit only."}
},
{
  "backfillId": "BF-P1-028",
  "candidateId": "HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-REFUEL",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 7,
  "actor": "DARPA and industry partners",
  "occurredOn": "2007-01-01",
  "occurredOnPrecision": "YEAR",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Orbital Express transferred propellant and replaceable modules in orbit",
  "rubricJustification": "Autonomous in-orbit fluid transfer exceeded a relevant-environment prototype but did not establish operational mission refueling or recurring service.",
  "evidenceRefs": ["HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Fluid-transfer loop supports L7 without routine-service credit."}
},
{
  "backfillId": "BF-P1-029",
  "candidateId": "HC-2012-DRAGON-ISS-CARGO-BERTHING",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-LOGISTICS",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 7,
  "actor": "NASA and SpaceX",
  "occurredOn": "2012-05-25",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Dragon completed the first commercial cargo berthing at the ISS",
  "rubricJustification": "The demonstration completed orbital cargo rendezvous, berthing, and station handoff but preceded routine operational service.",
  "evidenceRefs": ["HC-2012-DRAGON-ISS-CARGO-BERTHING#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Cargo handoff supports L7 demonstration credit."}
},
{
  "backfillId": "BF-P1-030",
  "candidateId": "HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-LOGISTICS",
  "eventType": "OPERATIONAL_DEPLOYMENT",
  "claimedLevel": 8,
  "actor": "Orbital Sciences and NASA",
  "occurredOn": "2014-01-12",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "A second commercial provider began operational station cargo delivery",
  "rubricJustification": "An operational resupply mission completed accountable cargo delivery and later waste handoff through an established station interface.",
  "evidenceRefs": ["HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Operational cargo delivery satisfies the L8 logistics anchor."}
},
{
  "backfillId": "BF-P1-031",
  "candidateId": "HC-2008-JULES-VERNE-ISS-REFUEL",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-REFUEL",
  "eventType": "OPERATIONAL_DEPLOYMENT",
  "claimedLevel": 8,
  "actor": "ESA",
  "occurredOn": "2008-06-17",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Jules Verne transferred mission propellant to the ISS",
  "rubricJustification": "Automatic transfer of 811 kilograms into the ISS propulsion system satisfies operational refueling at useful quantity without proving routine multi-client service.",
  "evidenceRefs": ["HC-2008-JULES-VERNE-ISS-REFUEL#ESA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Useful-quantity operational transfer supports L8, not L9."}
},
{
  "backfillId": "BF-P1-032",
  "candidateId": "HC-1972-APOLLO17-FINAL-LUNAR-MISSION",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-SURFACE-ASCENT",
  "eventType": "PROGRAM_CANCELLATION",
  "claimedLevel": null,
  "actor": "NASA",
  "occurredOn": "1972-12-19",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Apollo 17 completed the final Apollo lunar landing mission",
  "rubricJustification": "The final Apollo lunar landing mission is the supported program-end transition for the demonstrated lunar surface-ascent line; it preserves L8 and starts dormancy.",
  "evidenceRefs": ["HC-1972-APOLLO17-FINAL-LUNAR-MISSION#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Program completion records lifecycle end without reducing the demonstrated level."}
}
```

- [x] **Step 7: 집중 테스트와 정적 코퍼스 검증을 통과시킨다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test,HistoricalProductionCorpusTest,BackfillDatasetValidatorTest,TrackerHistoricalSourceTest,BackfillLoaderTest'
  if ($LASTEXITCODE -ne 0) { throw "Focused Phase 2 tests failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
& scripts/backfill/Test-HistoricalCorpus.ps1 -MavenPath $Maven
```

Expected: 선택한 모든 테스트 failures/errors 0, 코퍼스 리포트
`total=212 ready=212 rejected=0`.

- [x] **Step 8: 데이터·코드·테스트만 커밋한다**

```powershell
$stageFiles = @(
  'apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java',
  'apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json',
  'apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl',
  'apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java'
)
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- $stageFiles
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "data(tracker): audit P1 historical state"
```

Expected: 보호 대상 로컬 fixture와 문서는 이 커밋에 포함되지 않는다.

#### Task 3: 감사 문서 동기화와 전체 검증

**Files:**

- Modify `docs/research/tracker-backfill-mapping-review.md`
- Modify `docs/runbooks/tracker-reference-backfill-validation.md`
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`
- Append `.superpowers/sdd/progress.md` (Git 제외)

**Interfaces:**

- Consumes: Task 2의 검증된 212개 후보, 146개 청구, fresh-H2 P1 상태.
- Produces: 재현 가능한 릴리스 전 검증 절차와 WP2.2-A 실행 기록.

- [x] **Step 1: 최종 개수·분포·파일 크기·SHA-256을 산출한다**

```powershell
$candidatePath = 'apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl'
$mappingPath = 'apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json'
$claims = Get-Content -Raw -Encoding UTF8 -LiteralPath $mappingPath | ConvertFrom-Json
$candidates = Get-Content -Encoding UTF8 -LiteralPath $candidatePath
[pscustomobject]@{
  candidates = $candidates.Count
  claims = $claims.Count
  usedCandidates = ($claims.candidateId | Sort-Object -Unique).Count
  advancing = @($claims | Where-Object eventType -NotIn @(
    'SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE')).Count
  nonAdvancing = @($claims | Where-Object eventType -In @(
    'SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE')).Count
  candidateBytes = (Get-Item -LiteralPath $candidatePath).Length
  candidateSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidatePath).Hash.ToLowerInvariant()
  mappingBytes = (Get-Item -LiteralPath $mappingPath).Length
  mappingSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $mappingPath).Hash.ToLowerInvariant()
}
$claims | Group-Object { $_.nodeCode.Substring(0, 2) } |
  Sort-Object Name | Select-Object Name, Count
```

Expected structural values: candidates 212, claims 146, usedCandidates 140,
advancing 114, nonAdvancing 32, pillar counts P1/P2/P3/P4/P5/P6 =
32/21/24/21/23/25.

- [x] **Step 2: 매핑 검토 문서를 현재 데이터로 교체한다**

`tracker-backfill-mapping-review.md`의 머리말·분포·P1 표·검증 체크포인트를
다음 값으로 갱신한다.

```markdown
- 검토일: 2026-07-14
- 입력 코퍼스: 212건
- 생산 매핑: 146개 청구
- 고유 사용 후보: 140건
- 미선정 후보: 72건
- 출처 코드: NASA, ESA, FAA, UNOOSA, GOVINFO, LSA
- 검증 기대값: 146건 모두 OFFICIAL

| 기둥 | 청구 수 |
|---|---:|
| P1 | 32 |
| P2 | 21 |
| P3 | 24 |
| P4 | 21 |
| P5 | 23 |
| P6 | 25 |
| 합계 | 146 |

| P1 노드 | 전체 | 진척 | 현재상태 | 준비도 기여 |
|---|---:|---:|---|---:|
| P1-REUSE-LV | 5 | 4 | L5 ACTIVE | 0.0540 |
| P1-ORBIT-REFUEL | 5 | 3 | L8 ACTIVE | 0.1360 |
| P1-DEEP-PROP | 4 | 1 | L5 DORMANT | 0.0144 |
| P1-EDL-HEAVY | 5 | 4 | L5 ACTIVE | 0.0420 |
| P1-SURFACE-ASCENT | 3 | 2 | L8 DORMANT | 0.0340 |
| P1-CREW-SAFE | 5 | 4 | L8 ACTIVE | 0.1020 |
| P1-ORBIT-LOGISTICS | 4 | 4 | L8 ACTIVE | 0.0680 |
| P1-TRANSPORT-INTEGRATION | 1 | 1 | L3 ACTIVE | 0.0120 |
| 합계 | 32 | 23 |  | 0.4624 |
```

기존 120건·117건·필라별 20건·P1 L0 설명은 삭제한다. P2~P6 현재상태
감사는 후속 WP2.2 작업임을 명시한다.

- [x] **Step 3: 롤아웃 런북을 검증된 데이터로 갱신한다**

`tracker-reference-backfill-validation.md`에서 210/120/117과 기존 파일
해시·크기를 Step 1의 값으로 교체한다. importer 계약은 212/212/0 및
110~150으로, 로그 기대값과 audit `record_count`/승인 참조는 146으로,
고유 후보는 140으로 바꾼다. ESA는 기존 실시간 feed/CNP를 재사용하고
추가 egress가 없다고 기록한다. 전체 저장 추정치는 실제 두 리소스 합계와
146행 기준으로 다시 계산하며 1 MiB 미만 조건을 유지한다.

- [x] **Step 4: 전체 백엔드와 정책 검증을 fresh 실행한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
  if ($LASTEXITCODE -ne 0) { throw "Full backend regression failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
& 'gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1'
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
```

Expected: backend failures/errors 0, egress verifier PASS, diff check exit 0.
프런트 계약 변경이 없으므로 프런트 소스·테스트·빌드는 이 배치에서 건드리지
않는다.

- [x] **Step 5: 실행계획과 진행 원장에 실제 검증 결과를 기록한다**

WP2.2-A 항목 끝에 후보/청구/상태/준비도와 Step 4의 실제 테스트 수를
기록한다. WP2.1/WP2.2/G2 상위 체크박스는 P2~P6 감사·골든셋·관제 완료
전까지 열어 둔다. `.superpowers/sdd/progress.md`에는 동일 실행 증거와
다음 단계 `WP2.2-B P2~P6 현재상태 감사`를 기록한다.

- [x] **Step 6: 문서만 단독 커밋한다**

```powershell
$docFiles = @(
  'docs/research/tracker-backfill-mapping-review.md',
  'docs/runbooks/tracker-reference-backfill-validation.md',
  'docs/plans/multiplanetary-tracker-execution-plan.md'
)
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- $docFiles
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "docs(tracker): record Phase 2 P1 audit"
```

Expected: 세 문서만 커밋되고 `.superpowers/sdd/progress.md`와 로컬 fixture는
스테이징되지 않는다.
