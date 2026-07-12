# Phase 1a — MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 뉴스 기사가 사람 손 없이 "다행성 도달 카운트다운"의 숫자가 되는 최소 완결 루프를 2주 내에 실배포한다 (피드 4개 → 게이트 → 분류 → 사건 → 상태 → ETA → 대시보드).

**Architecture:** 기존 `apps/backend/springboot-app`에 `com.aienterprise.backend.tracker` 패키지를 추가한다. DB-as-queue(article.pipeline_status 상태 기계) + in-process `@Scheduled` + ShedLock. 순수 로직(검증 도출·채점·수학)은 무의존 정적 함수로 분리해 단위 테스트가 주 방어선이 되게 한다. 설계 기준: [tracker-data-model.md](tracker-data-model.md) · [tracker-pipeline-architecture.md](tracker-pipeline-architecture.md) · [tracker-rubric-v1.md](tracker-rubric-v1.md) · [tracker-infra-prework.md](tracker-infra-prework.md).

**Tech Stack:** Java 21 / Spring Boot 4.1.0 / Maven(mvnw) / **spring-jdbc(JdbcClient) — JPA 도입하지 않음(기존 의존성 결)** / Flyway + flyway-database-oracle / ShedLock(jdbc-template provider) / 테스트: JUnit5 + H2(MODE=Oracle) / 프런트: 기존 react-app(React 19+Vite+TS), 차트 라이브러리 추가 금지(SVG 수제).

## Global Constraints

- LLM 출력에 숫자 점수 필드 금지 — 점수·상태·ETA는 전부 코드 계산 (컨셉 원칙 2)
- 검증 수준은 코드 도출, 상태 확정 전진은 `PEER_REVIEWED` 이상, TRL/EGL 8~9 전진은 인간 승인 필수
- 멱등성: `article.url_hash UNIQUE`, `event.natural_key UNIQUE` — 중복 투입은 no-op
- 모든 분류·사건에 `rubric_version_id` 스탬프
- DDL은 H2(MODE=Oracle) 호환 서브셋: JSON 타입 대신 CLOB (WP0.1의 JSON 컬럼 2개를 CLOB로 변경 — 설계 노트)
- 모든 LLM 호출 전 CostGuard 통과 (일일 캡, 기본 $20)
- GitOps 전용 배포, CNP toFQDNs 화이트리스트, 시크릿은 Vault+ESO, 신규 파드 0개
- 테스트 명령: `./mvnw -f apps/backend/springboot-app/pom.xml test -Dtest=<Class>` (Windows: `mvnw.cmd`)
- 커밋: 태스크당 1커밋, 브랜치 `feat/tracker-mvp`, main 직접 푸시 금지

---

### Task 1: 빌드 기반 — 의존성 + 안전 무동작 기동

**Files:** Modify `apps/backend/springboot-app/pom.xml`
**Interfaces:** 이후 태스크가 쓰는 의존성: `flyway-core`, `flyway-database-oracle`, `shedlock-spring` + `shedlock-provider-jdbc-template`, test scope `h2`.

- [ ] pom.xml에 위 4+1 의존성 추가 (버전은 Boot 4.1.0 BOM 우선, ShedLock은 최신 5.x 명시)
- [ ] 테스트 프로파일 `src/test/resources/application-test.yml`: `spring.datasource.url=jdbc:h2:mem:tracker;MODE=Oracle;DB_CLOSE_DELAY=-1`, `spring.flyway.enabled=true`
- [ ] 기존 전체 테스트 통과 확인: `./mvnw test` → BUILD SUCCESS (회귀 없음)
- [ ] Commit: `build(tracker): add flyway, shedlock, h2 test deps`

### Task 2: V1 마이그레이션 + 시드

**Files:** Create `src/main/resources/db/migration/V1__tracker_core.sql`, `V2__tracker_seed.sql`
**Interfaces:** 테이블·컬럼명은 [tracker-data-model.md](tracker-data-model.md) DDL과 1:1 (JSON→CLOB 치환만). V2 시드: `source_registry` 16행(피드 4 + 레지스트리 전용 12 — 인프라 문서 1절), `capability_node` 20행(루브릭 문서 1절 표 그대로, current_level=0), `parameter_set` 'params-v1'(기본값 전부), `rubric_version` 'r1.0'(모델 ID 2종, 프롬프트 해시는 Task 10~11 완료 후 갱신), `shedlock`.

