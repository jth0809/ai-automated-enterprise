# WP0.2 — Tracker 파이프라인 아키텍처

> 기준: 컨셉 v2.11 / 시행계획 v1.0 / WP0.1 데이터 모델. 원칙: 기존 `news` 도메인의 검증된 패턴을 재사용하고, 신규 인프라(큐, 워커 파드)를 추가하지 않는다 (free tier 리소스 제약).

## 1. 배치 결정: 신규 파드 없음, DB-as-queue

- **실행 위치:** 기존 `apps/backend/springboot-app` Deployment 내부의 in-process `@Scheduled` 잡 (news 도메인과 동일 패턴 — `NewsIngestionScheduler` 참조). **K8s CronJob을 만들지 않는다** — free tier CPU request 포화 이력상 신규 파드는 최후 수단.
- **큐:** Kafka가 클러스터에 있으나 사용하지 않는다(YAGNI — 일 2,000건 규모에 브로커 개입은 과잉). `article.pipeline_status` 컬럼이 상태 기계이며, 처리 잡이 폴링한다.
- **이중 기동 안전:** Flagger 캐너리가 primary/canary 파드를 동시에 띄우므로, 모든 잡은 **ShedLock**(DB 락, WP0.1 `shedlock` 테이블)으로 단일 실행을 보장한다. 멱등 제약(unique key)이 2차 방어선.

## 2. 패키지 구조

```
com.aienterprise.backend.tracker
├── domain/        # JPA 엔티티 + 리포지토리 (WP0.1 테이블 1:1)
├── ingest/        # Stage 0: TrackerFeedScheduler, BodyExtractor(범용 추출기)
├── evaluate/      # Stage 1+2: RelevanceGate, DeepClassifier, AnthropicClient(공통), CostGuard
├── event/         # 병합+검증 관문: EventMerger, VerificationDeriver, ProvisionalExpiryJob
├── scoring/       # Stage 3: ImpactScorer(결정론), StateUpdater, FlukeFilter, ReviewQueueService
├── math/          # ReadinessCalculator, LogitRegression, EtaCalculator, SnapshotJob
├── api/           # TrackerController(공개 조회), TrackerAdminController(검수 큐)
├── ops/           # CircuitBreaker(P2), DeadmanCheck(P2), ControlChart(P2)
└── config/        # TrackerConfig (news의 NewsConfig 패턴: 미설정 시 안전 무동작)
```

**news 도메인 재사용 지점:** `FeedFetcher`/`HttpFeedFetcher`/`RssParser`는 public이므로 그대로 주입해 재사용. Anthropic 호출은 `AnthropicSummarizer`의 RestClient 패턴(x-api-key + anthropic-version 헤더, 실패 시 무해 강등)을 따르되, tracker는 structured outputs가 필요하므로 별도 `AnthropicClient`를 evaluate/에 구현. `NewsConfig.parseFeeds()`의 `source|url` CSV 파싱 규약을 그대로 사용 (`TRACKER_FEEDS` env).

## 3. 잡 스케줄 (UTC, 시간대 분산 — free tier CPU 경합 회피)

| 잡 | 크론 | 동작 | Phase |
|----|------|------|-------|
| `tracker-ingest` | `0 10 * * * *` (매시 :10 — news 잡 :00과 분산) | 피드 수집 → article 멱등 삽입, BODY 도메인 정책으로 PENDING/SKIPPED 결정 | P1 |
| `tracker-body-extraction` | `0 15 * * * *` (Phase 1b 구현) | PENDING 기사 ≤30건: 허용 호스트 페치 → 범용 추출 → EXTRACTED / SKIPPED(정책·부적합) / 3회 실패 시 FAILED. 추출 상태가 종결되기 전에는 게이트에 비노출 | P1b |
| `tracker-process` | `0 */15 * * * *` | INGESTED 기사 배치(틱당 ≤30건): 게이트→분류→병합→검증 도출→채점→상태 갱신 | P1 |
| `tracker-fluke-filter` | `0 57 * * * *` (Phase 1b 구현, `tracker.fluke-enabled` 기본 false) | PENDING 검수의 2차 컨텍스트 평가: MATCH/MISMATCH + 인용 코드 재검증 → `fluke_evaluation` 감사 기록, mismatch는 priority 1, 비용 캡 소진은 카운터 무변 유보, 3회 실패 시 FAILED/priority 2. 어떤 판정도 상태를 자동 전진/반려하지 않음 | P1b |
| `tracker-snapshot` | `0 30 0 * * MON` | 주간 준비도 스냅샷 + 회귀 + ETA + 감쇠 표시값 갱신 | P1 |
| `tracker-expiry` | `0 10 1 * * *` | 잠정 사건 90일 소멸 처리 | P1 |
| `tracker-dormancy` | `0 20 1 1 * *` (월 1회) | 휴면 진입/감쇠 재계산 | P2 |
| `tracker-deadman` | `0 40 1 * * *` | 피드 신선도 SLO 점검 → 경보 | P2 |
| `tracker-control` | `0 50 1 * * *` | 관리도(게이트 통과율·사건 수·점수 분포) → 서킷 브레이커 판정 | P2 |
| `tracker-goldenset` | `0 0 2 * * SUN` | 골든셋 재평가 → 일치율 → 서킷 브레이커 입력 | P2 |
| `tracker-coherence` | 분기 수동 트리거 + `0 0 3 1 */3 *` | B쌍 정합 검사 | P3 |

