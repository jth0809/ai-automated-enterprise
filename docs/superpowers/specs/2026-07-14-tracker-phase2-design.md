# Tracker Phase 2 전체 설계

**상태:** 승인됨  
**승인 방향:** A — P2·P3 집중 감사 후 P4~P6 전수 감사, 이어서 주간 백필·골든셋·관제·정식 검토 UI·임베딩 병합을 순차 완료  
**기준 문서:** docs/plans/multiplanetary-tracker-concept-v2.md, docs/plans/multiplanetary-tracker-execution-plan.md  
**선행 산출물:** docs/superpowers/plans/2026-07-14-tracker-phase2-wp21-wp22a-plan.md

## 1. 목적

Phase 2는 35개 역량 노드의 “오늘 상태”를 공식 근거로 보정하고, 1957년부터 현재까지의 주간 시계열을 생성하며, 자동 분류가 잘못될 때 상태 갱신을 멈출 수 있는 운영 안전장치를 완성한다.

완료 범위는 다음과 같다.

1. WP2.2-B: P2~P6 현재 상태·이면·휴면 전수 감사
2. WP2.2-C: 1957년부터 현재까지 6개 필라의 주간 스냅샷
3. WP2.3: 약 50건의 저작권 안전 골든셋과 주간 회귀 평가
4. WP2.4: 데드맨·관리도·자동 상태 동결·인간 전용 해제와 훈련
5. WP2.5: 필터·이력·페이지 이동·동결 해제를 포함한 정식 검토 UI와 운영 런북
6. WP2.6: Phase 1에서 이월한 자연 키 + 로컬 벡터 임베딩 사건 병합
7. G2 증거 묶음: 현재 상태 일치, 주간 백필, 골든 기준선, 서킷 브레이커 훈련, 임시 시드 대체

외부 분류 API를 현재 호출할 수 없다는 제약은 구현을 막지 않는다. 테스트와 훈련은 결정론적 오프라인 fixture로 수행하고, 실제 모델 합의율은 운영 키가 주입된 뒤 실행하는 별도 활성화 증거로 구분한다. 오프라인 replay 결과를 실제 모델 품질로 오인해 기록하지 않는다.

## 2. 설계 원칙

### 2.1 굿하트 방지

- 목표 점수, 목표 ETA, 목표 L0 개수, 목표 청구 건수를 데이터 승인 기준으로 사용하지 않는다.
- 각 노드의 현재 단계는 루브릭의 실제 도달 조건과 직접 공식 근거로만 결정한다.
- 일부 조건만 충족한 노드는 그에 해당하는 부분 단계를 인정한다. 최고 단계의 모든 조건을 충족하지 않았다는 이유로 L0으로 되돌리지 않는다.
- 직접 근거가 없는 통합 노드는 L0을 유지한다. 특히 P4 RESOURCE-INTEGRATION과 P6 SETTLEMENT-INTEGRATION은 실제 통합 체계 증거가 생기기 전까지 올리지 않는다.
- 감사 결과는 근거와 독립적으로 계산한 readiness에 반영하며, readiness 또는 ETA를 보고 청구를 추가·삭제하지 않는다.
- 데이터 validator의 수량 제한은 품질 목표가 아니라 저장·처리 안전 상한이다. 기존 110~150 청구 상한은 제거하고 파일 바이트, 항목 길이, 중복 ID, 노드별 비정상 폭증을 검증한다.

### 2.2 근거와 저작권

- 상태 청구에는 정부기관·공공 연구기관·임무 운영기관의 공식 1차 자료를 우선 사용한다.
- 저장하는 역사 근거는 URL, 문서 내 locator, 접근일, SHA-256, 짧은 사실 요약, 출판 경로뿐이다.
- 제3자 원문 본문, PDF 본문, 긴 인용문, 스크린샷을 백필 또는 골든셋에 저장하지 않는다.
- 골든셋 입력은 사람이 작성한 합성 기사 또는 공식 사실을 독자적으로 바꿔 쓴 짧은 문장만 사용한다. 외부 문장의 복제나 긴 인용은 금지한다.
- 합성 입력에는 SYNTHETIC 또는 HUMAN_PARAPHRASE 출처 유형을 명시하고, provenance에는 내부 case code와 관련 historical candidate ID만 둔다.

### 2.3 저장·자원