- [ ] V1/V2 SQL 작성 (H2-Oracle 호환 확인 항목: IDENTITY, VARCHAR2, NUMBER, CLOB, CHECK — 전부 지원)
- [ ] 실패 확인: 마이그레이션 검증 테스트 `TrackerSchemaTest` — `@SpringBootTest(webEnvironment=NONE)` + `@ActiveProfiles("test")`, JdbcClient로 `SELECT COUNT(*) FROM capability_node` = 20, 필라별 `SUM(weight)` = 1.0(±0.001) × 6필라
- [ ] 테스트 통과 → Commit: `feat(tracker): schema V1 + seed V2 (20 nodes, sources, params r1.0)`

### Task 3: 도메인 레코드 + JdbcClient 리포지토리

**Files:** Create `tracker/domain/` — `ArticleRow.java, ClassificationRow.java, EventRow.java, NodeRow.java, SnapshotRow.java, ReviewRow.java` (record) + `TrackerRepository.java` (단일 파사드, JdbcClient 주입)
**Interfaces (이후 태스크가 의존):**
```java
Optional<Long> insertArticleIfNew(String url, String urlHash, long sourceId, String title, Instant publishedAt, String body); // 멱등: 중복 시 empty
List<ArticleRow> findByStatus(String status, int limit);
void updateArticleStatus(long id, String status);
long upsertEventByNaturalKey(String naturalKey, EventRow draft);   // 존재 시 기존 id 반환
void linkClassification(long classificationId, long eventId);
List<SourceEvidence> findClusterEvidence(long eventId);
NodeRow findNodeByCode(String code);
void advanceNode(long nodeId, int newLevel, String verification, long causeEventId, long rubricVersionId); // history 포함, 트랜잭션
```
- [ ] 실패 테스트 `TrackerRepositoryTest`: ① 동일 url_hash 2회 삽입 → 두 번째 empty, ② upsert 동일 natural_key 2회 → 동일 id, ③ advanceNode → node level 갱신 + history 1행
- [ ] 구현 → 통과 → Commit: `feat(tracker): domain records + idempotent repository`

### Task 4: 순수 로직 — VerificationDeriver

**Files:** Create `tracker/event/VerificationDeriver.java`, `SourceEvidence.java`; Test `VerificationDeriverTest`
**Interfaces:** `static VerificationLevel derive(List<SourceEvidence> cluster)` / `record SourceEvidence(long sourceId, int tier, String sourceType, String publicationPath)`

- [ ] 실패 테스트 (루브릭 문서 5절 규칙 그대로):
```java
// AGENCY Tier1 존재 → OFFICIAL
assertEquals(OFFICIAL, derive(List.of(ev(1,1,"AGENCY","PRIMARY"))));
// JOURNAL Tier1 → PEER_REVIEWED (기관 아님)
assertEquals(PEER_REVIEWED, derive(List.of(ev(2,1,"JOURNAL","PRIMARY"))));
// 서로 다른 Tier2 2곳, 재전재 아님 → INDEPENDENT
assertEquals(INDEPENDENT, derive(List.of(ev(3,2,"SPECIALIZED_MEDIA","THIRD_PARTY"), ev(4,2,"SPECIALIZED_MEDIA","THIRD_PARTY"))));
// Tier2 1곳 + 그 재전재 1곳 → CLAIMED (재전재는 1건 계산)
assertEquals(CLAIMED, derive(List.of(ev(3,2,"SPECIALIZED_MEDIA","THIRD_PARTY"), ev(5,3,"GENERAL_MEDIA","WIRE_REPRINT"))));
// PREPRINT 단독 → CLAIMED / CORPORATE 직발표 → CLAIMED
// AGENCY + Tier2 2곳 → INDEPENDENT (서열 최고 적용)
```
- [ ] 구현(서열: CLAIMED<PEER_REVIEWED<OFFICIAL<INDEPENDENT, 최고 등급) → 통과 → Commit: `feat(tracker): verification level derivation (code-only gate)`

