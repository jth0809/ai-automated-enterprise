# Tracker 주간 역사 백필 검증 런북

작성: 2026-07-14. 대상: `backfill-v1` / `nodes-v1.0` / `r2.0` /
`weekly-projector-v1` / `params-v1`.

이 런북은 WP2.2-C가 만든 `pillar_snapshot` 주간 시계열을 읽기 전용으로
검증하는 절차다. 백필 입력은 애플리케이션 이미지에 포함된 로컬 JSON/JSONL만
읽으며, 실행 중 외부 네트워크를 호출하지 않는다. 운영 변경·비활성화·롤백은
반드시 Git 커밋과 Flux 조정으로 수행하고 운영 DB에서 행을 수동 수정하지 않는다.

## 1. 고정 계약과 예상 행 수

| 항목 | 값 |
|---|---:|
| 최초 스냅샷 월요일 | 1957-01-07 |
| 검증 기준 시각 | 2026-07-14 UTC |
| 직전 완료 월요일 | 2026-07-13 |
| 포함 주 수 | 3,628 |
| 필라 수 | 6 |
| 예상 주간 행 수 | 21,768 |
| 다음 완료 주마다 추가되는 행 | 6 |

포함 주 수 공식은
`floor((직전 완료 월요일 - 1957-01-07) / 7일) + 1`이다. 월요일에는 아직
끝나지 않은 당일을 제외해 전주 월요일까지, 화요일~일요일에는 그 주 월요일까지
투영한다. 따라서 2026-07-14 기준 계산은 `3,628 × 6 = 21,768`행이다.

각 행에는 `pillar`, `snapshot_date`, `readiness`, `logit_clipped`,
`params_version`만 채운다. 추세·윈도우·ETA 열은 `NULL`로 남겨 운영 스냅샷과
구조적으로 구분한다.

## 2. 멱등성·원자성·충돌 규칙

`ops_state.state_key='BACKFILL_WEEKLY_PROJECTION_V1'`의 값은 다음 형식이다.

```text
weekly-projector-v1:<canonical-dataset-sha256>:nodes-v1.0:r2.0:params-v1|<through-monday>
```

- fingerprint와 완료 월요일이 같으면 아무 행도 쓰지 않는다.
- 같은 fingerprint에서 한 주가 지나면 다음 월요일의 6행만 추가한다.
- 데이터셋·노드셋·루브릭·파라미터·projector 버전이 바뀌면 기존의 **역사
  전용 sparse 행만** 지우고 재생한다.
- 같은 월요일에 추세·윈도우·ETA 메타데이터가 있는 운영 행은 보존한다.
  재생한 readiness/logit/params와 다르면 데이터 전체를 롤백하고 실패한다.
- marker가 없거나 손상됐어도 사건·증거·`backfill_import`는 재삽입하지 않고
  주간 스냅샷만 복구한다.
- 재생·검사·marker 기록은 한 트랜잭션이다. 충돌이나 삽입 실패 시 이전 역사
  행과 marker가 함께 유지된다.

## 3. 저작권·저장량 경계

생산 리소스는 URL, locator, 접근일, SHA-256과 검수자가 직접 쓴 500자 이하의
사실 요약만 저장한다. 출처 본문·인용문·HTML·PDF·이미지·WARC는 저장하지 않는다.

| 리소스 | 크기 | 파일 SHA-256 |
|---|---:|---|
| `historical-candidates-v1.jsonl` | 214,159 bytes | `ac0a6e22a3ad423dd7fc1965ece5ff1655d01b4a2423564625d21217c1a0a4b3` |
| `backfill-v1.json` | 138,038 bytes | `56dc82771572b9da5f0759d1eff7a99d2400272c068ece0f8df2f2e56537e916` |
| `backfill-audit-v1.json` | 26,099 bytes | `72500987f1435c4e856dd2f03bd64fb97fcf422eadc70af1014e859482da72d3` |
| 합계 | 378,296 bytes | 원문 payload 0 bytes |

DB는 고정된 21,768개 sparse 행이다. 행·인덱스·블록 여유를 실제 논리
payload보다 훨씬 큰 **행당 1 KiB**로 잡아도 초기 상한 추정은 약 21.3 MiB다.
이후 증가는 연 52주 기준 312행, 같은 보수 가정으로 약 0.31 MiB/년이다.
이는 용량 목표가 아니라 과대 추정한 안전 계획값이며, 실제 ATP 사용량은 아래
`USER_SEGMENTS` 읽기 쿼리로 확인한다.

## 4. 릴리스 전 자동 검증

저장소에 캐시된 Maven과 의존성만 사용한다.

```powershell
# apps/backend/springboot-app
& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' `
  '-Dtest=BackfillAuditValidatorTest,BackfillDatasetValidatorTest,HistoricalProductionCorpusTest,WeeklyBackfillProjectorTest,BackfillLoaderTest' test

& $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test

