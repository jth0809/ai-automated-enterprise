# Tracker Phase 2 WP2.5/WP2.6/G2 상세 구현 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 승인 설계: [2026-07-14-tracker-phase2-design.md](../specs/2026-07-14-tracker-phase2-design.md)
>
> 선행 조건: WP2.2~WP2.4 완료
>
> 상태: 실행 대기

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. 사용자가 금지한 서브에이전트는 사용하지 않는다. 예상 밖 실패에는 systematic-debugging, 완료 주장 전에는 verification-before-completion을 적용한다.

**Goal:** 기존 검토 큐를 필터·이력·pagination·운영 상태·인간 동결 해제를 포함한 정식 UI로 확장하고, Phase 1 이월 자연 키 + 로컬 벡터 사건 병합을 구현한 뒤 G2 요구사항을 증거별로 폐쇄한다.

**Architecture:** 기존 TrackerAdminController와 ReviewQueue를 호환 확장한다. 토큰은 헤더와 React memory에만 둔다. EventMerger는 exact natural key 우선, bounded SemanticCandidateMatcher 후순위로 동작한다. 벡터는 저장하지 않고 JDK feature hashing으로 즉시 계산한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, JUnit 5, React 19, TypeScript, Vite, Vitest, Testing Library, CSS, GitOps.

## 전역 제약

- 기존 GET /api/tracker/admin/review와 POST /api/tracker/admin/review/{id}를 깨지 않는다.
- 새 API의 모든 filter, page, size, action은 allowlist와 상한을 적용한다.
- 관리자 토큰을 localStorage, sessionStorage, URL, 로그, DOM attribute, fixture에 남기지 않는다.
- 임베딩은 외부 API, 모델 파일, native library, vector DB, 새 secret, 새 egress를 사용하지 않는다.
- exact natural key가 항상 semantic 후보보다 우선한다.
- semantic ambiguity 또는 actor conflict는 병합하지 않는 쪽으로 실패한다.
- G2 문서는 실제 실행 증거만 완료로 표시한다. API가 없으면 LIVE_MODEL 활성화 항목은 별도 상태로 남긴다.
- master plan에는 요약과 상세 계획/증거 링크만 둔다.

## 파일 구조

### Review API/UI

- Modify apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/api/TrackerAdminController.java
- Modify apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/TrackerRepository.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/ReviewPage.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/OpsOverview.java
- Modify apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/api/TrackerAdminControllerTest.java
- Modify apps/frontend/react-app/src/tracker/api.ts
- Modify apps/frontend/react-app/src/tracker/ReviewQueue.tsx
- Modify apps/frontend/react-app/src/tracker/ReviewCaseCard.tsx
- Create apps/frontend/react-app/src/tracker/ReviewFilters.tsx
- Create apps/frontend/react-app/src/tracker/OpsPanel.tsx
- Modify/create corresponding Vitest files and tracker CSS

### Semantic merge

- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/event/TextFeatureEmbedding.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/event/SemanticCandidateMatcher.java
- Create apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/domain/MergeCandidate.java
- Modify apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/event/EventMerger.java
- Modify TrackerRepository.java
- Create TextFeatureEmbeddingTest.java and SemanticCandidateMatcherTest.java
- Modify EventMergerTest.java

### Runbook/G2

- Create docs/runbooks/tracker-phase2-operations.md
- Create docs/runbooks/tracker-phase2-g2-validation.md
- Create docs/research/tracker-phase2-g2-evidence.md
- Modify docs/plans/multiplanetary-tracker-execution-plan.md
- Append .superpowers/sdd/progress.md (Git 제외)

## Task 1: 정식 review page API

**Contract:**

- GET /api/tracker/admin/reviews?status=PENDING&reason=HIGH_IMPACT&page=0&size=25
- response: items, page, size, total, totalPages, sort
- status: PENDING, APPROVED, REJECTED
- reason: DB check constraint의 5개 값
- size: 1~100, page: 0 이상
- sort: priority DESC, created_at ASC, id ASC

- [ ] 다음 controller/repository 실패 테스트를 먼저 작성한다.
  - token 없음/오류는 401
  - status/reason 잘못된 값은 400
  - page 음수, size 0 또는 101은 400
  - pending/approved/rejected filter와 total이 일치
  - 동률 정렬이 안정적
  - resolved row의 note/resolvedAt이 표시
  - 한 page query와 bounded evidence query로 N+1 없음
  - 기존 /review endpoint 응답은 유지
- [ ] RED를 확인한다.
- [ ] ReviewPage record와 repository count/page query를 구현한다.
- [ ] controller에 plural endpoint를 추가하고 기존 endpoint는 adapter로 유지한다.
- [ ] 문자열 SQL 조합은 allowlist enum 뒤에만 수행하고 사용자 값을 직접 삽입하지 않는다.
- [ ] evidence는 현재 page item에 대해서만 최대 100건 범위에서 조회한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): paginate formal review queue

## Task 2: ops overview와 인간 release API

**Contract:**

- GET /api/tracker/admin/ops
- POST /api/tracker/admin/ops/release body reason
- release: admin token, frozen=true, nonblank reason 최대 2,000자