- 새 모델 파일, 외부 벡터 DB, 별도 pod, 별도 CronJob을 추가하지 않는다.
- 모든 주기 작업은 기존 Spring Boot 프로세스의 @Scheduled + ShedLock으로 실행한다.
- 1957년 첫 월요일부터 현재의 마지막 월요일까지 6개 필라를 저장하면 약 2.2만 행이다. 인덱스를 포함해도 수십 MB보다 훨씬 작은 범위로 제한한다.
- 골든셋은 약 50건, 입력·기대 출력·메모 합계 건당 최대 4 KiB를 기본 한도로 하며 전체 fixture는 256 KiB 이하를 목표로 한다.
- 임베딩 벡터는 DB에 저장하지 않고 최근 후보의 기존 evidenceQuote와 actor에서 요청 시 계산한다.
- 배치 크기와 후보 검색 수를 제한하고, 모든 조회는 상한을 강제한다.

### 2.4 보안·배포

- 테스트는 네트워크를 사용하지 않는다.
- 새로운 외부 연결이 없으므로 Cilium egress 허용 목록을 확장하지 않는다.
- 관리자 토큰은 X-Tracker-Admin-Token 헤더로만 전달하며 브라우저 저장소, URL, 로그, fixture, Git에 넣지 않는다.
- 실제 토큰과 API 키는 OCI Vault + External Secrets Operator 경로만 사용한다.
- 배포 변경은 gitops/의 선언형 변경으로만 수행하며 수동 kubectl 명령은 사용하지 않는다.

## 3. 전체 데이터 흐름

1. 공식 역사 자료를 historical-candidates-v1.jsonl의 짧은 참조 메타데이터로 등록한다.
2. 이중 검토된 backfill-v1.json 청구가 node/event/level/evidence를 매핑한다.
3. BackfillDatasetValidator가 출처 등급, 참조 무결성, 리뷰 독립성, 단계 범위, 저장 상한을 검증한다.
4. BackfillLoader가 자연 키 기준으로 사건을 멱등 upsert하고 현재 상태를 재생한다.
5. WeeklyBackfillProjector가 동일한 승인 청구를 시간순으로 재생해 월요일 기준 주간 스냅샷을 만든다.
6. 온라인 수집 분류는 EventMerger에서 자연 키를 먼저 확인하고, 불일치할 때만 제한된 로컬 벡터 후보 비교를 수행한다.
7. StateUpdater는 상태 동결 여부를 마지막 쓰기 직전에 확인한다. 동결 중 사건은 PROVISIONAL과 검토 큐에 남고 node state는 바꾸지 않는다.
8. GoldenSetJob과 PipelineMonitorJob이 평가·관제 결과를 기록한다. 임계 위반은 StateFreezeService를 통해 자동 동결한다.
9. 관리자는 정식 검토 UI에서 사건을 승인·반려하고, 원인을 확인한 뒤 이유를 남겨 동결을 해제한다.

## 4. WP2.2-B — P2~P6 현재 상태 감사

### 4.1 순서

감사는 두 묶음으로 수행한다.

- B1: P2 생존·생활지원과 P3 거주·에너지
- B2: P4 자원·산업, P5 운영·자율성, P6 경제·제도

B1을 먼저 수행하는 이유는 현재 HEALTH-AUTONOMY와 THERMAL이 L0이고, 부분 실증 근거가 존재할 가능성이 높아 과도한 보수 판정 여부를 가장 먼저 검증할 수 있기 때문이다. B2에서는 이미 확인된 실제 통합 부재를 존중하면서 나머지 노드의 단계·휴면 의미를 대조한다.

### 4.2 노드별 감사 기록

35개 모든 노드에 대해 다음 기록이 있어야 한다.

- 현재 level과 적용 rubric version
- 해당 level을 직접 지지하는 claim ID와 evidence ref
- 다음 level에서 아직 충족하지 못한 조건
- ACTIVE 또는 DORMANT 판정과 근거
- 비상태 사건과 상태 사건의 구분
- 감사자 2인의 독립 승인

노드당 청구 개수는 완료 기준이 아니다. 한 개의 강한 공식 근거로 충분할 수도 있고, 여러 근거가 있어도 도달 조건을 충족하지 못할 수 있다.

### 4.3 프로그램 취소 의미