### Task 5: 순수 로직 — ImpactScorer + novelty

**Files:** Create `tracker/scoring/ImpactScorer.java`; Test `ImpactScorerTest`
**Interfaces:** `record ScoreResult(double impactScore, int novelty, boolean stateEligible, boolean requiresReview)` / `static ScoreResult score(String eventType, Integer claimedLevel, int currentLevel, boolean dormant, VerificationLevel v)`

- [ ] 실패 테스트 (루브릭 6절 검산 예 포함):
```java
// 전진 6→7, OFFICIAL: base(7)=8 × 0.9 × 1 = 7.2, 검수 불요
assertScore(score("FLIGHT_TEST", 7, 6, false, OFFICIAL), 7.2, 1, true, false);
// 8레벨 도달, OFFICIAL: 9×0.9=8.1 ≥ 8 → requiresReview (구조적 보장 + 8~9 규칙)
assertTrue(score("OPERATIONAL_DEPLOYMENT", 8, 7, false, OFFICIAL).requiresReview());
// 반복 시연(claimed ≤ current) → novelty 0, score 0
assertEquals(0, score("FLIGHT_TEST", 6, 7, false, OFFICIAL).novelty());
// 휴면 재시연 → novelty 1 (복원)
assertEquals(1, score("FLIGHT_TEST", 7, 9, true, OFFICIAL).novelty());
// ANNOUNCEMENT_ONLY / RETROSPECTIVE → stateEligible=false
// CLAIMED 검증 → stateEligible=false (PEER_REVIEWED 미만)
// ROLLBACK + OFFICIAL → stateEligible=true(하향), CLAIMED → false
```
- [ ] 구현 (base 조회 1→1..6→6,7→8,8→9,9→10; verify 0.3/0.7/0.9/1.0; 8~9레벨 전진은 무조건 requiresReview) → 통과 → Commit: `feat(tracker): deterministic impact scorer`

### Task 6: 순수 로직 — 수학 (준비도·logit 회귀·ETA)

**Files:** Create `tracker/math/Readiness.java`, `LogitEta.java`; Tests `ReadinessTest`, `LogitEtaTest`
**Interfaces:**
```java
static double nodeReadiness(int level, boolean dormant, LocalDate dormantSince, Params p); // trl_map × 기간 의존 감쇠
static double pillarReadiness(List<NodeRow> nodes, Params p);                              // Σ w·r
record Trend(double slopePerYear, double intercept, double residualSe) {}
static Trend fitWeightedTrend(double[] years, double[] logits, double windowYears);        // 지수 감쇠 가중 최소제곱
static Double etaYear(double nowYear, double logitNow, Trend t, double logitTarget, Params p); // 클램프, null=상한 초과
static double logitClipped(double r, double eps);
```
- [ ] 실패 테스트: ① trl_map 스팟체크(level 7 → 0.65), ② 휴면 25년 → ×0.70, 45년+ → ×0.40 하한, ③ **합성 로지스틱 검증(G1a 완료 기준):** `R(t)=1/(1+e^{-0.08(t-2035)})`를 1960~2025 연 1회 샘플 → fit → etaYear(target=trl_map(8)=0.85) 역산 오차 `< 1.0년`, ④ 기울기 0 → etaYear null, ⑤ logitClipped(0.0)=logit(0.01) 유한
- [ ] 구현 → 통과 → Commit: `feat(tracker): readiness + logit-linear ETA math`

### Task 7: TrackerConfig + 피드 파싱 + ShedLock

**Files:** Create `tracker/config/TrackerConfig.java`; Modify 없음(뉴스 도메인 불변)
**Interfaces:** `@ConditionalOnProperty("tracker.enabled")`로 전체 빈 게이팅. `NewsConfig.parseFeeds()` 재사용(공개 static). `@EnableSchedulerLock(defaultLockAtMostFor="PT30M")` + JdbcTemplateLockProvider.

- [ ] 테스트: `tracker.enabled=false`(기본)일 때 컨텍스트에 tracker 빈 부재, true면 존재 (`ApplicationContextRunner`)
- [ ] 구현 → 통과 → Commit: `feat(tracker): config gating + shedlock wiring`

