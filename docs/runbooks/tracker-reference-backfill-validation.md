# Tracker 참조형 역사 백필 검증·롤아웃 런북

작성: 2026-07-13. 대상 데이터셋: `nodes-v1.0` / `r2.0` / `backfill-v1`.

이 백필은 애플리케이션 이미지에 포함된 로컬 JSON/JSONL만 읽는다. 배포와 설정 변경은 Git 커밋 및 Flux 조정으로만 수행하며, 운영 데이터베이스를 수동 변경하지 않는다.

## 1. 승인된 불변 입력

| 항목 | 값 |
|---|---:|
| 활성 역량 노드 | 35 |
| 노드 중립 후보 | 정확히 210건 |
| 승인 매핑 | 120건(필라별 20건) |
| 직접 사용 후보 | 117건 |
| 상태 전진 가능 / 비진행 맥락 | 86 / 34 |
| 후보 파일 | 211,298 bytes, SHA-256 `3a556f18d5f40b067934d9e93d015168b6a47a0d12c7928c9bb3d6fb106bdd17` |
| 매핑 파일 | 110,603 bytes, SHA-256 `1273edd3c0f388cc8d0a719477b8d35a9af95a2a2b0dacd3065b687a26c4cfa8` |

210건은 노드마다 6건을 강제로 할당한 수가 아니라, 약한 근거를 버릴 여유를 둔 노드 중립 후보 풀이다. 생산 매핑은 110~150건 승인 범위 안의 120건이다. `backfill-v*` importer는 기동 시 후보 210/READY 210/REJECTED 0과 매핑 110~150을 다시 강제한다. 35개 노드 중 31개에는 직접 매핑이 있고, 직접 통합 근거가 없는 `P1-TRANSPORT-INTEGRATION`, `P2-SURVIVAL-INTEGRATION`, `P4-RESOURCE-INTEGRATION`, `P6-SETTLEMENT-INTEGRATION`은 사건을 발명하지 않고 레벨 0을 유지한다. `P3-HABITAT-INTEGRATION`과 `P5-OPS-INTEGRATION`의 각 1건도 맥락 기록일 뿐 상태를 올리지 않는다.

## 2. 저작권·저장량 방어선

`historical_evidence`와 생산 리소스에는 원문 본문, 인용, 발췌, 출처 제목, HTML, PDF, 이미지, 첨부를 넣지 않는다. 허용 정보는 출처 코드/URL, 로케이터, 확인일, SHA-256 지문, 발행 경로, 검수자가 독립적으로 작성한 사실 요약뿐이다.

현재 리소스 감사 결과:

- 금지 JSON 키 0건, NUL/바이너리 파일 0건
- 후보 JSONL 최대 행 1,155 bytes, 매핑 JSON 최대 행 211 bytes(각 한도 8 KiB)
- 120개 역사 증거 행의 가변 payload 61,872 bytes
- 행·이벤트 오버헤드를 포함한 150개 청구 추정치 192,540 bytes
- 396개 연말 스냅샷, 최대 150개 이력 행, audit까지 각 행 256~512 bytes로 보수 계산한 전체 추정치 434,204 bytes(1 MiB 미만)
- 후보와 매핑 원본을 합쳐도 321,901 bytes

릴리스 전 동일 감사를 다시 실행하고 결과를 PR에 첨부한다. API 응답에서 `HISTORICAL_REFERENCE.evidenceQuote`는 항상 `null`이어야 하며 `body`, `html`, `attachment` 계열 필드가 없어야 한다.

## 3. 릴리스 전 자동 검증 게이트

다음 결과가 모두 녹색인 이미지에서만 진행한다.

```powershell
# apps/backend/springboot-app
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test

# apps/frontend/react-app
npm test -- --run
npm run build

# 저장소 루트
git diff --check
& 'gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1'
```

필수 테스트 범위는 V7/V8 제약과 FK, 35개 노드, 210개 후보, 120개 승인 매핑, 잘못된 데이터셋 전체 롤백, 동일 해시 no-op, 같은 버전의 다른 해시 거부, 시간순 롤백·취소·휴면·복원, 실시간/역사 증거의 출처 중복 제거, 두 UI 렌더링 분기다.

이미 확정된 실시간 상태 전이가 있는 노드는 백필 시작 시 보호 대상으로 고정한다. 해당 노드에는 역사 사건·참조·연말 스냅샷을 추가하지만 현재 레벨, 휴면, 프로그램 종료 상태를 오래된 사건으로 덮어쓰지 않는 회귀 테스트가 필수다.

현재 작업 세션에서는 데이터셋 validator 집중 테스트 16건과 V1~V8 H2 스키마 검사가 통과했다. 이후 importer/API/UI 변경을 포함한 전체 회귀와 프런트 빌드는 로컬 실행 권한 제한 때문에 아직 릴리스 증거로 기록하지 않았으므로, 위 게이트가 실제로 완료되기 전에는 배포 승인으로 간주하지 않는다.

## 4. Flux 전용 2단계 롤아웃

### 1단계 — 이미지와 스키마만 배포

1. CI가 검증된 이미지 digest를 발행한다.
2. GitOps 이미지 참조 변경을 PR로 머지하되 `SPRING_FLYWAY_ENABLED=true`, `TRACKER_ENABLED=false`를 유지한다.
3. Flux 상태와 중앙 로그에서 새 digest 기동, Flyway V7/V8 성공, tracker 비활성을 확인한다.
4. 승인된 읽기 전용 DB 경로로 아래 사전 활성화 쿼리를 실행한다.