- [ ] 다음 실패 테스트를 먼저 작성한다.
  - 인증, frozen/non-frozen 상태
  - freeze reason/trigger/time, latest golden, control metrics, deadman summary 노출
  - release 이유 없음/초과는 400
  - non-frozen release는 409
  - 성공 release는 audit log를 만들고 token을 기록하지 않음
  - frozen review approve는 409 FROZEN이며 pending 유지
- [ ] RED를 확인한다.
- [ ] OpsOverview bounded DTO와 repository query를 구현한다.
- [ ] StateFreezeService.release를 controller에서 호출한다.
- [ ] 오류 응답은 비밀이나 내부 SQL을 노출하지 않는다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): expose safe operations controls

## Task 3: React review filters와 이력

**Files:** api.ts, ReviewQueue.tsx, ReviewFilters.tsx, ReviewCaseCard.tsx와 tests

- [ ] Vitest 실패 테스트를 먼저 작성한다.
  - token 입력 전 요청 없음
  - pending/approved/rejected 탭 전환
  - reason filter와 page 이동 query
  - total/empty/loading/error 표시
  - resolved row는 결정 버튼 없이 note/status 표시
  - 401과 409 메시지
  - 결정 뒤 현재 page reload 또는 안전한 local removal
  - token이 fetch header 외 local/session storage와 URL에 없음
- [ ] RED를 확인한다.
- [ ] getReviewPage type/client를 추가하고 기존 getReviews는 호환 유지한다.
- [ ] ReviewFilters를 별도 컴포넌트로 만들고 상태를 ReviewQueue가 소유한다.
- [ ] page가 비었을 때 이전 page로 한 번 조정한다.
- [ ] button, label, status에 접근 가능한 이름을 제공한다.
- [ ] focused Vitest를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker-ui): add review history and filters

## Task 4: React ops panel과 release

**Files:** OpsPanel.tsx, api.ts, ReviewQueue/TrackerPage, tests, CSS

- [ ] 실패 테스트를 먼저 작성한다.
  - frozen badge, reason, time
  - latest golden mode를 LIVE/OFFLINE/DRILL로 구분
  - control/deadman status
  - release reason 필수
  - 성공 후 ops와 queue reload
  - 401/409/500 처리
  - token을 UI persistence에 저장하지 않음
- [ ] RED를 확인한다.
- [ ] getOpsOverview/releaseOps client를 추가한다.
- [ ] OpsPanel을 구현하고 기존 TrackerPage layout을 깨지 않게 배치한다.
- [ ] 모바일/좁은 폭에서도 filter와 action이 넘치지 않게 기존 CSS 패턴을 재사용한다.
- [ ] focused frontend tests와 build를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker-ui): operate circuit breaker reviews

## Task 5: TextFeatureEmbedding pure TDD

**Files:** TextFeatureEmbedding.java/test

- [ ] 다음 pure unit test를 먼저 작성한다.
  - 같은 입력은 bit-for-bit 같은 256차원 vector
  - Unicode normalization과 대소문자/구두점 차이에 안정적
  - 단어와 문자 n-gram 표현 차이가 높은 cosine을 가짐
  - 관련 없는 문장이 낮은 cosine을 가짐
  - blank 입력은 zero vector이며 NaN 없음
  - vector L2 norm은 0 또는 1
  - 입력 최대 길이 상한 뒤의 텍스트는 처리하지 않음
- [ ] RED를 확인한다.
- [ ] JDK MessageDigest 또는 stable integer hash 기반 signed feature hashing을 구현한다.
- [ ] 256차원 double 배열을 요청 메모리에서만 생성한다.
- [ ] 외부 dependency를 추가하지 않는다.
- [ ] focused test를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): add bounded local text embedding

## Task 6: SemanticCandidateMatcher 안전 가드

**Contract:**

- candidate: same node, exact event type, occurredOn ±7일, 최대 50개
- actor: 양쪽 nonblank이면 alias/token compatibility 필요
- cosine threshold 0.82
- top1-top2 margin 최소 0.02
- ambiguity/conflict/no candidate는 no match

- [ ] matcher 실패 테스트를 먼저 작성한다.
  - 표현이 다른 동일 사건 positive
  - 같은 날·같은 node지만 actor가 다른 negative
  - 같은 actor지만 event type이 다른 negative
  - 8일 차이 negative
  - top score 0.8199 negative, 0.82 positive
  - top margin 0.0199 ambiguous, 0.02 accepted
  - 후보 50개 상한
  - blank actor 양쪽과 한쪽 blank의 명시된 정책
- [ ] RED를 확인한다.
- [ ] SemanticCandidateMatcher를 pure orchestration으로 구현한다.
- [ ] actor alias는 작은 코드 내 allowlist가 아니라 normalization/token overlap으로 제한한다. 새 alias가 필요하면 versioned fixture로 추가한다.
- [ ] threshold 상수와 embedding version을 명시한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): match semantic event candidates

## Task 7: EventMerger exact-first 통합

**Files:** EventMerger.java, TrackerRepository.java, MergeCandidate.java, EventMergerTest.java

