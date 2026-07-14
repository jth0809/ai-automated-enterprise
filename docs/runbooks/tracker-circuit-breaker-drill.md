# Tracker 서킷 브레이커 DRILL 런북

## 목적과 경계

이 훈련은 체크인된 저작권 안전 골든셋과 결정론적 test classifier로
`44/50 = 0.88` 합의를 만들고, 골든 평가부터 상태 동결·인간 해제·사건
재처리까지의 연결을 검증한다.

- 실행 모드는 `DRILL`이다. 결과를 실제 모델 품질이나 LIVE baseline으로
  표시하지 않는다.
- Anthropic API key와 외부 네트워크를 사용하지 않는다.
- `CircuitBreakerDrillTest`는 테스트 트랜잭션에서 실행되고 종료 시 DB 변경을
  rollback한다.
- 외부 기사 원문을 추가하지 않으며 결과에는 canonical hash와 불일치 필드만
  남긴다.
- production에서 수동 SQL 변경이나 `kubectl apply`를 하지 않는다. 배포와
  비상 중지는 `gitops/` 변경과 Flux reconcile만 사용한다.

## 사전 조건

1. 작업트리가 `feat/tracker-mvp`의 검증 대상 commit을 가리킨다.
2. JDK 21과 저장소의 Maven 3.9.9 wrapper distribution을 사용할 수 있다.
3. `tracker/golden-set-v1.json`의 validator가 통과하며 active case가 50개다.
4. 실제 `anthropic.api-key`를 test profile에 넣지 않았다.
5. LIVE 활성화 증거와 DRILL 증거를 별도 기록할 위치를 준비했다.

## 오프라인 fixture 실행

`apps/backend/springboot-app`에서 다음 focused test를 실행한다.

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698\bin\mvn.cmd' `
  -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=GoldenSetJobTest,CircuitBreakerDrillTest' test
```

필수 결과:

- `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
- DRILL run은 50건 중 44건 일치, agreement `0.88`
- `STATE_FROZEN=true`, trigger `DRILL`, reason에 `44/50` 포함
- `GOLDEN_LAST_LIVE_SUCCESS`, `GOLDEN_LIVE_ACTIVATED`,
  `GOLDEN_BASELINE_REQUIRED` 값 불변
- 동결 중 사건은 `PROVISIONAL`, node level과 history 불변
- 관리자 승인은 `409 FROZEN`, review는 `PENDING`
- 빈 해제 이유는 거부되고 HUMAN 해제만 성공
- 해제 뒤 scheduler를 두 번 호출해도 event는 한 번만 CONFIRMED되고
  `node_state_history`는 한 건
- `ops_action_log`에는 `FREEZE/DRILL`과 `RELEASE/HUMAN` 두 전이만 존재

실패하면 해제 절차로 넘어가지 않는다. 먼저 test report의 최초 실패 assertion과
application log를 확인하고 원인을 수정한 뒤 fresh 실행한다.

## staging 관찰 쿼리

아래 SQL은 읽기 전용이다. 운영 DB에서 값을 수정하는 SQL로 대체하지 않는다.

```sql
SELECT id, mode, run_status, total_count, matched_count, failed_count, agreement,
       started_at, completed_at
  FROM golden_set_run
 ORDER BY started_at DESC
 FETCH FIRST 5 ROWS ONLY;
```

```sql
SELECT state_key, state_value, updated_at
  FROM ops_state
 WHERE state_key IN (
       'STATE_FROZEN','FREEZE_REASON','FREEZE_TRIGGER','FREEZE_AT',
       'GOLDEN_LAST_RUN_STATUS','GOLDEN_LAST_LIVE_SUCCESS',
       'GOLDEN_LIVE_ACTIVATED','GOLDEN_BASELINE_REQUIRED')
 ORDER BY state_key;
```

```sql
SELECT id, event_id, reason, status, reviewer_note, resolved_at
  FROM review_queue
 WHERE reason = 'CIRCUIT_BREAKER'
 ORDER BY id DESC
 FETCH FIRST 100 ROWS ONLY;
```

```sql
SELECT action_type, reason, trigger_type, previous_state, new_state, created_at
  FROM ops_action_log
 ORDER BY created_at DESC, id DESC
 FETCH FIRST 20 ROWS ONLY;
```