PROGRAM_CANCELLATION이라는 사건 유형만으로 역량 노드 전체의 program_end_date를 설정하지 않는다. 백필 청구에 선택 필드 programEndEffect를 추가하며 값은 NONE 또는 CAPABILITY_PROGRAM_END이다.

- 기본값은 NONE이다.
- CAPABILITY_PROGRAM_END는 근거가 해당 노드의 대표 프로그램 계보 전체 종료를 직접 말할 때만 허용한다.
- 개별 임무 취소, 예산 중단, 일정 변경은 사건으로 남기되 노드 전체 휴면을 유발하지 않는다.
- 현재 P6-FUNDING에 연결된 VIPER 취소는 이 규칙으로 다시 검토한다.

validator는 CAPABILITY_PROGRAM_END에 공식 근거, 비어 있지 않은 reviewer note, 종료 범위 설명이 없으면 실패시킨다.

### 4.4 데이터 안전 상한

production corpus의 기존 정확한 수량 강제는 다음 안전 규칙으로 교체한다.

- 후보 파일 최대 2 MiB, 매핑 파일 최대 1 MiB
- 후보·청구 각각 최대 512건
- factSummary 최대 500자, reviewer note 최대 2,000자
- 한 노드의 상태 변경 청구가 32건을 넘으면 수동 검토 실패
- 동일 candidate/node/event/date/level 중복 실패
- 최소 수량은 두지 않는다. 대신 35개 노드 audit coverage가 모두 존재해야 한다.

이 상한은 데이터 부풀리기를 장려하지 않으면서 현 212개 후보와 후속 공식 근거를 충분히 수용한다.

### 4.5 완료 조건

- 35/35 노드에 audit coverage가 있다.
- 각 현재 단계가 독립 재생 결과와 DB state에서 일치한다.
- L0 노드는 “정보 없음”이 아니라 직접 근거를 찾았으나 level 1 조건을 충족하지 못했음이 기록되거나, 공식 탐색 범위에서 근거가 없었음이 기록된다.
- P1 감사와 합친 전체 데이터가 validator와 importer 회귀 테스트를 통과한다.

## 5. WP2.2-C — 1957~현재 주간 백필

### 5.1 주간 기준

- 기준일은 SnapshotJob과 동일한 UTC 월요일이다.
- 시작일은 1957-01-07, 즉 1957년의 첫 월요일이다.
- 종료일은 실행 시점의 마지막 도달 월요일이다.
- 각 날짜마다 pillar 1~6을 생성한다.
- 사건이 없는 초기 주도 readiness 0으로 저장해 시계열 연속성을 보장한다.
- 날짜 계산은 주입 가능한 Clock을 사용해 테스트를 결정론적으로 만든다.

### 5.2 재생과 교체

기존 연말 스냅샷을 주간 데이터와 섞지 않는다. importer와 분리한 WeeklyBackfillProjector가 다음을 한 트랜잭션에서 수행한다.

1. 승인 데이터와 projector version의 해시를 검증한다.
2. 1957-01-07부터 마지막 월요일까지 pillar 1~6 역사 행을 교체한다.
3. 청구를 occurredOn, backfillId 순으로 재생한다.
4. level, rollback, dormancy, programEndEffect를 현재 상태 계산과 같은 규칙으로 적용한다.
5. 마지막 재생 상태와 35개 node current state를 비교한다.
6. 불일치가 하나라도 있으면 전체 트랜잭션을 롤백한다.

projector version은 별도 ops marker로 기록하고, 같은 데이터·버전의 재실행은 no-op이다. 현재 월요일의 운영 SnapshotJob 행과 충돌하지 않도록 역사 projector는 실행 직전 월요일까지 만들고, 같은 키는 동일 readiness로만 교체할 수 있다.

### 5.3 완료 조건

- 날짜 누락 없이 예상 주 수 × 6 행이 존재한다.
- 월요일이 아닌 백필 snapshot은 없다.
- 첫 주·사건 전·사건 당주·rollback·휴면 경계·현재 주 fixture가 통과한다.
- 마지막 주의 재생 상태와 감사된 현재 상태가 일치한다.
- 두 번 실행해 사건·근거·snapshot 수가 증가하지 않는다.

## 6. WP2.3 — 골든셋과 회귀 평가

### 6.1 데이터 계약

기존 golden_set_item은 유지하되 V9 migration에서 다음 메타데이터를 추가한다.

