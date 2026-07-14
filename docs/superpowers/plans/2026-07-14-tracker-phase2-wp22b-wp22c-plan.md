# Tracker Phase 2 WP2.2-B/WP2.2-C 상세 구현 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 승인 설계: [2026-07-14-tracker-phase2-design.md](../specs/2026-07-14-tracker-phase2-design.md)
>
> 상태: 2026-07-14 완료 — Task 1~7 및 WP2.2 종료 검증 완료

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans로 아래 작업을 순서대로 실행한다. 사용자가 서브에이전트 사용을 금지했으므로 subagent-driven-development는 사용하지 않는다. 버그나 예상 밖 실패가 나오면 systematic-debugging을 먼저 적용한다.

**Goal:** P2~P6 27개 노드를 공식 1차 근거로 감사하고, 35개 전체 노드의 감사 coverage를 확정한 뒤 1957년부터 현재까지 연속 주간 스냅샷을 재현한다.

**Architecture:** 기존 참조형 JSONL/JSON 코퍼스와 BackfillLoader를 유지한다. 청구 수 목표를 제거하고 저장 안전 상한·프로그램 종료 의미는 `BackfillDatasetValidator`에, 35개 노드 coverage와 청구·증거·재생 일치는 별도 `BackfillAuditValidator`에 둔다. 역사 상태 재생은 WeeklyBackfillProjector로 분리해 UTC 월요일 스냅샷을 멱등 생성한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, JdbcClient, Flyway, H2 Oracle mode, Jackson, JSON/JSONL, PowerShell, Git.

## 전역 제약

- 작업 위치는 .claude/worktrees/tracker-mvp, 브랜치는 feat/tracker-mvp다.
- .claude/, application-demo.yml, application-refbackfill.yml, backfill-demo.json은 사용자 파일이므로 스테이징하지 않는다.
- nodes-v1.0, r2.0, Params.defaults(), Readiness, LogitEta와 실제 도달 조건은 변경하지 않는다.
- readiness, ETA, L0 개수, 청구 개수를 보고 근거를 선택하지 않는다.
- P4 RESOURCE-INTEGRATION과 P6 SETTLEMENT-INTEGRATION은 실제 직접 통합 증거가 없으면 L0을 유지한다.
- 공식 페이지를 조사할 때 저장하는 값은 HTTPS URL, locator, 접근일, SHA-256, 500자 이하 직접 작성 factSummary뿐이다.
- 웹 본문·인용문·HTML·PDF·이미지·WARC를 저장하지 않는다.
- 테스트와 import는 네트워크를 호출하지 않는다.
- 새 source domain이 필요하면 source registry와 CNP를 함께 검토한다. 가능하면 기존 NASA/ESA/FAA 등 등록 source를 재사용한다.
- 모든 Java 변경은 실패 테스트를 먼저 만든다.
- 각 Task는 focused test, 관련 package test, 전체 backend test 순으로 검증하고 독립 커밋한다.

## 파일 구조

### 주요 수정

- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillClaim.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ingest/BackfillLoader.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java
- apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl
- apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json
- apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java
- apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java
- apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java

### 신규

- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/ProgramEndEffect.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillAuditValidator.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/NodeAuditApproval.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/NodeAuditEntry.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/ValidatedNodeAudit.java
- apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/WeeklyBackfillProjector.java
- apps/backend/springboot-app/src/main/resources/tracker/backfill-audit-v1.json
- apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillAuditValidatorTest.java
- apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/WeeklyBackfillProjectorTest.java
- docs/research/tracker-phase2-p2-p6-audit.md
- docs/runbooks/tracker-weekly-backfill-validation.md

## Task 1: 깨끗한 기준선과 감사 전 상태 고정

**Tests:** 전체 backend, backfill focused suite