DRILL이 LIVE 기준을 바꾸지 않았는지는 실행 전후
`GOLDEN_LAST_LIVE_SUCCESS`, `GOLDEN_LIVE_ACTIVATED`,
`GOLDEN_BASELINE_REQUIRED` 세 값을 대조해 확인한다. DRILL run의 `agreement`를
LIVE 운영 지표에 복사하지 않는다.

## API 관찰과 동결 중 승인 확인

관리자 token은 OCI Vault에서 External Secrets Operator를 통해 주입된 값만
사용한다. URL, query string, 브라우저 저장소, fixture, 로그에 token을 넣지
않는다.

정식 Phase 2 운영 API가 배포된 뒤에는 다음 경로로 상태를 읽는다.

- `GET /api/tracker/admin/ops`
- `GET /api/tracker/admin/reviews?status=PENDING&reason=CIRCUIT_BREAKER&page=0&size=25`

동결 중 pending review에 기존/호환 decision endpoint로 `APPROVE`를 보내면
HTTP `409`와 `{"error":"FROZEN"}`이 반환되어야 하며, 같은 review를 다시
조회했을 때 `PENDING`이어야 한다. 관리자 token은
`X-Tracker-Admin-Token` header로만 보낸다.

## 인간 해제 전 확인표

다음 항목이 모두 참일 때만 `POST /api/tracker/admin/ops/release`를 호출한다.

- [ ] 동결 원인이 golden agreement, control chart 또는 명시된 drill인지 확인했다.
- [ ] DRILL 결과와 LIVE_MODEL 결과를 혼동하지 않았다.
- [ ] 실제 model/prompt/rubric/schema tuple 변경 여부를 확인했다.
- [ ] LIVE가 활성화된 환경이면 fresh LIVE run이 최소 `45/50`이고 오류가 없다.
- [ ] control chart의 연속 위반 원인과 deadman feed 상태를 조사했다.
- [ ] 원인이 데이터 품질인지 수집 장애인지 분리했다.
- [ ] pending `CIRCUIT_BREAKER` review 수와 해제 후 재처리 범위를 확인했다.
- [ ] node state와 마지막 정상 표시 ETA가 동결 중 바뀌지 않았음을 확인했다.
- [ ] 해제 이유가 구체적이며 비밀·token·원문을 포함하지 않고 1,000자 이하다.
- [ ] 이상 재발 시 적용할 GitOps rollback commit 또는 이전 image revision을
      준비했다.

빈 이유, 자동 trigger 또는 이미 ACTIVE인 상태의 해제는 성공으로 간주하지
않는다. 자동 해제는 없다.

## 해제 후 검증

1. ops 상태가 `STATE_FROZEN=false`인지 확인한다.
2. `ops_action_log`의 최신 행이 `RELEASE/HUMAN/FROZEN→ACTIVE`인지 확인한다.
3. 대상 `CIRCUIT_BREAKER` review가 해제 처리됐는지 확인한다.
4. 다음 정상 state-updater 주기 뒤 대상 event가 정확히 한 번 처리됐는지
   `event.state_advanced`와 `node_state_history.cause_event_id`로 확인한다.
5. 새 자동 동결이 발생하지 않는지 최소 한 관제 주기 동안 관찰한다.

## rollback과 실패 대응

오프라인 fixture는 트랜잭션 rollback이 자동 적용되므로 별도 정리 작업을 하지
않는다.

staging/production에서 해제 후 잘못된 상태 전이나 같은 경보가 재발하면:

1. 수동 SQL로 `ops_state`나 node level을 수정하지 않는다.
2. 준비한 GitOps 변경으로 tracker workload를 비활성화하거나 검증된 이전
   image revision으로 되돌리고 Flux가 선언 상태를 적용하게 한다.
3. 이미 기록된 event, review, golden run, ops audit 행을 삭제하지 않는다.
4. 원인을 수정한 새 commit에서 focused test와 backend 전체 test를 다시
   실행한다.
5. fresh DRILL과 필요한 LIVE_MODEL 기준선을 통과한 뒤 새 HUMAN 해제 이유로
   복구한다.

API key를 사용할 수 없는 동안에는 `OFFLINE_REPLAY`와 `DRILL`만 실행한다.
이 상태는 `LIVE_MODEL NOT_ACTIVATED`이며 G2의 외부 모델 활성화 증거를 충족한
것으로 표시하지 않는다.