## 4. 스테이지 상세 (tracker-process 내부, 기사 단위 트랜잭션)

```
[INGESTED] ─게이트(Haiku)─> 무관: GATE_REJECTED (종료)
                          └> 관련: GATE_PASSED
[GATE_PASSED] ─심층 분류(Opus, structured outputs)─> article_classification 행들 (노드별 1행)
   ├ 인용문 부분 문자열 대조 실패 or 미등록 node_code → 해당 분류 행 기각(quote_verified='N', event 미생성)
   └ 성공 → [CLASSIFIED]
[CLASSIFIED 분류행] ─병합─> natural_key upsert:
   ├ 기존 사건 존재 → classification.event_id 연결, 사건의 verification_level 재도출(클러스터 재평가)
   └ 신규 → event 생성 (PROVISIONAL, expires=+90d)
[사건 verification 변경 시] ─검증 관문(코드)─>
   ├ PEER_REVIEWED 미만 → 잠정 유지 (상태 불변)
   └ PEER_REVIEWED 이상 → 채점(ImpactScorer) → novelty 판정 →
       ├ impact≥8 or 2단계+ 점프 or 8~9레벨 도달 → FlukeFilter(2차 평가) → review_queue (상태 보류)
       ├ ROLLBACK(P6) → OFFICIAL 이상 확인 후 레벨 하향
       └ 그 외 → StateUpdater: node level 전진 + history 기록 (CONFIRMED)
[검수 큐 APPROVED] → StateUpdater 실행 / REJECTED → event REJECTED
```

- **오류 격리:** 기사 1건 실패는 catch → `fail_count++`, 3회 초과 시 `FAILED`(주간 리뷰 대상). 잡 전체는 계속 진행 (news 도메인의 피드 격리 원칙과 동일).
- **서킷 브레이커(P2):** `ops_state.STATE_FROZEN='Y'`면 StateUpdater가 no-op — 사건은 PROVISIONAL로 적체되고 해제 시 재처리. 멱등 설계 덕에 동결 비용 = 지연.
- **도달 판정 검사:** StateUpdater가 전이 후 "전 핵심 노드 ≥8 + 12개월 유지" 조건을 검사, 충족 시 `review_queue(reason=ARRIVAL_CANDIDATE)` — 자동 선언 금지(컨셉 1절).

## 5. Anthropic 호출 계약

**공통:** `POST https://api.anthropic.com/v1/messages`, 헤더 `x-api-key`(Vault ESO의 기존 `ANTHROPIC_API_KEY`), `anthropic-version: 2023-06-01`. 모든 호출 전 `CostGuard.check()` — `llm_usage` 당일 합산이 `daily_cost_cap_usd` 초과 시 처리 중지(다음 틱 재시도) + 경보 로그.

**Stage 1 게이트** — 모델 `${tracker.gate-model:claude-haiku-4-5-20251001}` (기존 키 재사용), max_tokens 8, 출력 `YES`/`NO` 단일 토큰 판정. 프롬프트는 WP0.3.

**Stage 2 심층 분류** — 모델 `${tracker.classify-model:claude-opus-4-8}`, **tool use 강제(tool_choice)로 structured outputs 구현**, 시스템 프롬프트(고정 루브릭+앵커)에 `cache_control: {type: "ephemeral"}` 지정 → 프롬프트 캐싱. tool `input_schema` (v1 계약):

