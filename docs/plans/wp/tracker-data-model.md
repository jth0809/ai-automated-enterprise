# WP0.1 — Tracker 데이터 모델 (ERD + ATP DDL 초안)

> 기준: 컨셉 v2.11 / 시행계획 v1.0. 대상 DB: Oracle ATP (기존 backend 데이터소스 재사용 — `SPRING_DATASOURCE_*`는 이미 Vault+ESO로 주입됨). 전 테이블을 Phase 0에서 확정하되, P2~P4 전용 테이블은 표기만 하고 생성은 동일 마이그레이션에 포함한다(스키마 변경이 가장 비싸므로 선확정 — 시행계획 결정).

## 설계 원칙

1. **멱등성은 제약으로 보장** — `article.url_hash UNIQUE`, `event.natural_key UNIQUE`. 중복 투입은 에러가 아니라 no-op.
2. **모든 평가에 버전 스탬프** — `rubric_version_id` FK를 분류·사건·상태 이력에 강제.
3. **상태는 갱신, 이력은 append-only** — `capability_node`가 현재 상태, `node_state_history`가 전체 감사 추적.
4. **파라미터는 데이터** — 보정 파라미터(ε, k, m, 휴면 곡선, trl_map)는 코드 상수가 아니라 `parameter_set` 행 (몬테카를로 섭동·백테스트 보정 대상이므로).
5. **캐너리 이중 기동 안전** — Flagger가 primary/canary 파드를 동시에 띄우므로 스케줄 잡은 ShedLock으로 단일 실행 보장(`shedlock` 테이블).

## ERD (개요)

```
source_registry ──< source_domain
       │
       └──< article ──< article_classification >── event >── capability_node
                                                             │              │
                                       review_queue >────────┘              ├──< capability_edge (DAG)
                                                                             └──< node_state_history
rubric_version ──< (classification, event, history)
parameter_set                     pillar_snapshot(주간)        ops_state / llm_usage / shedlock
[P2] golden_set_item     [P3] hard_metric / k_index / external_forecast     [P4] prediction
```

## DDL 초안 — Phase 1 코어

