# Tracker Phase 2 WP2.3/WP2.4 상세 구현 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 승인 설계: [2026-07-14-tracker-phase2-design.md](../specs/2026-07-14-tracker-phase2-design.md)
>
> 선행 조건: WP2.2 완료
>
> 상태: 실행 대기

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. 서브에이전트는 사용하지 않는다. 테스트 실패 또는 예상 밖 동작은 systematic-debugging으로 원인을 확인한 뒤 수정한다.

**Goal:** 저작권 안전 합성 골든셋 약 50건, 결정론적 회귀 평가, 주간 LIVE 평가 경로, 관리도·데드맨·자동 상태 동결·인간 해제와 모의 drift 훈련을 구현한다.

**Architecture:** 기존 golden_set_item과 ops_state를 V9로 확장한다. 평가기는 GoldenClassifier 포트 뒤에서 OFFLINE_REPLAY, LIVE_MODEL, DRILL을 구분한다. PipelineMonitorJob은 DB 집계만 사용하며 외부 알림 egress를 만들지 않는다. StateFreezeService가 모든 동결·해제와 audit log를 단일화한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway V9, JUnit 5, Jackson, ShedLock, H2 Oracle mode.

## 전역 제약

- API 키가 없는 현재 환경에서 네트워크를 호출하지 않는다.
- OFFLINE_REPLAY 또는 DRILL 결과를 실제 모델 합의율로 표시하지 않는다.
- LIVE_MODEL 자동 실행은 tracker.golden-live-enabled=true이고 API 설정이 유효할 때만 가능하다.
- 실제 키는 Git에 기록하지 않고 OCI Vault + ESO만 사용한다.
- 골든 입력은 SYNTHETIC 또는 HUMAN_PARAPHRASE뿐이며 외부 원문·긴 인용을 저장하지 않는다.
- 골든 결과는 canonical hash, mismatch field, error code만 저장한다. 원시 모델 응답은 저장하지 않는다.
- 별도 pod/CronJob/queue/vector DB를 추가하지 않는다.
- 상태 동결 중 수집·분류는 계속되지만 node state 변경은 금지한다.
- 자동 해제는 금지하고 관리자 이유가 있는 명시적 해제만 허용한다.

## 파일 구조

### Migration/schema

- Create apps/backend/springboot-app/src/main/resources/db/migration/V9__tracker_phase2_quality_ops.sql
- Create apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerPhase2QualitySchemaTest.java

### Golden set

- Create apps/backend/springboot-app/src/main/resources/tracker/golden-set-v1.json
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenSetDatasetValidator.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenSetLoader.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenClassifier.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenInput.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenOutput.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenSetEvaluator.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/GoldenSetJob.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/evaluate/DeepClassifierGoldenAdapter.java
- Create corresponding tests under src/test/java/com/aienterprise/backend/tracker/evaluate

### Operations

- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ops/StateFreezeService.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ops/ControlChart.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ops/DeadmanMonitor.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/ops/PipelineMonitorJob.java
- Create records under apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain for golden runs, metrics and ops actions
- Modify TrackerRepository.java
- Modify StateUpdater.java
- Modify SnapshotJob.java
- Create corresponding tests under tracker/ops and extend StateUpdaterTest/SnapshotJobTest

## Task 1: V9 품질·관제 스키마

**Schema contract:**

- golden_set_item에 case_code, fixture_kind, expected_schema_version, rubric_version_id, dataset_version, provenance_refs, input_sha256, active, updated_at 추가
- golden_set_dataset: dataset_version, dataset_sha256, item_count, loaded_at
- golden_set_run: mode/version tuple/status/count/agreement/failure/timestamps
- golden_set_result: run/item unique, actual_output_sha256, matched, mismatch_fields, error_code
- pipeline_metric_daily: metric_date/code/value/baseline/bounds/violation/consecutive count
- ops_action_log: action, reason, trigger, previous/new state, created_at

- [ ] 먼저 schema test를 작성해 새 table, column, FK, unique, enum check와 인덱스를 요구한다.
- [ ] body column comment가 외부 기사 스냅샷이 아니라 합성·재서술 입력임을 요구한다.
- [ ] golden result에 source body 또는 raw model output 열이 없음을 검사한다.
- [ ] test를 실행해 V9 부재로 RED인지 확인한다.
- [ ] Oracle과 H2 Oracle mode에서 호환되는 V9 migration을 작성한다.
- [ ] 기존 비어 있는 golden_set_item을 안전하게 확장하고 모든 새 식별자를 bounded varchar로 둔다.
- [ ] schema test와 전체 migration tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): add Phase 2 quality schema

## Task 2: 저작권 안전 골든셋 v1

**Dataset contract:**

