# Tracker Phase 1b 검증·롤아웃 런북

작성: 2026-07-13. 대상: Phase 1b(WP1.2 수집 업그레이드 + WP1.6 플루크 필터·검수 UI) 파일 작업 완료 시점의 배포·검증 절차. 모든 클러스터 변경은 **Git 커밋 → Flux reconcile**로만 수행한다 — `kubectl apply` 금지.

## 1. 자동 검증 결과 (2026-07-13, feat/tracker-mvp)

| 검증 | 명령 | 결과 |
|---|---|---|
| 백엔드 전체 | `mvn -o -Dmaven.repo.local=... test` (springboot-app) | **187 tests, 0 failures/errors/skips** |
| 프론트엔드 전체 | `npm test` (react-app) | **42 tests, 9 files, all green** |
| 프로덕션 빌드 | `npm run build` (tsc + vite) | 통과 |
| 매니페스트 정책 | `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1` | OK (16호스트 집합 일치, PR-1 안전 기본값, 시크릿 부재) |
| 공백/충돌 | `git diff --check` | clean |

자동 테스트는 외부 피드·LLM에 절대 접속하지 않는다(모킹·픽스처 전용).

## 2. 요구사항 → 증거 매핑

| 요구 | 자동 증거 (완료) | 운영 증거 (미완 — 배포 후 기록) |
|---|---|---|
| 피드 12개 | V4 시드 + TrackerFeedPolicyTest(활성 12·정책 백킹) + 기동 시 미등록 피드 거부 + CNP 검증 스크립트 | 24시간 수집 관찰(유실·중복 0) |
| 전문 추출 | 4개 레이아웃 픽스처(증거 문장 보존·보일러플레이트 제거) + 수명주기 통합 테스트(EXTRACTED/SKIPPED/FAILED) | 실기사 1건 `body_extracted='Y'` 확인 |
| 갭 내성 | 기사 단위·피드 단위 실패 격리 테스트 | 피드 1개 중단 시 파이프라인 무영향 관찰 |
| 플루크 필터 | 모킹 계약 테스트(판정·인용 검증·비용 유보·3회 재시도) + 잡 통합 테스트 | 스테이징 고임팩트 케이스 1건이 검수 큐 도달 |
| 인간 검수 | 검수 API 8 테스트 + React 패널 8 테스트 + 로컬 실HTTP 401/빈 큐 확인 | 스테이징 승인·반려 각 1건 기록 |
| **G1 (실뉴스 E2E)** | — (자동 증거로 대체 불가) | 실기사 1건: 피드→추출→게이트→분류→병합→검증→채점→스냅샷→대시보드 전 구간 캡처 |

**G1a·G1은 미충족 상태다.** 로컬 H2/데모 증적은 코드 경로만 증명하며, 실뉴스 무인 운영 증거를 대체하지 않는다.

## 3. 배포 절차 (2단계, Flux 전용)

### 사전 조건 — OCI Vault 키 (값은 Git 외부, 수동 생성)

| Vault 키 | 용도 |
|---|---|
| `TRACKER_FEEDS` | `SOURCE\|https-url,...` CSV — 12개 활성 피드. 미등록 source/host 조합은 기동 시 거부됨 |
| `TRACKER_ADMIN_TOKEN` | `/api/tracker/admin/*` 헤더 토큰 (빈 값 = 전면 거부) |
| `ANTHROPIC_API_KEY` | 기존 키 재사용 (게이트·분류·플루크) |

ESO는 원자적으로 동기화하므로 **키 생성이 매니페스트 머지보다 먼저**여야 한다.

### 1단계 — 스키마·정책 배포 (수집·신규 잡 비활성)

1. PR 머지: CNP 16호스트 + externalsecret(TRACKER_*) + deployment env(`TRACKER_ENABLED=false`, `TRACKER_FLUKE_ENABLED=false`, `SPRING_FLYWAY_ENABLED=true`, 추출 한도).
2. Flux reconcile 확인: ESO Secret 렌더 성공, CiliumNetworkPolicy 적용.
3. 이미지 릴리스(CI) → 기동 로그에서 Flyway V1~V8 적용과 "tracker disabled" 확인. 참조형 역사 백필의 별도 게이트와 쿼리는 `tracker-reference-backfill-validation.md`를 따른다.

### 2단계 — 수집 활성화, 이후 플루크 활성화

4. PR: `TRACKER_ENABLED=true` → reconcile → 관찰:
   - ingest 로그에서 12피드 수집, `article` 적재, `body_extraction_status` 분포(PENDING→EXTRACTED/SKIPPED/FAILED).
   - 24시간: 중복 URL 0, 한 피드 장애가 다른 피드에 비전파.
5. Anthropic 계약 점검(모킹/스테이징) 통과 후 별도 PR: `TRACKER_FLUKE_ENABLED=true`.
   - 비용은 기존 `daily_cost_cap_usd`(CostGuard)가 공유 상한.

### 롤백

각 단계는 해당 PR revert → Flux reconcile로 되돌린다. 플루크 비활성화는 상태 전진에 영향 없음(어차피 인간 결정 필수). 수집 비활성화는 신규 적재만 멈추고 기존 데이터·API는 유지.

## 4. G1 증거 수집 명령 (배포 후)

```sql
-- 실기사 1건의 전 구간 추적 (article id로 치환)
SELECT pipeline_status, body_extraction_status, body_extracted FROM article WHERE id = :id;
SELECT c.node_code, c.quote_verified, c.event_id FROM article_classification c WHERE c.article_id = :id;
SELECT e.natural_key, e.verification_level, e.event_status, e.impact_score FROM event e WHERE e.id = :eventId;
SELECT * FROM node_state_history WHERE cause_event_id = :eventId;
SELECT pillar, readiness, eta_year, displayed_eta_year FROM pillar_snapshot ORDER BY snapshot_date DESC FETCH FIRST 7 ROWS ONLY;
```

대시보드 캡처(시계·레이더·타임라인)와 위 쿼리 결과를 함께 보관한다. 고임팩트 스테이징 케이스는 `review_queue`/`fluke_evaluation` 행과 검수 UI 결정 기록으로 증빙한다.

## 5. 신규 운영 파라미터 (비밀 아님)

| env | 기본 | 의미 |
|---|---|---|
| `TRACKER_EXTRACT_CRON` | `0 15 * * * *` | 본문 추출 잡 주기 |
| `TRACKER_EXTRACT_BATCH_SIZE` | `30` | 틱당 추출 건수 |
| `TRACKER_FLUKE_ENABLED` | `false` | 플루크 필터 잡 게이트 (false여도 검수 큐 적재는 정상) |
| `TRACKER_FLUKE_MODEL` | classify-model 상속 | 플루크 모델 재정의 |

호스트 변경 시 세 곳 동기화: Flyway 시드(`source_domain`) ↔ CNP(`network-policy.yaml`) ↔ 검증 스크립트(`tracker-egress-policy.ps1`).