- [ ] 다음 통합 실패 테스트를 먼저 작성한다.
  - exact natural key가 있으면 semantic query 결과와 무관하게 exact event 사용
  - exact가 없고 안전한 semantic match가 있으면 기존 event에 link
  - ambiguity면 새 natural-key event 생성
  - 재실행 시 event/classification link 수가 불변
  - 후보 query가 node/type/date와 50개 상한을 적용
  - historical event와 live event의 evidence derivation이 깨지지 않음
- [ ] RED를 확인한다.
- [ ] repository에 linked evidenceQuote를 포함한 bounded MergeCandidate query를 추가한다.
- [ ] EventMerger 순서를 exact lookup → semantic match → upsert로 변경한다.
- [ ] merge 뒤 VerificationDeriver와 linkClassification은 기존 순서를 유지한다.
- [ ] natural key 공개 계약과 기존 tests를 유지한다.
- [ ] focused tests를 GREEN으로 만든다.
- [ ] 커밋: feat(tracker): merge events with local embeddings

## Task 8: 정식 운영 런북

**Files:** docs/runbooks/tracker-phase2-operations.md

- [ ] 주간 1~2시간 절차를 작성한다.
  - pending 우선순위 검토
  - 골든 report 확인
  - deadman alert 확인
  - 승인/반려 기준
- [ ] 월간 1시간 절차를 작성한다.
  - 관리도 baseline과 false alarm 검토
  - source health와 비용
  - review backlog
- [ ] 분기 2~4시간 절차를 작성한다.
  - 50-case 재합의와 drift drill
  - prompt/model/rubric/schema version 변경
  - release/rollback 복습
- [ ] API 키가 없을 때 OFFLINE_REPLAY만 실행하고 LIVE baseline으로 표시하지 않는 규칙을 강조한다.
- [ ] Vault/ESO token/key 경로와 GitOps-only 배포 원칙을 적되 실제 secret 값은 쓰지 않는다.
- [ ] egress 변경이 필요한 미래 모델은 CNP allowlist와 보안 검토 없이 활성화하지 않는다고 명시한다.
- [ ] 커밋: docs(tracker): add Phase 2 operations runbook

## Task 9: G2 검증 하네스와 증거 행렬

**Files:**

- Create docs/runbooks/tracker-phase2-g2-validation.md
- Create docs/research/tracker-phase2-g2-evidence.md
- Modify master plan

- [ ] G2 runbook에 fresh 검증 명령과 expected evidence 위치를 작성한다.
- [ ] 다음 항목을 실제 결과로 채운다.
  - 35/35 current-state audit
  - 1957~현재 월요일 cadence/count/current match
  - weekly history를 사용한 SnapshotJob ETA fixture
  - golden dataset count/size/provenance
  - OFFLINE contract agreement report
  - DRILL freeze/release report
  - live model activation status
  - seed replacement
  - source-body prohibited-field scan
  - DB/storage estimate
  - egress/secret/resource diff
- [ ] LIVE_MODEL API가 없으면 NOT_ACTIVATED로 기록하고 가짜 수치를 만들지 않는다.
- [ ] 마스터 WP2.2~2.6에는 완료 상태와 상세 계획/증거 링크만 반영한다.
- [ ] .superpowers/sdd/progress.md에 실행 증거를 기록하되 Git에 넣지 않는다.
- [ ] 커밋: docs(tracker): assemble G2 evidence

## Task 10: Phase 2 전체 회귀와 보안 검증

- [ ] backend focused Phase 2 suites를 fresh 실행한다.
- [ ] backend 전체 Maven test를 fresh 실행한다.
- [ ] frontend 전체 Vitest를 fresh 실행한다.
- [ ] frontend npm run build를 fresh 실행한다.
- [ ] historical corpus/reference validator를 실행한다.
- [ ] GitOps egress policy test를 실행한다.
- [ ] 저장소의 기존 secret scan/gitleaks 경로를 실행한다.
- [ ] git diff --check를 실행한다.
- [ ] tracked change allowlist를 확인하고 사용자 미추적 파일을 제외한다.
- [ ] verification-before-completion 체크리스트로 요구사항별 증거를 다시 대조한다.
- [ ] requesting-code-review 절차를 직접 자체 검토 방식으로 수행한다.
- [ ] 발견한 문제를 수정한 뒤 관련 테스트와 전체 테스트를 다시 fresh 실행한다.
- [ ] 최종 문서 커밋: docs(tracker): close Phase 2 gate

## Phase 2 완료 체크

- [ ] WP2.1 구조 확정
- [ ] WP2.2 35-node 감사와 주간 백필
- [ ] WP2.3 골든셋과 회귀 평가
- [ ] WP2.4 관제·동결·훈련
- [ ] WP2.5 정식 검토 UI·운영 런북
- [ ] WP2.6 exact-first 로컬 임베딩 병합
- [ ] 저작권 위험 원문 저장 0
- [ ] 저장·CPU bounded 증거
- [ ] 새 egress·평문 secret·수동 kubectl 0
- [ ] G2 소프트웨어·데이터 증거 완결
- [ ] LIVE_MODEL은 실제 실행 여부를 정직하게 별도 표기