- 정확히 50개 active case
- fixtureKind는 SYNTHETIC 또는 HUMAN_PARAPHRASE
- title 최대 300자, body 최대 2,000 UTF-8 bytes
- expectedOutput 최대 2,000 bytes, notes 최대 1,000자
- 전체 파일 256 KiB 이하
- URL, HTML, PDF, quote/excerpt/sourceBody 필드 금지
- expected output은 canonical JSON schema version으로 검증

- [ ] GoldenSetDatasetValidatorTest에 파일·필드·크기·금지 키·중복 case code 실패 테스트를 작성한다.
- [ ] 6개 필라, state/non-state/rollback/cancellation/arrival, 관련 없음, quote 검증 실패, level 경계, duplicate/non-duplicate 표현쌍 분포를 검사하는 테스트를 작성한다.
- [ ] RED를 확인한다.
- [ ] golden-set-v1.json에 직접 작성한 50개 짧은 합성·재서술 case를 추가한다.
- [ ] 기존 승인 historical claim ID만 provenance ref로 사용하고 외부 문장을 복제하지 않는다.
- [ ] validator를 구현해 schema와 UTF-8 byte 상한을 강제한다.
- [ ] GoldenSetLoader를 구현해 dataset hash가 같은 재실행을 no-op으로 만든다.
- [ ] dataset 변경은 새 version을 요구하고 기존 version/hash 충돌을 실패시킨다.
- [ ] fixture 파일 크기와 case별 최대 크기를 테스트 출력에 기록한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: data(tracker): add copyright-safe golden set

## Task 3: GoldenClassifier 포트와 canonical diff

**Files:** GoldenInput, GoldenOutput, GoldenClassifier, GoldenSetEvaluator와 tests

- [ ] 다음 실패 테스트를 먼저 작성한다.
  - JSON field order 차이는 합의에 영향이 없다.
  - relevant, nodeCode, eventType, claimedLevel, actor, occurredOn, publicationPath의 차이를 field별로 보고한다.
  - quote/schema 검증 실패는 case 실패다.
  - actual raw output을 DB나 log에 저장하지 않는다.
  - 45/50은 0.90, 44/50은 0.88로 계산한다.
  - case 예외가 나도 나머지 case를 평가하고 run status는 FAILED다.
  - run mode가 결과와 report에 보존된다.
- [ ] RED를 확인한다.
- [ ] GoldenOutput canonicalizer와 field diff를 구현한다.
- [ ] evaluator가 run/result를 한정된 레코드로 저장하게 repository를 확장한다.
- [ ] OFFLINE_REPLAY용 fake classifier는 test source에만 둔다.
- [ ] 실제 model adapter가 기존 DeepClassifier의 structured output 검증을 재사용하도록 최소 refactor한다.
- [ ] DeepClassifier 기존 tests를 그대로 GREEN으로 유지한다.
- [ ] 커밋: feat(tracker): evaluate golden agreement

## Task 4: 주간 골든 평가와 활성화 경계

**Files:** GoldenSetJob, DeepClassifierGoldenAdapter, TrackerConfig/application config, tests

- [ ] scheduler가 기본 일요일 UTC cadence와 ShedLock을 가지는지 실패 테스트를 작성한다.
- [ ] tracker.golden-live-enabled=false에서 AnthropicClient를 호출하지 않는 테스트를 작성한다.
- [ ] LIVE_MODEL만 GOLDEN_LAST_LIVE_SUCCESS와 GOLDEN_LIVE_ACTIVATED를 갱신하는 테스트를 작성한다.
- [ ] OFFLINE_REPLAY와 DRILL이 운영 baseline을 갱신하지 않는 테스트를 작성한다.
- [ ] prompt/model/rubric/schema tuple 변경 시 새 baseline이 필요함을 테스트한다.
- [ ] RED를 확인한다.
- [ ] GoldenSetJob을 구현하고 batch limit 60을 강제한다.
- [ ] 설정이 꺼졌을 때 SKIPPED 상태 또는 명시적 ops status만 기록하고 네트워크를 호출하지 않는다.
- [ ] API 활성화 방법은 런북으로 미루고 평문 secret을 추가하지 않는다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): schedule versioned golden runs

## Task 5: StateFreezeService와 상태 쓰기 차단

**Files:** StateFreezeService, TrackerRepository, StateUpdater, SnapshotJob와 tests

- [ ] 다음 실패 테스트를 먼저 작성한다.
  - STATE_FROZEN=true이면 자동 processEvent가 node와 history를 바꾸지 않는다.
  - frozen event에 CIRCUIT_BREAKER review가 정확히 하나 생긴다.
  - frozen 상태의 관리자 approve는 review를 resolve하지 않는다.
  - ops_state 조회 예외는 fail closed다.
  - freeze가 여러 번 호출돼도 최초 시각과 audit 의미가 일관된다.
  - release는 nonblank reason과 human trigger를 요구한다.
  - release 뒤 pending event가 정확히 한 번 처리된다.
  - SnapshotJob은 frozen 중 LAST_DISPLAYED_ETA를 유지한다.