### Task 8: IngestJob (Stage 0 — RSS 요약 기반)

**Files:** Create `tracker/ingest/TrackerIngestJob.java`
**Interfaces:** 기존 `FeedFetcher`/`RssParser` 빈 주입. `@Scheduled(cron="0 10 * * * *")` + `@SchedulerLock(name="tracker-ingest")`. RSS item의 title+description을 `body`로 저장(`body_extracted='N'`), `url_hash=SHA-256(url)`, 소스 매핑은 피드 host→source_registry.

- [ ] 실패 테스트: 동일 피드 2회 ingest → article 수 불변(멱등); 피드 1개 fetch 예외 → 나머지 피드 정상 처리(격리)
- [ ] 구현 → 통과 → Commit: `feat(tracker): hourly ingest with idempotent insert`

### Task 9: AnthropicClient + CostGuard

**Files:** Create `tracker/evaluate/AnthropicClient.java`, `CostGuard.java`
**Interfaces:**
```java
// AnthropicSummarizer 패턴 승계 (RestClient, x-api-key, anthropic-version: 2023-06-01)
String complete(String model, String system, String user, int maxTokens);                 // 게이트용
Map<String,Object> completeWithTool(String model, List<Map> systemBlocks /*cache_control 포함*/,
                                    String user, Map toolSchema, String toolName);        // 분류용, tool_choice 강제
boolean CostGuard.allow();      // llm_usage 당일 합산 < daily_cost_cap_usd
void CostGuard.record(String model, int in, int out, int cached);
```
- [ ] 실패 테스트(CostGuard만 — 클라이언트는 통합에서 검증): 사용량 캡 초과 기록 후 `allow()==false`; 자정 경과(다른 usage_date)면 true
- [ ] 구현 (usage→비용 환산 계수는 parameter_set 옆 application.yml `tracker.cost-per-mtok.*`로 외부화 — 단가 변동 대응) → 통과 → Commit: `feat(tracker): anthropic client + daily cost guard`

### Task 10: RelevanceGate (Stage 1)

**Files:** Create `tracker/evaluate/RelevanceGate.java`, `src/main/resources/tracker/prompt-gate.txt` (루브릭 문서 2절 전문)
**Interfaces:** `boolean relevant(ArticleRow a)` — complete(gateModel, prompt, title+excerpt, 8) → "YES" 접두 판정. CostGuard 불허 시 처리 보류(상태 유지).

- [ ] 테스트: 응답 "YES"/"NO"/"yes\n" 파싱; CostGuard false → 기사 상태 INGESTED 유지 (모킹)
- [ ] 구현 → 통과 → Commit: `feat(tracker): relevance gate (haiku)`

### Task 11: DeepClassifier (Stage 2)

**Files:** Create `tracker/evaluate/DeepClassifier.java`, `prompt-classify-system.txt` (루브릭 3절 블록 1~6 전문 — 앵커 12건 포함), `classify-tool-schema.json` (파이프라인 문서 5절 스키마 그대로)
**Interfaces:** `List<ClaimDraft> classify(ArticleRow a, List<String> recentNaturalKeys)` / `record ClaimDraft(String nodeCode, String eventType, Integer claimedLevel, String actor, LocalDate occurredOn, String publicationPath, String evidenceQuote, String duplicateHint)`
검증(코드): ① evidenceQuote가 body의 부분 문자열(공백 정규화 후) 아니면 해당 claim 기각(quote_verified='N' 기록), ② nodeCode 미등록 → 기각, ③ enum·레벨 범위 위반 → 기각. 기각은 크래시가 아니라 행 단위 스킵.

- [ ] 실패 테스트: 모킹 응답으로 ① 정상 claim → ClassificationRow 저장, ② 인용 불일치 claim → quote_verified='N' + event 미생성, ③ 빈 relevant_claims → 기사 CLASSIFIED로 종결
- [ ] 구현 (system 블록에 `cache_control: {"type":"ephemeral"}`) → 통과 → Commit: `feat(tracker): deep classifier with structured outputs + quote verification`

### Task 12: EventMerger + 검증 관문 연결