- case_code: 안정적인 고유 ID
- fixture_kind: SYNTHETIC 또는 HUMAN_PARAPHRASE
- expected_schema_version, rubric_version_id
- active, dataset_version
- input_sha256

body CLOB의 의미는 “외부 기사 원문 보존”에서 “저작권 안전 합성·재서술 입력”으로 명확히 수정한다. 애플리케이션은 title/body/expected_output/notes의 UTF-8 바이트 상한을 검증한다.

추가 테이블:

- golden_set_run: 실행 모드, 모델·프롬프트·루브릭 버전, 시작·종료, 총수, 합의수, agreement rate, 상태
- golden_set_result: run/item별 실제 출력의 canonical hash, 일치 여부, 불일치 필드, 오류 코드

원문 모델 출력은 저장하지 않는다. 필요한 경우 canonical 구조화 결과도 크기 제한된 JSON만 저장하며, 운영 로그에 본문이나 관리자 토큰을 남기지 않는다.

### 6.2 약 50건 구성

- positive state event, non-state event, rollback, cancellation, arrival candidate
- 6개 필라와 주요 level 경계
- 동일 사건의 다른 표현, 날짜 정밀도, actor 표기 차이
- 잘못된 quote, 잘못된 node, 과도한 level, 관련 없음
- 자연 키 충돌과 임베딩 유사·비유사 쌍

기대 출력은 2인 합의 라벨이며 현재 승인된 historical claim을 기반으로 하되 입력 문장은 새로 작성한다.

### 6.3 실행 모드

- OFFLINE_REPLAY: 체크인된 결정론적 실제 출력 fixture로 runner, diff, 보고서, breaker 연동을 검증한다.
- LIVE_MODEL: GoldenClassifier 인터페이스의 DeepClassifier adapter가 실제 모델을 호출한다.
- DRILL: 의도적인 불일치 fixture로 자동 동결과 인간 해제를 검증한다.

OFFLINE_REPLAY와 DRILL은 실제 모델 품질 수치로 표시하지 않는다. LIVE_MODEL 실행만 운영 agreement baseline과 stale 판단의 기준이 된다.

### 6.4 판정

- canonical structured output의 필드별 exact agreement를 기본으로 한다.
- quote 검증 또는 schema 검증 실패는 해당 case 실패다.
- 기본 운영 임계값은 0.90, 즉 50건이면 최소 45건 합의다.
- prompt, model, rubric, schema version 중 하나가 바뀌면 새 기준선 실행이 필요하다.
- 주간 실행은 일요일 UTC이며 ShedLock으로 단일 실행한다.

## 7. WP2.4 — 관제와 서킷 브레이커

### 7.1 상태 동결

StateFreezeService가 STATE_FROZEN, FREEZE_REASON, FREEZE_TRIGGER, FREEZE_AT을 일관되게 관리한다.

자동 동결 조건:

- 최신 LIVE_MODEL 골든셋 agreement가 0.90 미만
- 활성화 이후 최신 성공 LIVE_MODEL run이 8일보다 오래됨
- 관리도 위반이 2회 연속 발생

동결 중 동작:

- 수집·추출·분류·사건 병합은 계속할 수 있다.
- StateUpdater는 node state와 node_state_history를 변경하지 않는다.
- 상태 대상 사건은 PROVISIONAL로 남고 CIRCUIT_BREAKER 검토 항목을 멱등 생성한다.
- 관리자 승인도 review를 먼저 resolve하지 않고 409 FROZEN을 반환한다.
- snapshot은 마지막 정상 표시 ETA를 유지하고 frozen=true를 노출한다.

해제:

- 자동 해제는 없다.
- 관리자 토큰과 비어 있지 않은 2,000자 이하 이유가 필요하다.
- ops_action_log에 actor 종류, action, reason, 이전·새 상태, 시각을 남긴다. 토큰이나 비밀은 기록하지 않는다.
- 해제 후 pending 사건은 기존 scheduler가 다시 처리한다.

### 7.2 관리도

매일 다음 집계만 저장한다.

- relevance gate pass rate
- 새 confirmed event count
- impact score median과 p95

최근 28일 중 데이터가 있는 최소 14일을 기준선으로 사용하고 평균 ± 3 표준편차를 경계로 한다. 비율은 0~1로 자르고, 분산이 0이면 값 변화 자체를 경계 위반으로 본다. 동일 metric의 2회 연속 일일 위반에서 동결한다.