- [ ] RED를 확인한다.
- [ ] StateFreezeService에 isFrozen, freeze, release를 구현한다.
- [ ] freeze/release와 ops_action_log 쓰기를 transaction으로 묶는다.
- [ ] StateUpdater가 scoring 및 최종 advance 직전에 동결을 재검사하도록 한다.
- [ ] approve는 resolveReview보다 먼저 동결을 확인한다.
- [ ] SnapshotJob의 displayed ETA 경로를 frozen-aware로 만든다.
- [ ] 기존 review uniqueness와 idempotency를 유지한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): freeze unsafe state transitions

## Task 6: 관리도

**Files:** ControlChart, PipelineMonitorJob, TrackerRepository와 tests

- [ ] pure ControlChart 실패 테스트를 먼저 작성한다.
  - 최근 28일 중 populated 14일 미만이면 INSUFFICIENT_DATA
  - mean ± 3 sigma 경계 안은 정상
  - 첫 위반은 경고, 두 번째 연속 위반은 freeze trigger
  - 정상 하루가 연속 위반 count를 0으로 되돌림
  - 비율 경계는 0~1
  - sigma=0에서 값 변화는 위반
- [ ] repository 집계 테스트를 작성한다.
  - relevance gate pass rate
  - confirmed event count
  - impact median과 p95
  - 빈 날과 null score 처리
- [ ] RED를 확인한다.
- [ ] JDK-only 통계 계산을 구현한다.
- [ ] daily job과 ShedLock을 추가하고 metric row를 upsert한다.
- [ ] 두 번 연속 위반에서 StateFreezeService.freeze를 호출한다.
- [ ] metric query와 저장은 최근 28일·고정 4개 metric으로 제한한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): monitor pipeline control charts

## Task 7: 피드 데드맨

**Files:** DeadmanMonitor, PipelineMonitorJob, TrackerRepository와 tests

- [ ] 다음 실패 테스트를 먼저 작성한다.
  - publication 간격 표본 3개 미만은 INSUFFICIENT_DATA
  - silence가 median interval의 정확히 2배 이하는 OK
  - 2배 초과는 ALERT
  - disabled source는 제외
  - ALERT가 StateFreezeService를 호출하지 않음
- [ ] RED를 확인한다.
- [ ] 최근 bounded publication timestamp만 읽는 repository query를 추가한다.
- [ ] median과 상태 계산을 pure class로 구현한다.
- [ ] 결과를 ops_state 또는 bounded daily metric으로 노출한다.
- [ ] 새 외부 alert endpoint나 egress를 만들지 않는다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): add feed deadman monitoring

## Task 8: drift 훈련

**Files:**

- Create apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ops/CircuitBreakerDrillTest.java
- Create docs/runbooks/tracker-circuit-breaker-drill.md

- [ ] DRILL fake가 44/50 agreement를 내도록 통합 테스트를 작성한다.
- [ ] 자동 freeze, provisional 유지, approval 409 의미, 이유 없는 release 거부, 이유 있는 release, audit log, 단일 재처리를 한 테스트에서 검증한다.
- [ ] DRILL이 GOLDEN_LAST_LIVE_SUCCESS를 바꾸지 않는지 검증한다.
- [ ] 테스트를 GREEN으로 만든다.
- [ ] 런북에 fixture 실행, 관찰 SQL/API, 해제 전 확인표와 rollback을 기록한다.
- [ ] 커밋: test(tracker): prove circuit breaker drill

## Task 9: WP2.3/WP2.4 전체 검증과 문서

- [ ] schema, golden, ops focused tests를 fresh 실행한다.
- [ ] backend 전체 테스트를 fresh 실행한다.
- [ ] fixture 크기, DB 행 수, scheduler count를 기록한다.
- [ ] secret scan에서 token/API key fixture가 없음을 확인한다.
- [ ] GitOps egress diff에서 새 도메인이 없음을 확인한다.
- [ ] 마스터에는 WP2.3/WP2.4 요약과 이 상세 계획 링크만 추가한다.
- [ ] .superpowers/sdd/progress.md에 로컬 실행 증거를 기록하되 스테이징하지 않는다.
- [ ] 커밋: docs(tracker): record quality operations evidence

## 완료 체크

- [ ] 저작권 안전 골든 case 50개
- [ ] OFFLINE/LIVE/DRILL 명확한 분리
- [ ] LIVE agreement 0.90 threshold 구현
- [ ] 28일/14일/3 sigma/2회 관리도 구현
- [ ] 2× median 데드맨 경보 구현
- [ ] 자동 freeze와 인간 전용 release 구현
- [ ] 훈련 통합 테스트 통과
- [ ] 새 egress·평문 secret·별도 workload 0