**Files:** Create `tracker/event/EventMerger.java`
**Interfaces:** `long mergeClaim(ClassificationRow c)` — natural_key = `nodeCode|eventType|normalize(actor)|floor(epochDay(occurredOn)/7)`; upsert 후 클러스터 증거 재수집 → `VerificationDeriver.derive` → event.verification_level 갱신; PROVISIONAL 만료일 = 최초 관측 +90일.

- [ ] 실패 테스트: ① 동일 사건 기사 3건(SpaceNews, NSF, NASA) 순차 병합 → event 1건, 검증 수준 CLAIMED→INDEPENDENT→OFFICIAL 승격 경로, ② 발생일 5일 차이 동일 주체·유형 → 동일 bucket이면 병합, ③ 90일 만료 잡: PROVISIONAL & 기한 경과 → EXPIRED
- [ ] 구현(+ `ExpiryJob` @Scheduled 일 1회) → 통과 → Commit: `feat(tracker): event merge by natural key + verification gate`

### Task 13: StateUpdater + 간이 검수 API

**Files:** Create `tracker/scoring/StateUpdater.java`, `tracker/api/TrackerAdminController.java`
**Interfaces:** StateUpdater — ImpactScorer 결과에 따라: stateEligible & !requiresReview → advanceNode 즉시; requiresReview → `review_queue(reason=HIGH_IMPACT|LEVEL_JUMP)` + 상태 보류; ROLLBACK(OFFICIAL+) → 하향. Admin — `GET /api/tracker/admin/review` 목록, `POST /api/tracker/admin/review/{id}` `{decision, note}` → APPROVE 시 advanceNode 실행. 인증: 헤더 `X-Tracker-Admin-Token` == env `TRACKER_ADMIN_TOKEN` (Vault 키 추가, MVP 간이 — 1b에서 정식화).

- [ ] 실패 테스트: ① 6→7 OFFICIAL 사건 처리 → node=7 + history 1행 + event CONFIRMED, ② 7→8 사건 → node 불변 + review PENDING, ③ APPROVE 호출 → node=8, ④ 토큰 불일치 → 401
- [ ] 구현 → 통과 → Commit: `feat(tracker): state updater + minimal review API`

### Task 14: 미니 백필 (60~80건)

**Files:** Create `src/main/resources/tracker/backfill-v0.json`, `tracker/ingest/BackfillLoader.java`
**Interfaces:** JSON 항목 `{nodeCode, eventType, claimedLevel, actor, occurredOn, verificationLevel, title}` — 검증 수준은 역사적 사실이므로 수기 지정(파생 아님). Loader: 시간순 재생 → event(CONFIRMED)+advanceNode → **연말 단위 pillar_snapshot 생성(1960~현재)**. 부팅 시 1회 실행(빈 테이블일 때만 — 멱등).
**콘텐츠 작업(코드 아님):** 루브릭 앵커 12건을 시작점으로, 노드당 2~5건씩 주요 마일스톤을 LLM 배치 초안 → 인간 검수 반나절로 60~80건 확정. 이 작업은 Task 14 브랜치에서 별도 세션으로 수행하고 검수 완료 후 JSON 커밋.

- [ ] 실패 테스트: 샘플 8건 JSON으로 로더 실행 → 노드 레벨 재생 일치 + 연도별 스냅샷 단조 증가 + 2회 실행 멱등
- [ ] 구현 → 통과 → JSON 본작업(검수 포함) → Commit 2회: `feat(tracker): backfill loader` / `data(tracker): backfill-v0 (74 events, human-reviewed)`

### Task 15: SnapshotJob + 공개 API

**Files:** Create `tracker/math/SnapshotJob.java`, `tracker/api/TrackerController.java`
**Interfaces:** SnapshotJob(@Scheduled 주 1회 + @SchedulerLock): 필라별 준비도 → logit 시계열(백필 연말 + 운영 주간 혼합, 시간값 실수 연도) → fitWeightedTrend(고정 10년 창) → ETA + 잔차 80% 구간 → pillar 0 행(min 준비도, max ETA) → 감쇠: `displayed = prev + clamp(Δ, ±90d×경과일)` (ops_state). 공개 API 3종은 파이프라인 문서 7절 계약 그대로 (`label` 필드 고정 문자열 포함 — v2.10 정직성 표기).