- [x] 워크트리 상태를 기록하고 추적 제외 파일이 스테이지되지 않았음을 확인한다.
- [x] 캐시된 mvn.cmd를 찾고 오프라인 전체 테스트를 실행한다.
- [x] 현재 backfill validator, loader, production corpus focused tests를 실행한다.
- [x] 현재 P2~P6 replay level, node status, program_end_date, pillar readiness를 테스트 출력 또는 읽기 전용 검사로 기록한다.
- [x] 현재 4개 L0 노드와 P6-FUNDING의 VIPER 종료 효과를 red-test 입력으로 고정한다.

검증 명령:

- Maven wrapper: 사용자 홈 .m2/wrapper/dists 아래 mvn.cmd
- Backend: mvn.cmd -o -Dmaven.repo.local=C:\Users\jang\.m2\repository test
- Focused: -Dtest=BackfillDatasetValidatorTest,HistoricalProductionCorpusTest,BackfillLoaderTest test

예상: 기준선 green. 실패하면 데이터 변경 전에 systematic-debugging으로 원인을 확정한다.

## Task 2: validator의 굿하트 방지·programEndEffect 계약

**Files:**

- Modify BackfillClaim.java
- Create ProgramEndEffect.java
- Modify BackfillDatasetValidator.java
- Modify BackfillDatasetValidatorTest.java
- Modify BackfillLoaderTest.java

- [x] 먼저 다음 실패 테스트를 추가한다.
  - production mapping이 150건을 넘어도 안전 상한 이하면 수량만으로 실패하지 않는다.
  - 후보 2 MiB 초과, mapping 1 MiB 초과, 항목 512건 초과가 실패한다.
  - factSummary 500자 초과, reviewerNote 2,000자 초과가 실패한다.
  - 동일 candidate/node/event/date/level 중복이 실패한다.
  - 한 노드 상태 변경 청구가 32건을 넘으면 실패한다.
  - 별도 audit resource의 coverage가 35개 node code보다 적으면 실패한다.
  - programEndEffect 생략은 NONE이다.
  - CAPABILITY_PROGRAM_END는 PROGRAM_CANCELLATION, OFFICIAL 이상, 비어 있지 않은 범위 메모를 요구한다.
- [x] focused tests를 실행해 예상 이유로 RED인지 확인한다.
- [x] ProgramEndEffect enum과 BackfillClaim 필드를 추가한다.
- [x] MAPPING_FIELDS와 materialization을 확장한다.
- [x] 정확한 후보·mapping 수량 계약을 제거하고 바이트·건수 안전 상한을 구현한다.
- [x] 별도 audit resource validator로 35개 coverage와 replay 일치를 구현한다.
- [x] rejected 후보 금지, node/rubric/source/리뷰 무결성은 유지한다.
- [x] 실패 메시지에 비밀·본문을 포함하지 않는다.
- [x] focused tests를 GREEN으로 만든다.
- [x] 커밋: feat(tracker): harden Phase 2 backfill contract (`9c82199`)

## Task 3: WP2.2-B1 P2·P3 공식 근거 전수 감사

**Files:**

- Modify historical-candidates-v1.jsonl
- Modify backfill-v1.json
- Modify HistoricalProductionCorpusTest.java
- Modify BackfillDatasetValidatorTest.java
- Modify BackfillLoaderTest.java
- Create docs/research/tracker-phase2-p2-p6-audit.md

- [x] 데이터 변경 전에 P2·P3 13개 노드 audit coverage와 현재 level을 검증하는 실패 테스트를 추가한다.
- [x] HEALTH-AUTONOMY와 THERMAL이 무조건 L0이어야 한다는 기대는 두지 않는다. 실제 rubric 조건과 근거가 결정하게 한다.
- [x] 기존 후보 중 관련 후보를 먼저 목록화하고, 각 후보가 실제 도달 조건을 직접 지지하는지 확인한다.
- [x] 인터넷 조사는 공식 1차 자료로 제한한다. NASA, ESA, 임무·프로그램 공식 페이지와 공식 기술 보고서를 우선한다.
- [x] 각 페이지에서 URL, locator, accessedOn, transient response SHA-256만 수집하고 factSummary를 독자적으로 작성한다. 응답 본문은 파일이나 DB에 저장하지 않는다.
- [x] P2 7개 노드를 각각 판정한다.
  - ECLSS
  - FOOD
  - HEALTH-AUTONOMY
  - MED
  - RAD
  - SURVIVAL-INTEGRATION
  - WASTE-CYCLE