### 7.3 데드맨

각 활성 feed의 최근 정상 publication 간격 중앙값을 계산한다. 마지막 정상 수집 이후 시간이 중앙값의 2배를 넘으면 경보 상태를 기록한다.

- 표본이 3개 미만이면 INSUFFICIENT_DATA다.
- 데드맨은 수집 문제 신호이므로 상태를 자동 동결하지 않는다.
- 새 알림 egress를 만들지 않고 ops API와 UI에서만 보여 준다.

### 7.4 훈련

DRILL golden run으로 agreement 0.90 미만을 만들고 다음을 자동 검증한다.

1. STATE_FROZEN=true와 원인 기록
2. 새 사건이 node state를 변경하지 않음
3. 승인 API가 409를 반환하고 review가 pending 유지
4. 인간 해제 API가 이유 없이 실패
5. 이유 있는 해제 성공과 audit log
6. 재처리 후 정확히 한 번 상태 전이

## 8. WP2.5 — 정식 검토 UI와 운영 런북

### 8.1 API

기존 token-gated endpoint를 확장한다.

- GET /api/tracker/admin/reviews?status=&reason=&page=&size=
- POST /api/tracker/admin/reviews/{id}/decision
- GET /api/tracker/admin/ops
- POST /api/tracker/admin/ops/release

기존 /review와 /review/{id}는 호환 경로로 유지한다. status, reason은 allowlist enum으로 검증하고 size는 최대 100이다. 응답에는 total, page, size와 정렬 기준을 포함한다.

### 8.2 UI

- pending/approved/rejected 탭
- reason 필터와 우선순위 정렬
- 현재 level → proposed level, 증거, 검증 수준, 상태, 처리 메모
- 이전·다음 페이지
- 동결 상태·원인·발생 시각·관리도·골든셋·데드맨 요약
- 관리자 이유를 요구하는 release form

토큰은 현재 React state와 요청 header에만 존재하며 localStorage, sessionStorage, query string을 사용하지 않는다.

### 8.3 런북

runbook은 주간 1~2시간, 월간 1시간, 분기 2~4시간 절차를 분리한다. 최소 내용은 다음과 같다.

- 검토 순서와 승인·반려 기준
- 동결 원인별 조사
- 골든셋 변경·2인 합의 절차
- 데드맨 feed 확인
- 관리도 오탐·실제 drift 구분
- 해제 전 확인표와 rollback
- API 키가 없을 때 OFFLINE_REPLAY만 실행하고 LIVE baseline으로 표시하지 않는 규칙

## 9. WP2.6 — 로컬 벡터 임베딩 병합

### 9.1 방식

Phase 1 자연 키를 1차 병합으로 유지하고, 자연 키가 일치하지 않을 때만 로컬 feature-hashed text embedding을 사용한다.

- 입력: node code, event type, normalized actor, evidenceQuote
- 특징: Unicode 정규화 후 단어·문자 n-gram을 256차원 signed hash vector로 투영하고 L2 정규화
- 후보: 같은 node, 같은 event type, occurredOn ±7일, 최근 최대 50개 event
- actor: 양쪽이 비어 있지 않으면 token overlap 또는 alias compatibility가 필요
- 유사도: cosine 0.82 이상
- 모호성: 상위 2개 점수 차가 0.02 미만이면 병합하지 않음
- 안전 실패: 별도 사건 생성

threshold는 골든셋의 duplicate/non-duplicate 쌍으로 고정하며, 점수를 높이기 위해 데이터 라벨을 바꾸지 않는다.

### 9.2 저장과 확장성

벡터는 저장하지 않는다. 기존 linked classification의 evidenceQuote를 최대 50건 읽어 메모리에서 계산한다. 구현은 JDK만 사용하고 새 모델·native library·egress·secret을 요구하지 않는다.

SemanticCandidateMatcher 인터페이스를 두어 향후 승인된 로컬 또는 외부 모델로 교체할 수 있지만, Phase 2 배포 계약은 이 결정론적 로컬 lexical-semantic baseline이다. “신경망 의미 동일성”으로 과장해 표시하지 않는다.

### 9.3 완료 조건