```json
{
  "type": "object",
  "required": ["relevant_claims"],
  "properties": {
    "relevant_claims": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["node_code", "event_type", "claimed_level", "actor",
                     "occurred_on", "publication_path", "evidence_quote", "duplicate_hint"],
        "properties": {
          "node_code":       { "type": "string", "description": "역량 노드 코드 (등록 목록 중에서만)" },
          "event_type":      { "type": "string", "enum": ["THEORY_PAPER","LAB_RESULT","PROTOTYPE_DEMO",
                               "FLIGHT_TEST","OPERATIONAL_DEPLOYMENT","COMMERCIALIZATION",
                               "INSTITUTIONAL_ADVANCE","SETBACK","PROGRAM_CANCELLATION",
                               "ANNOUNCEMENT_ONLY","RETROSPECTIVE","ROLLBACK"] },
          "claimed_level":   { "type": "integer", "minimum": 1, "maximum": 9 },
          "actor":           { "type": "string" },
          "occurred_on":     { "type": "string", "description": "YYYY-MM-DD, 사건 실제 발생일 (보도일 아님)" },
          "publication_path":{ "type": "string", "enum": ["PRIMARY","THIRD_PARTY","WIRE_REPRINT"] },
          "evidence_quote":  { "type": "string", "description": "기사 원문에서 그대로 발췌한 근거 문장" },
          "duplicate_hint":  { "type": "string", "description": "최근 사건 목록 중 동일 사건의 natural_key, 없으면 'NEW'" }
        }
      }
    }
  }
}
```

숫자 점수 필드는 스키마에 **존재하지 않는다**(LLM은 채점하지 않음 — 컨셉 원칙 2). `relevant_claims: []`는 유효 출력(게이트 오탐의 2차 방어). 유저 메시지에는 기사 본문 + 해당 노드의 최근 사건 natural_key 목록(중복 대조용, 최대 20건)을 포함한다.

**Batch API(P2 백필):** `POST /v1/messages/batches`에 동일 계약으로 제출. 백필 전용 잡은 수동 트리거.

## 6. 수학 잡 (tracker-snapshot, P1 단순판)

1. 노드별 `r = trl_map(level) × dormancy_factor` (P1: r_eff = r — DAG는 P4).
2. 필라 `R_p = Σ w·r`, 전체 = min (pillar 0 행).
3. `logit(clip(R, ε, 1−ε))` 시계열(주간)에 **고정 10년 윈도우 지수 감쇠 가중 최소제곱** → trend_fit(=trend_used).
4. `ETA_p = snapshot_year + (logit(R_target) − logit_now) / trend_yearly` → 클램프 [now+2, now+150] (상회 시 eta_year NULL = ">2175" 표시).
5. 전체 ETA = max_p, 잔차 표준오차 기반 80% 구간 (몬테카를로는 P4).
6. 표시값: `displayed = prev_displayed ± min(|Δ|, 90일 × 경과일)` → `ops_state.LAST_DISPLAYED_ETA` 갱신.
7. `R_target`: 전 핵심 노드 level 8 기준 가중 준비도 (노드 세트에서 계산).

## 7. API 표면 (react-app 대시보드 계약)

| 엔드포인트 | 응답 요지 |
|---|---|
| `GET /api/tracker/summary` | { displayedEtaYear, etaLow, etaHigh, label:"현 추세 지속 시나리오 기준 · 모델 내 80% 구간", overallReadiness, bottleneckPillar, frozen:bool } |
| `GET /api/tracker/pillars` | [ { pillar, name, readiness, etaYear, momentum } ×6 ] |
| `GET /api/tracker/events?limit=50` | 타임라인: [ { occurredOn, nodeName, eventType, levelFrom, levelTo, impactScore, verificationLevel, sourceCount, evidenceQuote } ] |
| `GET /api/tracker/admin/review` (인증) | 검수 큐 목록 — Phase 1b부터 **ReviewCase 프로젝션**: 검수 행 + 사건/노드/현재·제안 레벨 + 검증 수준·임팩트 + 플루크 상태·판정 + 출처 수 + 인용 검증된 증거(제목·URL·인용). priority DESC → 오래된 순 |
| `POST /api/tracker/admin/review/{id}` (인증) | { decision: APPROVE\|REJECT, note } — Phase 1b부터 REJECT는 공백 아닌 note 필수, note ≤2000자, PENDING 외 결정은 409 |

인증은 기존 resume 도메인의 접근 코드 패턴 또는 헤더 시크릿 재사용 — WP1.6 상세 플랜에서 확정. 공개 조회는 무인증(대시보드 공개 제품).

## 8. 설정 키 (TrackerConfig — news 패턴: 미설정 시 안전 무동작)

```
tracker.feeds            (env TRACKER_FEEDS, Vault·ESO — 'source|url,...' CSV. 빈 값 = 수집 안 함)
tracker.gate-model       (기본 claude-haiku-4-5-20251001)
tracker.classify-model   (기본 claude-opus-4-8)
anthropic.api-key        (기존 키 재사용)
tracker.enabled          (기본 false — P1 배포 시 true 전환)
```