- [ ] 실패 테스트: ① 백필 샘플로 잡 실행 → pillar 0~6 스냅샷 생성, eta_low ≤ eta ≤ eta_high, ② 감쇠: 계산 ETA가 3년 당겨져도 표시 Δ ≤ 90일, ③ `GET /api/tracker/summary` JSON 계약 스냅샷 테스트
- [ ] 구현 → 통과 → Commit: `feat(tracker): weekly snapshot + eta + public api`

### Task 16: 대시보드 3요소

**Files:** Create `apps/frontend/react-app/src/tracker/TrackerPage.tsx`, `Countdown.tsx`, `PillarRadar.tsx`, `EventTimeline.tsx`, `api.ts` (기존 라우팅 방식에 맞춰 등록 — 구현 시 기존 App 구조 확인 후 편입)
**Interfaces:** `api.ts`: `getSummary(): Promise<Summary>` 등 3 fetch — 백엔드 계약과 동일 타입. Radar는 외부 라이브러리 없이 SVG 육각형(꼭짓점 = readiness×반지름, 병목 필라 강조색). Countdown은 연 단위 큰 숫자 + 구간 + **고정 라벨 "현 추세 지속 시나리오 기준 · 모델 내 80% 구간"**.

- [ ] 실패 테스트(vitest): PillarRadar에 readiness 6개 주입 → SVG polygon points 계산 검증; Countdown에 `{eta:2048.3, low:2042, high:2056}` → 라벨·구간 렌더 확인
- [ ] 구현 → `npm test` 통과 → 수동 확인 `npm run dev` → Commit: `feat(tracker-ui): countdown + radar + timeline`

### Task 17: GitOps 배포 + G1a 체크리스트

**Files:** Modify `gitops/apps/backend-springboot/network-policy.yaml`(피드 4 도메인: www.nasa.gov, www.esa.int, spacenews.com, www.nasaspaceflight.com), `externalsecret.yaml`(+TRACKER_FEEDS, +TRACKER_ADMIN_TOKEN), `deployment.yaml`(+TRACKER_ENABLED=false, 모델 ID env)
**절차:** 인프라 문서 8절 순서 — Vault 키 생성(수동) → PR-1(CNP+ESO+env false) → 이미지 릴리스 → 스키마·기동 확인 → PR-2(TRACKER_ENABLED=true).

- [ ] PR-1 머지 + Flux reconcile 확인 (`ESO 동기화 성공, CNP 적용`)
- [ ] 이미지 배포 → 로그에서 Flyway V1/V2 적용 + "tracker disabled" 확인
- [ ] PR-2 머지 → ingest 로그·article 적재 확인
- [ ] **G1a 체크리스트 실행:** ① 신규 기사 1건의 무인 E2E(시계·레이더·타임라인) 캡처, ② 1주 무인 운영(크래시 0, llm_usage 일일 캡 미만), ③ 분류 30건 표본 육안 검수 → 품질 판정 기록
- [ ] Commit/PR: `deploy(tracker): mvp rollout` → **게이트 G1a 사용자 승인 요청**

---

## Self-Review 결과

- **커버리지:** MVP 스코프 표(시행계획 Phase 1a)의 포함 항목 전부에 태스크 존재 — 스키마(2), 피드4·요약수집(8), 게이트(10), 분류+인용대조(11), 병합+검증관문+90일(12), 채점+상태+8~9승인(5,13), 미니백필(14), 스냅샷+ETA+감쇠+API(6,15), 대시보드3요소(16), 배포+비용가드(9,17). 제외 항목(본문 추출기·플루크 필터·임베딩·관제)은 어느 태스크에도 없음 확인.
- **타입 일관성:** ClaimDraft/ScoreResult/Trend/SourceEvidence 시그니처가 태스크 간 동일 명칭으로 참조됨. enum 문자열은 V1 CHECK 제약과 동일 12종/4종.
- **결정 노트:** WP0.1의 JSON 컬럼 2개(raw_output, trl_map 계열)는 H2 호환을 위해 CLOB로 구현 (데이터 모델 문서에 반영 필요 — Task 2에서 주석으로 기록).