# 저장소 루트
& 'gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1'
git diff --check
```

필수 회귀는 다음을 검증한다.

- 1957-01-07부터 직전 완료 월요일까지 빈 주 없이 월요일만 생성
- 사건 전 0, 전진, rollback, 대표 프로그램 종료, 15년 휴면, 후속 복원 경계
- 최종 주간 replay와 감사 행렬 및 fresh DB의 35개 현재 상태 일치
- 같은 fingerprint no-op, 다음 주 6행 append, projector 변경 시 원자 재생성
- 기존 비월요일 역사 행 제거, 일치하는 운영 월요일 행 보존, 충돌 전체 롤백
- 후보 213/READY 213/REJECTED 0, 청구 147, 감사 35/35
- 테스트 중 네트워크 호출 0, 새 egress·시크릿 0

## 5. H2·ATP 읽기 전용 검증 SQL

다음 쿼리는 행을 변경하지 않는다. 2026-07-14 기준 기대값은 각 필라 3,628행,
전체 21,768행, 최소 1957-01-07, 최대 2026-07-13이다.

```sql
SELECT pillar,
       COUNT(*) AS snapshot_count,
       MIN(snapshot_date) AS first_monday,
       MAX(snapshot_date) AS through_monday
  FROM pillar_snapshot
 WHERE pillar BETWEEN 1 AND 6
 GROUP BY pillar
 ORDER BY pillar;

SELECT COUNT(*) AS total_weekly_rows
  FROM pillar_snapshot
 WHERE pillar BETWEEN 1 AND 6
   AND snapshot_date BETWEEN DATE '1957-01-07' AND DATE '2026-07-13';

SELECT pillar, snapshot_date, COUNT(*) AS duplicate_count
  FROM pillar_snapshot
 WHERE pillar BETWEEN 1 AND 6
 GROUP BY pillar, snapshot_date
HAVING COUNT(*) > 1;

SELECT state_key, state_value, updated_at
  FROM ops_state
 WHERE state_key = 'BACKFILL_WEEKLY_PROJECTION_V1';
```

ATP에서는 월요일 cadence와 7일 연속성을 추가 확인한다. 두 쿼리 모두 기대값은
0행이다.

```sql
SELECT pillar, snapshot_date
  FROM pillar_snapshot
 WHERE pillar BETWEEN 1 AND 6
   AND snapshot_date BETWEEN DATE '1957-01-07' AND DATE '2026-07-13'
   AND TRUNC(snapshot_date, 'IW') <> snapshot_date;

SELECT pillar, snapshot_date, previous_date
  FROM (
        SELECT pillar,
               snapshot_date,
               LAG(snapshot_date) OVER (
                   PARTITION BY pillar ORDER BY snapshot_date) AS previous_date
          FROM pillar_snapshot
         WHERE pillar BETWEEN 1 AND 6
           AND snapshot_date BETWEEN DATE '1957-01-07' AND DATE '2026-07-13'
       )
 WHERE previous_date IS NOT NULL
   AND snapshot_date - previous_date <> 7;
```

최종 주 준비도는 감사 결과와 같은 값이어야 한다.

| 필라 | 2026-07-13 readiness |
|---|---:|
| P1 | 0.4624 |
| P2 | 0.1884 |
| P3 | 0.1524 |
| P4 | 0.1920 |
| P5 | 0.2991 |
| P6 | 0.2667 |

```sql
SELECT pillar, readiness, logit_clipped, params_version
  FROM pillar_snapshot
 WHERE snapshot_date = DATE '2026-07-13'
   AND pillar BETWEEN 1 AND 6
 ORDER BY pillar;
```

실제 ATP segment 크기는 다음 읽기 쿼리로 기록한다. 공유 테이블이므로 결과는
백필 단독 크기가 아니라 `PILLAR_SNAPSHOT` 테이블·인덱스 전체 크기다.

```sql
SELECT segment_name, segment_type, bytes
  FROM user_segments
 WHERE segment_name = 'PILLAR_SNAPSHOT'
    OR segment_name IN (
       SELECT index_name
         FROM user_indexes
        WHERE table_name = 'PILLAR_SNAPSHOT'
    )
 ORDER BY segment_type, segment_name;
```

## 6. GitOps 롤아웃과 실패 처리

1. CI가 위 테스트를 통과한 이미지 digest를 발행한다.
2. GitOps PR로 이미지를 갱신하되 먼저 `TRACKER_ENABLED=false`에서 Flyway가
   정상임을 확인한다.
3. 별도 GitOps PR로 tracker를 활성화하고 최초 로그의
   `tracker backfill imported 147 reviewed claims from dataset backfill-v1`을
   한 번 확인한다.
4. 재기동 시 import 로그가 반복되지 않고 marker와 행 수가 그대로인지 확인한다.
5. marker만 유실된 훈련 환경에서는 사건·증거 수가 변하지 않은 채
   `tracker weekly backfill projection repaired ...` 로그만 나와야 한다.

검증 실패, 운영 스냅샷 충돌 또는 해시 불일치가 발생하면 현장에서 DB를 고치지
않는다. tracker 활성화 GitOps 변경을 revert하고 코드·데이터 PR에서 원인을
수정한다. 백필은 새 외부 연결이나 시크릿을 요구하지 않으므로 CNP·Vault 변경도
없다.