```sql
SELECT version, description, success
  FROM flyway_schema_history
 WHERE version IN ('7', '8')
 ORDER BY installed_rank;

SELECT COUNT(*) AS active_nodes
  FROM capability_node
 WHERE node_set_version = 'nodes-v1.0' AND active = 'Y';

SELECT COUNT(*) AS historical_tables
  FROM user_tables
 WHERE table_name IN ('HISTORICAL_EVIDENCE', 'BACKFILL_IMPORT');

SELECT COUNT(*) AS prohibited_columns
  FROM user_tab_columns
 WHERE table_name = 'HISTORICAL_EVIDENCE'
   AND column_name IN ('BODY','QUOTE','EXCERPT','SOURCE_TITLE','HTML','PDF','IMAGE','ATTACHMENT');
```

기대값은 각각 V7/V8 성공 2행, 활성 노드 35, 테이블 2, 금지 열 0이다.

### 2단계 — 백필과 tracker 활성화

1. 별도 GitOps PR에서 `TRACKER_ENABLED=true`로 변경한다. 기본값인 `backfill-v1`, `tracker/backfill-v1.json`, `tracker/historical-candidates-v1.jsonl`을 사용한다.
2. Flux 조정 후 클러스터 단일 `tracker-backfill-import` 잠금 아래에서 실행된 `tracker backfill imported 120 reviewed claims from dataset backfill-v1` 로그를 한 번만 확인한다.
3. 재기동 후 같은 로그가 반복되지 않고 아래 audit 행이 그대로인지 확인한다.

```sql
SELECT dataset_version, dataset_sha256, node_set_version, record_count, imported_at
  FROM backfill_import
 WHERE dataset_version = 'backfill-v1';

SELECT COUNT(*) AS approved_references,
       COUNT(DISTINCT candidate_id) AS used_candidates,
       COUNT(DISTINCT event_id) AS referenced_events
  FROM historical_evidence
 WHERE fact_review_status = 'APPROVED'
   AND rubric_review_status = 'APPROVED'
   AND reference_status = 'APPROVED';

SELECT pillar, COUNT(*) AS snapshots, MIN(snapshot_date), MAX(snapshot_date)
  FROM pillar_snapshot
 WHERE pillar BETWEEN 1 AND 6
 GROUP BY pillar
 ORDER BY pillar;
```

기대값은 audit `record_count=120`, 승인 참조 120건이며, 연말 스냅샷은 여섯 필라에 같은 기간으로 존재해야 한다. `dataset_sha256`은 런타임 정규화 해시이므로 파일별 SHA와 다르며, 같은 데이터셋 버전에서 절대 바꾸지 않는다.

## 5. API·화면 확인

공개 타임라인에서 역사 사건 하나와 실시간 사건 하나를 확인한다.

- 역사: `kind=HISTORICAL_REFERENCE`, `인간 검수 사실 요약`, 출처 링크, 로케이터, 확인일을 표시하고 `<blockquote>`를 사용하지 않는다.
- 실시간: `kind=VERBATIM`, `원문 인용`을 표시하고 검증된 인용만 `<blockquote>`로 표시한다.
- YEAR/MONTH 정밀도는 각각 연도/연월만 표시하되 원본 `occurredOn`은 API에 보존한다.
- 동일 출처가 실시간과 역사 경로에 모두 있어도 `sourceCount`는 한 번만 센다.
- 외부 링크는 새 탭과 `noreferrer` 속성을 사용한다.
- 브라우저 콘솔 오류와 API 5xx가 없어야 한다.

## 6. 네트워크·시크릿 영향

백필은 클래스패스 리소스만 읽으므로 새 egress가 없다. V7의 역사 전용 FAA/UNOOSA/GOVINFO/LSA 행은 피드가 비활성이고 RSS/본문 도메인을 추가하지 않는다. 기존 NASA 실시간 피드 정책도 변경하지 않는다.

백필 자체에는 새 시크릿이 필요 없다. 실시간 수집과 관리자 화면을 함께 활성화할 때만 기존 Vault의 `TRACKER_FEEDS`와 `TRACKER_ADMIN_TOKEN`을 사용하며 값은 Git, 로그, URL, 브라우저 저장소에 기록하지 않는다.

## 7. 실패와 롤백

- Flyway 실패 또는 사전 쿼리 불일치: 활성화 PR을 진행하지 않고 이미지 변경 PR을 revert한다.
- 가져오기 검증 실패: 트랜잭션 전체가 롤백되어 `backfill_import` 행이 없어야 한다. 데이터 파일을 현장에서 수정하지 말고 코드/데이터 PR로 고친다.
- 같은 버전의 해시 불일치: 기존 버전을 덮어쓰지 않는다. 새 인간 검수와 새 데이터셋 버전을 만든다.
- UI/API 이상: `TRACKER_ENABLED=false` GitOps 변경으로 노출과 수집을 중단한다. V7/V8은 전진 호환 스키마로 유지한다.
- 이미 기록된 역사 행이나 audit 행을 운영 DB에서 수동 삭제하지 않는다.

모든 롤백도 Git revert와 Flux 조정으로 수행한다.