```sql
CREATE TABLE source_registry (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          VARCHAR2(64)  NOT NULL UNIQUE,          -- 'NASA', 'SPACENEWS'
  name          VARCHAR2(200) NOT NULL,
  source_type   VARCHAR2(20)  NOT NULL CHECK (source_type IN
                  ('AGENCY','JOURNAL','PREPRINT','SPECIALIZED_MEDIA','GENERAL_MEDIA','CORPORATE')),
  tier          NUMBER(1)     NOT NULL CHECK (tier IN (1,2,3)),
  rss_url       VARCHAR2(500),                          -- NULL = 피드 아님(주체 매핑용 레지스트리 항목)
  site_domain   VARCHAR2(200) NOT NULL,                 -- 본문 추출 egress 허용 도메인
  feed_active   CHAR(1) DEFAULT 'Y' NOT NULL CHECK (feed_active IN ('Y','N')),
  median_publish_interval_hours NUMBER,                 -- 데드맨 스위치 기준 (P2)
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE source_domain (
  id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_id NUMBER NOT NULL REFERENCES source_registry(id),
  domain    VARCHAR2(253) NOT NULL,
  purpose   VARCHAR2(5) NOT NULL CHECK (purpose IN ('FEED','BODY','BOTH')),
  active    CHAR(1) DEFAULT 'Y' NOT NULL CHECK (active IN ('Y','N')),
  CONSTRAINT uq_source_domain UNIQUE (source_id, domain)
);

CREATE TABLE rubric_version (
  id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version_label        VARCHAR2(40) NOT NULL UNIQUE,    -- 'r1.0'
  gate_model           VARCHAR2(64) NOT NULL,
  classify_model       VARCHAR2(64) NOT NULL,
  gate_prompt_sha256   CHAR(64) NOT NULL,
  classify_prompt_sha256 CHAR(64) NOT NULL,
  node_set_version     VARCHAR2(40) NOT NULL,           -- 'nodes-v0.1' (20노드) → 'nodes-v1.0' (35노드, P2)
  active               CHAR(1) DEFAULT 'N' NOT NULL CHECK (active IN ('Y','N')),
  notes                VARCHAR2(2000),
  created_at           TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE parameter_set (
  id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version_label     VARCHAR2(40) NOT NULL UNIQUE,       -- 'params-v1'
  active            CHAR(1) DEFAULT 'N' NOT NULL CHECK (active IN ('Y','N')),
  epsilon           NUMBER(4,3)  DEFAULT 0.010 NOT NULL, -- ε 클리핑
  k_shrink          NUMBER(4,1)  DEFAULT 4    NOT NULL,  -- 수축 강도 (P4)
  window_m          NUMBER(3)    DEFAULT 6    NOT NULL,  -- W_p 계수 (P4; P1은 고정 창)
  window_fixed_years NUMBER(3)   DEFAULT 10   NOT NULL,  -- P1 단순판 고정 윈도우
  window_min_years  NUMBER(3)    DEFAULT 4    NOT NULL,
  window_max_years  NUMBER(3)    DEFAULT 15   NOT NULL,
  dormancy_start    NUMBER(3,2)  DEFAULT 0.85 NOT NULL,
  dormancy_step_per_decade NUMBER(3,2) DEFAULT 0.15 NOT NULL,
  dormancy_floor    NUMBER(3,2)  DEFAULT 0.40 NOT NULL,
  dormancy_trigger_years NUMBER(3) DEFAULT 15 NOT NULL,
  default_delta_e   NUMBER(4,3)  DEFAULT 0.150 NOT NULL,
  eta_clamp_min_years NUMBER(3)  DEFAULT 2    NOT NULL,
  eta_clamp_max_years NUMBER(4)  DEFAULT 150  NOT NULL,
  display_damping_days_per_day NUMBER(3) DEFAULT 90 NOT NULL,
  daily_cost_cap_usd NUMBER(6,2) DEFAULT 20  NOT NULL,
  trl_map           JSON NOT NULL,   -- {"1":0.03,...,"9":1.0}
  maturity_map      JSON NOT NULL,   -- EGL용 (초기값 = trl_map과 동일 곡선, 백테스트 보정 대상)
  created_at        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE capability_node (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          VARCHAR2(64)  NOT NULL UNIQUE,          -- 'P1-ORBIT-REFUEL'
  pillar        NUMBER(1)     NOT NULL CHECK (pillar BETWEEN 1 AND 6),
  name_ko       VARCHAR2(200) NOT NULL,
  scale_type    VARCHAR2(3)   NOT NULL CHECK (scale_type IN ('TRL','EGL')),
  current_level NUMBER(1) DEFAULT 0 NOT NULL CHECK (current_level BETWEEN 0 AND 9), -- 0 = 미착수
  verification_level VARCHAR2(20) CHECK (verification_level IN
                  ('CLAIMED','PEER_REVIEWED','OFFICIAL','INDEPENDENT')),
  node_status   VARCHAR2(10) DEFAULT 'ACTIVE' NOT NULL CHECK (node_status IN ('ACTIVE','DORMANT')),
  dormant_since DATE,
  program_end_date DATE,                                -- 휴면 카운트다운 기점
  weight        NUMBER(6,4) NOT NULL,                   -- w_c (필라 내 합=1은 앱 레벨 검증)
  is_integration_node CHAR(1) DEFAULT 'N' NOT NULL CHECK (is_integration_node IN ('Y','N')),
  description   VARCHAR2(2000),
  node_set_version VARCHAR2(40) NOT NULL,
  updated_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE capability_edge (                          -- DAG (P4 활성, 스키마 선확정)
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  to_node_id    NUMBER NOT NULL REFERENCES capability_node(id),  -- 후행
  from_node_id  NUMBER NOT NULL REFERENCES capability_node(id),  -- 선행
  or_group      NUMBER(2) DEFAULT 1 NOT NULL,           -- 동일 (to,group) 내 OR(max), 그룹 간 AND(min)
  delta_e       NUMBER(4,3) DEFAULT 0.150 NOT NULL,     -- 간선별 허용 선행 마진
  CONSTRAINT uq_edge UNIQUE (to_node_id, from_node_id),
  CONSTRAINT ck_no_self CHECK (to_node_id != from_node_id)
);

CREATE TABLE article (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_id     NUMBER NOT NULL REFERENCES source_registry(id),
  url           VARCHAR2(1000) NOT NULL,
  url_hash      CHAR(64) NOT NULL UNIQUE,               -- SHA-256(url) — 멱등 삽입
  title         VARCHAR2(1000),
  published_at  TIMESTAMP,
  fetched_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  body          CLOB,                                   -- 원문 보존 (루브릭 개정 시 전체 재평가용)
  body_extracted CHAR(1) DEFAULT 'N' NOT NULL CHECK (body_extracted IN ('Y','N')), -- N이면 RSS 요약만
  body_extraction_status VARCHAR2(10) DEFAULT 'SKIPPED' NOT NULL CHECK
                  (body_extraction_status IN ('PENDING','EXTRACTED','SKIPPED','FAILED')),
  body_extraction_attempts NUMBER(2) DEFAULT 0 NOT NULL CHECK
                  (body_extraction_attempts BETWEEN 0 AND 99),
  body_extraction_error VARCHAR2(1000),
  pipeline_status VARCHAR2(20) DEFAULT 'INGESTED' NOT NULL CHECK (pipeline_status IN
                  ('INGESTED','GATE_REJECTED','GATE_PASSED','CLASSIFIED','FAILED')),
  fail_count    NUMBER(2) DEFAULT 0 NOT NULL,
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ix_article_status ON article(pipeline_status, fetched_at);

CREATE TABLE event (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  natural_key   VARCHAR2(240) NOT NULL UNIQUE,          -- node_code|event_type|actor정규화|주bucket(발생일±7일)
  node_id       NUMBER NOT NULL REFERENCES capability_node(id),
  event_type    VARCHAR2(30) NOT NULL CHECK (event_type IN
                  ('THEORY_PAPER','LAB_RESULT','PROTOTYPE_DEMO','FLIGHT_TEST',
                   'OPERATIONAL_DEPLOYMENT','COMMERCIALIZATION','INSTITUTIONAL_ADVANCE',
                   'SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE','ROLLBACK')),
  claimed_level NUMBER(1) CHECK (claimed_level BETWEEN 1 AND 9),
  actor         VARCHAR2(200),
  occurred_on   DATE NOT NULL,
  verification_level VARCHAR2(20) NOT NULL CHECK (verification_level IN
                  ('CLAIMED','PEER_REVIEWED','OFFICIAL','INDEPENDENT')),
  event_status  VARCHAR2(20) DEFAULT 'PROVISIONAL' NOT NULL CHECK (event_status IN
                  ('PROVISIONAL','CONFIRMED','EXPIRED','REJECTED')),
  provisional_expires_on DATE,                          -- 최초 관측 + 90일
  impact_score  NUMBER(4,2),
  novelty       NUMBER(1) CHECK (novelty IN (0,1)),
  state_advanced CHAR(1) DEFAULT 'N' NOT NULL CHECK (state_advanced IN ('Y','N')),
  rubric_version_id NUMBER NOT NULL REFERENCES rubric_version(id),
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ix_event_node ON event(node_id, occurred_on);

CREATE TABLE article_classification (                   -- LLM 출력 원본 = event↔article 조인 겸용
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  article_id    NUMBER NOT NULL REFERENCES article(id),
  event_id      NUMBER REFERENCES event(id),            -- 병합 후 채움
  node_code     VARCHAR2(64) NOT NULL,                  -- 레지스트리에 없는 코드 → 기각 처리
  event_type    VARCHAR2(30) NOT NULL,                  -- event와 동일 enum (앱 검증)
  claimed_level NUMBER(1),
  actor         VARCHAR2(200),
  occurred_on   DATE,
  publication_path VARCHAR2(20) CHECK (publication_path IN ('PRIMARY','THIRD_PARTY','WIRE_REPRINT')),
  evidence_quote CLOB NOT NULL,
  quote_verified CHAR(1) NOT NULL CHECK (quote_verified IN ('Y','N')), -- 원문 부분 문자열 대조
  raw_output    JSON NOT NULL,                          -- structured output 전문
  rubric_version_id NUMBER NOT NULL REFERENCES rubric_version(id),
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ix_class_article ON article_classification(article_id);
CREATE INDEX ix_class_event   ON article_classification(event_id);

CREATE TABLE node_state_history (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  node_id       NUMBER NOT NULL REFERENCES capability_node(id),
  prev_level    NUMBER(1) NOT NULL,
  new_level     NUMBER(1) NOT NULL,
  prev_status   VARCHAR2(10) NOT NULL,
  new_status    VARCHAR2(10) NOT NULL,
  verification_level VARCHAR2(20) NOT NULL,
  cause_event_id NUMBER REFERENCES event(id),           -- NULL = 초기 감사/휴면 잡 등 시스템 전이
  rubric_version_id NUMBER NOT NULL REFERENCES rubric_version(id),
  changed_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE pillar_snapshot (                          -- pillar 0 = 전체(시스템) 행
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pillar        NUMBER(1) NOT NULL CHECK (pillar BETWEEN 0 AND 6),
  snapshot_date DATE NOT NULL,
  readiness     NUMBER(6,5) NOT NULL,                   -- R_p (유효 준비도 기반, P1은 r_eff=r)
  logit_clipped NUMBER(10,6) NOT NULL,
  trend_fit     NUMBER(12,8),
  trend_used    NUMBER(12,8),                           -- 수축·개입 적용 후 (P1은 = trend_fit)
  n_events_window NUMBER(4),
  window_years  NUMBER(3),
  eta_year      NUMBER(6,1),                            -- 클램프 적용 후. NULL = ">클램프 상한"
  eta_low       NUMBER(6,1),
  eta_high      NUMBER(6,1),
  displayed_eta_year NUMBER(6,1),                       -- 감쇠 적용 (pillar 0만 사용)
  params_version VARCHAR2(40) NOT NULL,
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_snapshot UNIQUE (pillar, snapshot_date)
);

CREATE TABLE review_queue (
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id      NUMBER NOT NULL REFERENCES event(id),
  reason        VARCHAR2(30) NOT NULL CHECK (reason IN
                  ('HIGH_IMPACT','LEVEL_JUMP','FLUKE_MISMATCH','ARRIVAL_CANDIDATE','CIRCUIT_BREAKER')),
  fluke_result  VARCHAR2(10) CHECK (fluke_result IN ('MATCH','MISMATCH')),
  status        VARCHAR2(10) DEFAULT 'PENDING' NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  reviewer_note VARCHAR2(2000),
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  resolved_at   TIMESTAMP
);

CREATE TABLE llm_usage (                                -- 일일 비용 가드 원장
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  usage_date    DATE NOT NULL,
  model         VARCHAR2(64) NOT NULL,
  calls         NUMBER(8)  DEFAULT 0 NOT NULL,
  input_tokens  NUMBER(12) DEFAULT 0 NOT NULL,
  output_tokens NUMBER(12) DEFAULT 0 NOT NULL,
  cached_tokens NUMBER(12) DEFAULT 0 NOT NULL,
  est_cost_usd  NUMBER(8,4) DEFAULT 0 NOT NULL,
  CONSTRAINT uq_usage UNIQUE (usage_date, model)
);

CREATE TABLE ops_state (                                -- 서킷 브레이커·감쇠 상태 등 K/V
  state_key     VARCHAR2(64) PRIMARY KEY,               -- 'STATE_FROZEN','FREEZE_REASON','LAST_DISPLAYED_ETA'
  state_value   VARCHAR2(4000),
  updated_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE shedlock (                                 -- 표준 ShedLock 스키마 (캐너리 이중 기동 대비)
  name          VARCHAR2(64) PRIMARY KEY,
  lock_until    TIMESTAMP NOT NULL,
  locked_at     TIMESTAMP NOT NULL,
  locked_by     VARCHAR2(255) NOT NULL
);
```