- [x] P3 6개 노드를 각각 판정한다.
  - COMMS
  - CONSTRUCT
  - DUST
  - HABITAT-INTEGRATION
  - POWER
  - THERMAL
- [x] 각 노드에 현재 level 근거, next-level gap, status, 감사 메모를 기록한다.
- [x] 상태 청구는 두 독립 승인 필드를 유지하고, 단순 배경 자료는 매핑하지 않는다.
- [x] focused corpus/validator/loader tests를 GREEN으로 만든다.
- [x] 실제 결과의 claim/candidate 수와 readiness는 결과로 기록하되 목표로 사용하지 않는다.
- [x] 커밋: data(tracker): audit Phase 2 survival and habitat nodes (`7c9dd0c`에 통합)

## Task 4: WP2.2-B2 P4~P6 공식 근거 전수 감사

**Files:** Task 3의 data/test/research 파일

- [x] P4~P6 14개 노드의 coverage와 현재 level을 검증하는 실패 테스트를 추가한다.
- [x] P4 5개 노드 ISRU, MANUFACTURING, MATERIALS, NUKE, RESOURCE-INTEGRATION을 감사한다.
- [x] P5 4개 노드 AUTOCON, AUTONOMY, MAINTENANCE, OPS-INTEGRATION을 감사한다.
- [x] P6 5개 노드 FUNDING, GOV, INSURANCE, LAUNCH-MARKET, SETTLEMENT-INTEGRATION을 감사한다.
- [x] RESOURCE-INTEGRATION과 SETTLEMENT-INTEGRATION은 직접 통합 운영 증거가 없으면 L0을 유지하고 next-level gap을 명시한다.
- [x] VIPER 취소가 역량 전체 종료를 뜻하는지 공식 범위 문구로 검토했다. 개별 임무 취소이므로 programEndEffect=NONE을 유지하고 P6-FUNDING의 node-wide dormancy를 해제했다.
- [x] 실제 대표 프로그램 계보 종료에만 CAPABILITY_PROGRAM_END를 사용한다.
- [x] 기존 unused 후보를 우선 사용하고, 새 공식 자료가 필요한 경우 Task 3의 저작권 안전 절차를 따랐다.
- [x] 35/35 audit coverage, 중복 0, 검증 오류 0을 확인한다.
- [x] 전체 현재 상태와 readiness를 research 문서에 기록한다.
- [x] focused tests를 GREEN으로 만든다.
- [x] 커밋: data(tracker): complete Phase 2 node audit (`7c9dd0c`)

## Task 5: BackfillLoader의 프로그램 종료 의미 수정

**Files:**

- Modify BackfillLoader.java
- Modify BackfillLoaderTest.java

- [x] 다음 실패 테스트를 추가한다.
  - PROGRAM_CANCELLATION + NONE은 program_end_date를 설정하지 않는다.
  - CAPABILITY_PROGRAM_END만 종료일과 휴면 경계를 설정한다.
  - replay와 실제 import가 같은 규칙을 사용한다.
  - 과거 종료 뒤 새 진전 사건이 오면 종료·휴면이 해제된다.
- [x] applyStateTransition과 replayTransition이 ProgramEndEffect를 사용하도록 최소 수정한다.
- [x] event type 자체는 보존해 타임라인 의미를 잃지 않는다.
- [x] P1 Apollo와 NERVA 종료 계약에 명시적으로 CAPABILITY_PROGRAM_END를 부여한다.
- [x] focused tests와 전체 backfill suite를 GREEN으로 만든다.
- [x] 커밋: fix(tracker): scope historical program endings (`7c9dd0c`에 통합)