- 자연 키 exact match가 항상 우선한다.
- 표현 차이가 있는 같은 사건 fixture가 병합된다.
- 같은 날짜라도 다른 actor/사건 fixture는 병합되지 않는다.
- 모호한 후보는 병합되지 않는다.
- 재실행해 classification link와 event 수가 멱등이다.
- 후보·배치 상한과 실행 시간 회귀 테스트를 통과한다.

## 10. 오류 처리

- validator 오류는 import 전에 전체 실패하며 부분 DB 쓰기를 남기지 않는다.
- projector의 현재 상태 불일치는 전체 주간 snapshot 교체를 롤백한다.
- 골든 case 한 건의 모델 오류는 result에 오류 코드를 남기고 run을 실패 처리한다. 원문 응답은 저장하지 않는다.
- scheduler 개별 항목 오류는 다음 항목 처리를 막지 않되 run-level 실패 수를 기록한다.
- 동결 상태 조회 실패는 fail closed로 간주해 상태 전이를 수행하지 않는다.
- 관리자 동시 결정은 기존 조건부 update로 한 명만 성공하고 나머지는 409를 받는다.

## 11. 테스트 전략

모든 구현은 실패 테스트를 먼저 추가한다.

### 백엔드

- schema migration: 새 제약, FK, unique, bounded enum
- backfill validator: audit coverage, programEndEffect, 파일·문자 상한, 중복
- audit corpus: P2~P6 27개 노드의 직접 도달 조건
- weekly projector: 1957 시작, 월요일 cadence, rollback, dormancy, current match, idempotency
- golden repository/runner/diff: 50건 seed, canonical agreement, run mode 구분
- freeze service/state updater: fail closed, 자동 동결, 인간 해제, 승인 경쟁
- control chart/deadman: 경계값, 최소 표본, 연속 위반
- semantic matcher/event merger: positive, negative, ambiguous, exact-first
- controller: auth, filter allowlist, pagination, frozen 409, release audit

### 프런트엔드

- 필터·탭·페이지 이동
- 동결 상태 표시와 이유 필수 release
- 401/409/재시도
- 토큰이 URL·저장소에 쓰이지 않음
- 기존 dashboard와 review tests 회귀

### 전체

- Maven 전체 테스트
- frontend test, typecheck/build
- historical corpus validator
- GitOps egress 회귀
- gitleaks 또는 저장소의 기존 secret scan 경로

## 12. G2 승인 증거

| 요구사항 | 증거 | 판정 |
|---|---|---|
| 35개 현재 상태 보정 | 노드별 audit matrix + importer replay + DB state 비교 | 35/35 일치 |
| 1957~현재 주간 시계열 | cadence/count/first-last/current-match 테스트 | 누락 0 |
| ETA가 백필 시계열 기반 | SnapshotJob trend가 주간 pillar_snapshot을 사용 | fixture 재현 |
| 골든 기준선 | 약 50개 2인 합의 라벨 + OFFLINE contract report | 데이터·runner 완료 |
| 실제 모델 합의율 | LIVE_MODEL report | 운영 키 주입 후 활성화 증거 |
| 서킷 브레이커 | DRILL report + audit log | 자동 동결·인간 해제 통과 |
| 임시 시드 대체 | 35-node version과 승인 backfill provenance | 임시 노드 없음 |
| 저작권·저장 | prohibited-body validator + size report | 원문 0, 상한 이내 |
| 보안 | auth tests, egress diff, secret scan | 새 egress·평문 비밀 0 |

G2 소프트웨어·데이터 게이트와 실제 외부 모델 활성화 게이트를 구분한다. API 키가 없는 상태에서 LIVE_MODEL 합의율을 조작하거나 OFFLINE_REPLAY를 실제 모델 결과로 표기하지 않는다.

## 13. 문서와 작업 분리

docs/plans/multiplanetary-tracker-execution-plan.md에는 Phase 2 요약, 상태, 이 설계서와 별도 상세 계획 링크만 둔다. 구현 단계·명령·파일별 TDD 절차는 docs/superpowers/plans 아래의 WP별 상세 계획으로 분리한다.

예정 상세 계획:

1. 2026-07-14-tracker-phase2-wp22b-wp22c-plan.md
2. 2026-07-14-tracker-phase2-wp23-wp24-plan.md
3. 2026-07-14-tracker-phase2-wp25-wp26-g2-plan.md