## DDL 초안 — 후속 페이즈 테이블 (P0에서 함께 생성)

```sql
CREATE TABLE golden_set_item (                          -- [P2]
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title         VARCHAR2(1000) NOT NULL,
  body          CLOB NOT NULL,                          -- 기사 스냅샷 (원문 소실 대비 자체 보존)
  expected_output JSON NOT NULL,                        -- 합의된 정답 라벨
  notes         VARCHAR2(2000),
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE hard_metric (                              -- [P3] Layer B
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  metric_code   VARCHAR2(40) NOT NULL,                  -- 'PRICE_PER_KG','UPMASS_T','LAUNCH_COUNT','PERSON_DAYS'
  period_start  DATE NOT NULL,
  metric_value  NUMBER(14,4) NOT NULL,
  unit          VARCHAR2(20) NOT NULL,
  source        VARCHAR2(200) NOT NULL,
  pillar        NUMBER(1) CHECK (pillar BETWEEN 1 AND 6),  -- NULL = 참고 지표(매핑 없음)
  is_reference_only CHAR(1) DEFAULT 'N' NOT NULL,
  ingestion_mode VARCHAR2(10) NOT NULL CHECK (ingestion_mode IN ('API','MANUAL')),
  created_at    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_metric UNIQUE (metric_code, period_start)
);

CREATE TABLE k_index (                                  -- [P3] Layer A
  obs_year      NUMBER(4) PRIMARY KEY,
  power_watts   NUMBER(20) NOT NULL,
  k_value       NUMBER(6,4) NOT NULL,
  accounting_basis VARCHAR2(20) NOT NULL CHECK (accounting_basis IN ('SUBSTITUTION','USEFUL')),
  source        VARCHAR2(200) NOT NULL
);

CREATE TABLE external_forecast (                        -- [P3]
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_type   VARCHAR2(15) NOT NULL CHECK (source_type IN ('CROWD','INSTITUTIONAL')),
  source_name   VARCHAR2(200) NOT NULL,
  question      VARCHAR2(500) NOT NULL,
  forecast_year NUMBER(6,1) NOT NULL,
  smoothed_year NUMBER(6,1),                            -- CROWD만: 수개월 이동 평균
  retrieved_on  DATE NOT NULL
);

CREATE TABLE prediction (                               -- [P4] 마이크로 예측
  id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  statement     VARCHAR2(1000) NOT NULL,
  node_id       NUMBER REFERENCES capability_node(id),
  probability   NUMBER(3,2) NOT NULL CHECK (probability BETWEEN 0 AND 1),
  issued_on     DATE NOT NULL,
  due_on        DATE NOT NULL,
  outcome       VARCHAR2(10) DEFAULT 'PENDING' NOT NULL CHECK (outcome IN ('PENDING','HIT','MISS','VOID')),
  brier         NUMBER(6,4),
  params_version VARCHAR2(40) NOT NULL
);
```