## Task 6: WeeklyBackfillProjector TDD

**Files:**

- Create WeeklyBackfillProjector.java
- Create WeeklyBackfillProjectorTest.java
- Modify BackfillLoader.java
- Modify TrackerRepository.java
- Modify BackfillLoaderTest.java

- [x] Clock이 2026-07-14로 고정된 실패 테스트를 작성한다.
  - 첫 snapshot date는 1957-01-07이다.
  - 모든 역사 snapshot date는 월요일이다.
  - 직전 완료 주까지 주 수 × 6개의 연속 행이 있다.
  - 사건 전 주 readiness는 0이다.
  - 사건 당주, rollback, program end, 15년 dormancy 경계가 정확하다.
  - 마지막 replay state와 fresh DB의 35개 current node state가 일치한다.
  - 같은 dataset/projector version을 두 번 실행하면 행 수와 사건 수가 같다.
  - projector hash/version 변경은 주간 행을 원자적으로 재생성한다.
  - 기존 연말 비월요일 snapshot이 남지 않는다.
- [x] RED가 연말 snapshot 구현 때문에 발생함을 확인한다.
- [x] WeeklyBackfillProjector를 BackfillLoader의 연말 계산에서 분리한다.
- [x] 주입 Clock, first Monday, previous completed Monday 계산을 구현한다.
- [x] TrackerRepository에 bounded range delete, insert/compare, marker read/write 메서드를 추가한다.
- [x] 같은 월요일의 운영 snapshot이 있으면 readiness/logit이 같을 때 보존하고 다르면 전체 실패시킨다.
- [x] marker에는 dataset SHA-256, node set, rubric, projector version을 포함한다.
- [x] importer가 이미 기록된 dataset에서도 projector marker가 없거나 오래되면 projection만 수행하게 한다.
- [x] transaction 실패 시 기존 역사 snapshot이 유지되는지 검증한다.
- [x] focused tests를 GREEN으로 만든다.
- [x] 커밋: feat(tracker): project weekly history since 1957 (`716c2c3`)

## Task 7: WP2.2 증거·문서·전체 검증

**Files:**

- Create docs/runbooks/tracker-weekly-backfill-validation.md
- Modify docs/research/tracker-phase2-p2-p6-audit.md
- Modify docs/plans/multiplanetary-tracker-execution-plan.md
- Append .superpowers/sdd/progress.md (Git 제외)

- [x] research 문서에 35/35 node matrix, 근거 ref, next-level gap, 실제 상태와 readiness를 기록한다.
- [x] runbook에 fresh H2/ATP-safe 검증 SQL, 예상 주 수 공식, 월요일 cadence, idempotency, hash를 기록한다.
- [x] 저장 파일 크기와 DB 예상 행 수를 기록해 위험이 낮음을 증명한다.
- [x] 마스터 WP2.2에는 완료 요약과 이 상세 계획 링크만 기록한다.
- [x] focused backfill tests를 fresh 실행한다(45/45).
- [x] backend 전체 테스트를 fresh 실행한다(295/295).
- [x] GitOps egress 테스트를 실행해 새 domain/egress가 없음을 확인한다.
- [x] git diff --check와 staged file allowlist를 확인한다.
- [x] 커밋: docs(tracker): close historical backfill (본 문서 커밋)

## WP2.2 완료 체크

- [x] P2~P6 27개와 전체 35개 노드 감사 완료
- [x] 부분 근거를 실제 level로 인정하고 목표 ETA 최적화 없음
- [x] node-wide 프로그램 종료 오판 제거
- [x] 1957-01-07부터 직전 완료 주까지 6개 필라 연속
- [x] 마지막 replay와 현재 상태 일치
- [x] 원문 저장 0, 파일 안전 상한 통과
- [x] 전체 backend와 egress 회귀 green