## 컨셉 필드 → 스키마 추적표 (완료 기준 증빙)

| 컨셉 조항·필드 | 스키마 위치 |
|---|---|
| 4절 노드 상태 {TRL/EGL, 검증, 신뢰도*, 활성/휴면, 갱신일} | `capability_node` (*신뢰도는 verification_level로 표현 — 별도 수치 신뢰도는 YAGNI 판정, G0 승인 대상) |
| 4절 DAG (AND/OR, δ_e) | `capability_edge.or_group / delta_e` |
| 4절 사건 {유형, 역량, 주체, 발생일, 검증, 인용, 출처 목록} | `event` + `article_classification`(인용·경로) |
| 4절 기사 원문 보존 | `article.body` CLOB |
| Phase 1b 본문 추출 상태·재시도 | `article.body_extraction_status/body_extraction_attempts/body_extraction_error` |
| Zero-Trust 피드·본문 호스트 정책 | `source_domain` (`FEED`/`BODY`/`BOTH`, 소스별 정확 호스트) |
| 6절 Stage 2 필드 표 (노드·유형·성숙도·경로·주체/발생일·인용·중복) | `article_classification.*` + `raw_output` JSON |
| 6절 검증 수준 도출 (Tier·발표 경로·서열) | `source_registry.tier/source_type` + `article_classification.publication_path` → `event.verification_level` (코드 도출) |
| 6절 플루크 필터 / 7절 인간 검수 | `review_queue` |
| 7절 채점 (base·verify_weight·novelty) | `event.impact_score/novelty/state_advanced` (값은 코드 계산) |
| 7절 잠정 90일 소멸 | `event.provisional_expires_on/event_status` |
| 7절 rollback 특례 | `event_type='ROLLBACK'` + history의 레벨 하향 행 |
| 8.1 준비도·매핑·휴면 곡선 | `parameter_set.trl_map/maturity_map/dormancy_*` |
| 8.2 스냅샷·logit·추세·클램프·감쇠 | `pillar_snapshot` + `ops_state.LAST_DISPLAYED_ETA` |
| 9절 버전 스탬프 | `rubric_version` + 각 테이블 FK |
| 9절 서킷 브레이커 | `ops_state.STATE_FROZEN` |
| 9절 골든셋 / 마이크로 예측 | `golden_set_item` / `prediction` |
| 3.1/3.2절 Layer A/B | `k_index` / `hard_metric` / `external_forecast` |
| 전역 제약: 일일 비용 가드 | `llm_usage` + `parameter_set.daily_cost_cap_usd` |

## 구현 노트 (Phase 1a에서 확정)

- **JSON 타입 컬럼(`raw_output`, `trl_map`, `maturity_map`, `expected_output`)은 CLOB로 구현한다** — 테스트 DB(H2 MODE=Oracle) 호환 및 ATP 19c 하위 호환. JSON 파싱은 앱 레벨(Jackson). DDL의 `JSON` 표기는 의미 명세로 읽는다.

## Phase 1b 스키마 보강

- `source_registry.site_domain`은 기존 계약 호환용 대표 도메인으로 유지한다. 실제 네트워크 허용 판단은 소스별 복수 호스트와 용도를 정규화한 `source_domain`을 사용한다.
- `body_extracted`는 전문 저장 여부의 기존 이진 계약으로 유지한다. 대기·정책 생략·재시도 소진을 구분하는 운영 상태는 `body_extraction_status`가 담당한다.
- V3 적용 시 기존 `body_extracted='Y'` 행은 `EXTRACTED`, 나머지는 `SKIPPED`로 결정론적으로 이관한다. 새 기사에 대한 `PENDING` 지정은 수집기 코드가 명시적으로 수행한다.

## G0 승인 필요 결정 사항

1. **event_type enum에 `INSTITUTIONAL_ADVANCE` 추가 (12종)** — 컨셉 6절의 11종에는 필라 6의 *전진* 사건(조약 채택, 시범 계약 등)에 맞는 유형이 없음이 설계 중 발견됨. 승인 시 컨셉 6절에 소급 반영.
2. **노드의 수치 신뢰도 필드 생략** — 컨셉 4절의 "신뢰도"는 verification_level로 충분히 표현된다고 판정(YAGNI). 반대 시 `confidence NUMBER(3,2)` 추가.
3. **마이그레이션 도구: Flyway 채택 권고** — Spring Boot 표준, `db/migration/V1__tracker_core.sql`부터 시작 (WP1.1에서 도입).
