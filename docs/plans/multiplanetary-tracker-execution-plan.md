# Multiplanetary Civilization Tracker — 시행계획 v1.0

> **For agentic workers:** 이 문서는 프로그램 수준의 페이즈별 마스터 플랜이다. 개별 작업 패키지(WP)에 착수할 때는 superpowers:writing-plans로 해당 WP의 TDD 태스크 수준 상세 플랜을 `docs/plans/wp/` 아래에 별도 작성한 뒤, superpowers:subagent-driven-development 또는 superpowers:executing-plans로 실행한다. WP 단위 체크박스(`- [ ]`)로 진행을 추적한다.

**Goal:** 컨셉 확정본 [multiplanetary-tracker-concept-v2.md](multiplanetary-tracker-concept-v2.md) (v2.11)를 기존 ai-automated-enterprise 모노레포 위에 4개 페이즈로 구현해 정식 런칭한다.

**Architecture:** 기존 `apps/backend/springboot-app`의 `news` 도메인 패턴(RSS 수집기·Anthropic 클라이언트·스케줄러)을 재사용하여 신규 바운디드 컨텍스트 `com.aienterprise.backend.tracker`를 구축한다. 상태 저장은 Oracle ATP, 대시보드는 `apps/frontend/react-app`, 배포는 `gitops/` FluxCD 플로우를 따른다. 파이프라인·수학 엔진·대시보드를 페이즈별로 점진 완성한다.

**Tech Stack (컨셉 12.1 확정 + 레포 기존 스택):**
- Backend: Spring Boot (기존 springboot-app), Java — 수집·파이프라인·채점·상태·수학 엔진 전부 단일 언어로 통일 (1인 유지보수 최적화; 회귀·몬테카를로는 Apache Commons Math)
- DB: Oracle ATP (기존)
- LLM: Anthropic API — 게이트=Haiku 4.5급, 심층 분류=Opus 4.8급(structured outputs + 프롬프트 캐싱), 백필/재평가=Batch API
- Frontend: React 19 + Vite + TS (기존 react-app)
- 인프라: OKE free tier + Istio Ambient + Cilium, FluxCD GitOps, OCI Vault + External Secrets Operator

## Global Constraints (루트 AGENTS.md + 컨셉 전역 규칙 — 전 WP에 적용)

- **GitOps 전용 배포:** 수동 `kubectl apply` 금지. 모든 인프라 변경은 `gitops/` 커밋으로만.
- **Egress default-deny:** 신규 RSS 피드·외부 API(Anthropic, Launch Library 2, Metaculus 등)마다 CiliumNetworkPolicy `toFQDNs` 화이트리스트 등록 필수.
- **시크릿:** 평문 커밋 금지. ANTHROPIC_API_KEY 등은 OCI Vault + ESO로 주입.
- **리소스 제약:** free tier CPU request 포화 이력 있음 — 모든 CronJob/신규 워크로드는 request를 최소로 잡고 실행 시간대를 분산한다.
- **PR 플로우:** main 직접 푸시 불가. 기능 브랜치 → PR → CI 보안 스캔(Semgrep/Trivy/Gitleaks) 통과.
- **컨셉 불변식(v2.9):** LLM은 enum+인용만 출력(숫자 금지), 검증 수준은 코드 도출, 상태 갱신은 멱등, TRL/EGL 8~9 전진은 인간 검수 필수, 모든 평가에 {루브릭, 프롬프트, 모델} 버전 스탬프.
- **일일 LLM 비용 가드:** 일 상한(기본 $20) 초과 시 게이트 이후 단계 일시 중지 + 경보.

---

## 페이즈 개요

| Phase | 이름 | 핵심 산출물 | 게이트 | 개략 규모* |
|-------|------|------------|--------|-----------|
| **0** | 구현 설계·기반 | 데이터 모델, 파이프라인 설계, 루브릭 v1, 인프라 사전 작업 | G0: 설계 승인 | 1~2주 |
| **1a** | **MVP** | 피드 4개·RSS 요약 기반 코어 루프 + 미니 백필 + 대시보드 3요소 ("시계가 실데이터로 움직이는 화면") | G1a: 무인 E2E + 분류 품질 판정 | 1.5~2.5주 |
| **1b** | Layer C 코어 완성 | 본문 추출기, 피드 12개, 플루크 필터, 검수 큐 UI (임베딩 병합은 WP1.5 계약대로 **Phase 2**) | G1: 실뉴스 E2E 데모 | +1.5~2.5주 |
| **2** | 콜드 스타트 + 관제 | 노드 35개·백필·골든셋·서킷 브레이커·검수 큐 | G2: 보정된 현재 상태 | 3~6주 |
| **3** | 앵커·관측 | Layer B·K-지수·수송 경제성 ETA·예측 대조 패널 | G3: 다중 대조 가동 | 2~3주 |
| **4** | 신뢰도 완성 | DAG·몬테카를로·백테스트·마이크로 예측·투명성 페이지 | G4: 방법론 공개 = **정식 런칭** | 3~5주 |

*1인 사이드 프로젝트 기준 집중 작업 주 수. 총 3~5개월. Phase 2의 인간 검수 노동(수 주)은 Phase 1 개발과 부분 병행 가능하다.

---

## Phase 0 — 구현 설계·기반

목표: 코드를 쓰기 전에 스키마·계약·인프라 전제를 고정한다. 여기서의 결정이 이후 전 페이즈의 변경 비용을 결정한다.

- [ ] **WP0.1 데이터 모델 설계** — 산출물: ERD + ATP DDL 초안 (`docs/plans/wp/tracker-data-model.md`)
  - 테이블: `capability_node`(TRL/EGL 상태·휴면·신뢰도), `capability_edge`(AND/OR·δ_e — P4까지 미사용이나 스키마는 지금 확정), `event`, `article`, `event_article`, `node_state_history`, `pillar_snapshot`, `source_registry`(Tier), `rubric_version`, `prediction`(마이크로 예측, P4용), `review_queue`
  - 완료 기준: 컨셉 4·6·7절의 모든 필드가 스키마에 1:1 매핑됨을 추적표로 증명. 멱등 상태 갱신을 보장하는 유니크 제약(사건 자연 키) 포함.
- [ ] **WP0.2 파이프라인 아키텍처 문서** — 산출물: 스테이지 토폴로지, 스케줄 설계(수집 시간당 / 스냅샷 주간 / 소멸·정합 잡), `tracker` 패키지 구조, 기존 `news` 도메인 재사용 지점 명세(FeedFetcher·RssParser·Anthropic 클라이언트 패턴), Stage 2 structured outputs JSON 스키마 v1 (컨셉 6절 필드 표 그대로)
- [ ] **WP0.3 루브릭 v1** — 산출물: 게이트 프롬프트, 심층 분류 시스템 프롬프트(고정 루브릭 + 역사적 앵커 예시 10~15건 초안), 검증 수준 도출 규칙의 코드 명세(서열 포함), 사건 유형 enum 확정
- [ ] **WP0.4 인프라 사전 작업** — 산출물: 피드 10~15개 선정 목록 + 도메인별 CNP `toFQDNs` 목록, OCI Vault 시크릿 경로 설계, CronJob 리소스 예산표(free tier CPU 제약 반영, 실행 시간대 분산표)

**게이트 G0:** 설계 문서 4종 리뷰 승인. 스키마·프롬프트 계약이 이후 페이즈에서 추가 필드 없이 버틸 수 있는지 컨셉 추적표로 확인.

---

## Phase 1 — Layer C 코어 ("동작하는 제품")

목표: 신규 기사 1건이 사람 손 없이 대시보드의 숫자가 되는 E2E 경로를 완성한다. 수학은 의도적으로 단순판(고정 윈도우, 잔차 구간)이며 정교화는 P4로 미룬다.

### Phase 1a — MVP (선행 실행)

**상세 플랜: [docs/plans/wp/phase1a-mvp-plan.md](wp/phase1a-mvp-plan.md).** 아래 WP1.1~1.9의 축소판을 태스크 17개로 재구성한 것으로, 범위 차이는: 피드 4개·RSS 요약 기반(본문 추출기 없음), 자연 키 병합만(임베딩 없음), 플루크 필터 없음(8~9레벨은 간이 admin API로 인간 승인), 검수 정식 UI 없음, **미니 백필 60~80건 포함**(ETA 회귀용 최소 시계열).

**게이트 G1a:** ① 신규 기사 1건이 무인으로 시계·레이더·타임라인 반영, ② 1주 무인 운영 무사고(크래시·비용 초과 없음), ③ 분류 표본 30건 육안 검수로 품질 판정 — 품질 미달 시 다음 행동은 증축이 아니라 루브릭 수정.

**Phase 1b:** 아래 WP1.2(본문 추출기·피드 12개)와 WP1.6(플루크 필터·검수 UI 정식화)의 잔여분. 임베딩 병합은 WP1.5의 구체 계약("임베딩 유사도는 P2에서 추가")이 우선하므로 **Phase 2 범위**다 — 승인된 프로바이더·모델·시크릿·egress·벡터 저장 계약이 아직 없다.

> **2026-07-13 구현 기록:** Phase 1b 파일 작업(설계 `docs/superpowers/specs/2026-07-12-tracker-phase1b-design.md`, 상세 플랜 `docs/plans/wp/phase1b-core-plan.md` 태스크 1~13) 완료 — 피드 12개 정책(V4)·RSS/RDF/Atom·허용 호스트 페처·범용 추출기·추출 수명주기(V3)·CNP 16호스트·플루크 스키마(V5)/계약/잡·증거 검수 API·React 검수 패널. 자동 검증: 백엔드 187 / 프론트 42 테스트 green. **G1a·G1 게이트는 여전히 미충족** — 배포 후 실뉴스 E2E·24시간 수집·스테이징 검수 증적이 필요하며 자동 테스트로 대체하지 않는다. 검증 절차: `docs/runbooks/tracker-phase1b-validation.md`.

- [ ] **WP1.1 도메인 스키마·엔티티·시드** — Modify: `apps/backend/springboot-app` (신규 `tracker` 패키지), ATP 마이그레이션 스크립트. 노드 ~20개 수동 시드(임시 가중치), `trl_map`/`maturity_map`/`base()`/`verify_weight` 조회 테이블 코드화
  - 완료 기준: 시드 로드 후 필라 준비도 쿼리가 수작업 계산과 일치
- [ ] **WP1.2 수집기 (Stage 0)** — 피드 10~15개, 범용 본문 추출(readability 계열 라이브러리 선정 포함 — **사이트별 파서 금지**, 컨셉 13.1), URL/해시 완전 중복 제거, 원문 보존
  - 완료 기준: 24시간 무인 수집에서 유실·중복 0, 피드 1개 죽여도 파이프라인 무영향(갭 내성)
- [ ] **WP1.3 관련성 게이트 (Stage 1)** — Haiku급 이진 판정, 일일 비용 가드 연동
  - 완료 기준: 수동 라벨 표본 50건 대비 재현율 ≥ 0.9 (누락이 오탐보다 치명적)
- [ ] **WP1.4 심층 분류 (Stage 2)** — Opus급 + structured outputs + 프롬프트 캐싱. 증거 인용문의 원문 부분 문자열 대조(불일치 시 기각), 버전 스탬프 기록
  - 완료 기준: 인용 검증 실패·스키마 위반이 0.5% 미만으로 기각 처리됨(크래시 없이)
- [ ] **WP1.5 사건 병합 + 검증 관문** — 자연 키 `(역량, 유형, 주체, 발생일±7일)` 병합(임베딩 유사도는 P2에서 추가), 검증 수준 코드 도출(서열: 주장<동료 심사<공식 확인<독립 검증), 잠정 사건 90일 소멸 잡
  - 완료 기준: 동일 사건 기사 3건 투입 시 사건 1건·상태 갱신 1회(멱등성 테스트), Tier 3 단독은 상태 불변
- [ ] **WP1.6 결정론적 채점·상태 갱신 + 플루크 필터 + 최소 검수 큐** — `impact_score` 잠정 산출→필터 트리거, TRL/EGL 8~9 전진의 인간 검수 필수 규칙, rollback 특례(필라 6), 도달 조건 상태 검사(1절), 검수는 P1에서는 관리 API + 단순 목록 페이지로 충분
  - 완료 기준: 컨셉 10절 엣지 케이스 표의 각 행이 불변식 테스트로 존재하고 통과
- [ ] **WP1.7 준비도·단순 ETA 잡** — 주간 스냅샷, ε-클리핑 + logit + **고정 10년 윈도우** 가중 선형 회귀(수축·W_p·개입 분석은 P4), 클램프 [+2년, +150년], 표시 감쇠 ±90일/일, 잔차 기반 80% 구간
  - 완료 기준: 합성 시계열(알려진 로지스틱 곡선)에서 ETA 역산 오차 < 1년
- [ ] **WP1.8 최소 대시보드** — Modify: `apps/frontend/react-app` — 카운트다운(중앙값+구간, 고정 라벨 **"현 추세 지속 시나리오 기준 · 모델 내 80% 구간"** — 컨셉 v2.10 정직성 표기), 육각 레이더(병목 강조), 사건 타임라인(증거 인용·출처 수·TRL 전이)
- [ ] **WP1.9 배포** — `gitops/` 매니페스트, CNP egress, ESO 시크릿, CronJob 3종(수집·스냅샷·소멸), 리소스 request 최소화
  - 완료 기준: Flux reconcile만으로 전체 기동, 수동 개입 0

**게이트 G1:** 실제 뉴스로 E2E 데모 — 신규 기사가 게이트→분류→사건→검증 관문→상태→ETA→대시보드까지 자동 반영. 멱등성·갭 내성·비용 가드 불변식 테스트 통과. **이 시점부터 시스템은 데이터를 축적하기 시작한다(P2~4 개발과 병행).**

---

## Phase 2 — 콜드 스타트 + 관제

목표: "오늘의 상태"를 역사로 보정하고, 무인 운영을 견디는 관제를 갖춘다. 단일 최대 노동 항목(컨셉 13.3) — 인간 검수 작업은 P1 개발과 병행 착수 가능.

- [ ] **WP2.1 기술 트리 확정** — 노드 ~35개 + 의존성 간선 + 가중치: LLM 배치 초안 → AHP 쌍대 비교 → 인간 검수 확정(12.1 절차). 루브릭 버전으로 관리. **통합 실증 노드에 finish line 등치 정점("26개월 무보급 연속 운영" — 컨셉 v2.11) 포함 필수**
  - 2026-07-13 파일 구현·검증: 사용자가 `nodes-v1.0`의 35개 코드·경계·
    가중치를 승인했다. Flyway V6가 35행, 필라별 통합 노드 1개, 필라별
    가중치 합 1.0, 필라 내부 필수 간선 29개를 적용한다. `r2.0`은 35개
    registry와 통합 추론 금지·26개월 무물질보급 조건을 포함하며 리소스
    SHA-256과 DB seed가 일치한다. 백엔드 회귀 242개가 통과했다.
    향후 AHP 재보정은 `nodes-v1.0`을 묵시적으로 변경하지 않고 새 버전으로
    수행한다. 역사 매핑·운영 데이터 보정 전이므로 WP2.1/G2 체크박스는
    아직 닫지 않는다.
  - **Phase 2 진입 순서(2026-07-14): WP2.1 폐쇄 감사를 먼저 수행한다.**
    `TrackerNodesV1Test`로 정확한 35개 코드·가중치·필라별 합 1.0·통합 노드
    6개·필수 간선 29개·`r2.0` 프롬프트 registry와 LF 정규화 SHA-256을 다시
    검증하고, 결과를 실행 기록에 남긴다. 이 감사에서는 코드, 가중치,
    경계, 간선, 루브릭 또는 도달 조건을 변경하지 않는다.
  - 문서화되지 않은 과거 AHP 행렬을 현재 가중치에서 역산해 만들지 않는다.
    `nodes-v1.0`은 2026-07-13의 명시적 인간 승인값을 동결 기준선으로 삼고,
    실제 AHP 재평가는 전 필라 현재상태 감사 후 새 노드 버전 제안으로만
    수행한다. 점수나 ETA를 원하는 방향으로 움직이기 위한 v1.0 재가중은
    금지한다.
  - 폐쇄 감사 통과 시 WP2.1의 **구조 산출물은 확정**으로 기록한다. 다만
    이 체크박스와 G2의 최종 폐쇄는 아래 WP2.2 전 필라 현재상태 감사까지
    유지한다. 노드 설계와 현재상태 데이터의 책임을 섞지 않는다.
  - 2026-07-14 폐쇄 감사: `TrackerNodesV1Test`의 35개 코드·정확 가중치·
    필라별 합 1.0·통합 노드 6개·필수 간선 29개·`r2.0` registry/해시 계약이
    fresh 실행에서 13/13 통과했다. `nodes-v1.0` 구조를 동결한다. P2~P6
    현재상태 감사와 G2는 계속 열어 둔다.
- [ ] **WP2.2 히스토리컬 백필** — 1957~현재 주요 마일스톤 수백 건, Batch API 초안 + 인간 검수, 초기 TRL/EGL·휴면 감사(아폴로 계열 휴면 처리 포함)
  - 완료 기준: 전 필라의 주간 스냅샷 시계열이 1957~현재 연속 생성, 현재 상태가 감사 결과와 일치
  - **1차 실행 범위 WP2.2-A — P1 현재상태 감사(2026-07-14 승인 방향):**
    실제 도달 조건, `nodes-v1.0`, `r2.0`, 가중치, `Params.defaults()`,
    `Readiness`, `LogitEta`는 변경하지 않는다. 현재 P1의 8개 노드는 모두
    L3 이상 부분점수를 갖고 있으며 준비도는 `0.3814`다. `2175+` 표시는
    정보 없는 노드 때문이 아니라 희소한 계단형 상태와 현행 10년 창
    로그릿 기울기/150년 표시 상한의 결과이므로, 유한 ETA를 이 작업의
    성공 조건으로 삼지 않는다. ETA 수학의 구조 변경은 WP4.2에서 한다.
  - 기존 후보에 다음 P1 청구 4건을 추가한다. 같은 사실이 서로 다른 노드
    경계를 직접 지지하면 후보를 복제하지 않고 여러 청구로 매핑할 수 있다.

    | backfill ID | 기존 후보 | P1 노드 | 사건/레벨 | 경계 판단 |
    |---|---|---|---|---|
    | `BF-P1-027` | `HC-1981-STS2-ORBITER-REFLIGHT` | `P1-REUSE-LV` | `FLIGHT_TEST` / L5 | 궤도선 재비행의 부분 재사용이며 완전 재사용 L6+가 아님 |
    | `BF-P1-028` | `HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS` | `P1-ORBIT-REFUEL` | `FLIGHT_TEST` / L7 | 궤도 유체 이송 실증이나 반복 운용 서비스는 아님 |
    | `BF-P1-029` | `HC-2012-DRAGON-ISS-CARGO-BERTHING` | `P1-ORBIT-LOGISTICS` | `FLIGHT_TEST` / L7 | 실제 화물 인계 실증이나 당시 임무는 운영 서비스 전 단계 |
    | `BF-P1-030` | `HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY` | `P1-ORBIT-LOGISTICS` | `OPERATIONAL_DEPLOYMENT` / L8 | 운영 보급 임무의 화물 전달·인계 |

  - 다음 공식 1차 출처 후보 2건과 P1 청구 2건을 추가한다.

    | backfill ID | 새 후보 | 공식 근거 | P1 노드 | 사건/레벨 |
    |---|---|---|---|---|
    | `BF-P1-031` | `HC-2008-JULES-VERNE-ISS-REFUEL` | [ESA Jules Verne ISS refuelling](https://www.esa.int/Science_Exploration/Human_and_Robotic_Exploration/ATV/Premiere_for_Europe_Jules_Verne_refuels_the_ISS), 2008-06-17 | `P1-ORBIT-REFUEL` | `OPERATIONAL_DEPLOYMENT` / L8 |
    | `BF-P1-032` | `HC-1972-APOLLO17-FINAL-LUNAR-MISSION` | [NASA Apollo 17](https://www.nasa.gov/mission/apollo-17/), 1972-12-19 | `P1-SURFACE-ASCENT` | `PROGRAM_CANCELLATION` / null |

    현재 전이 어휘에는 중립적인 `PROGRAM_END`가 없으므로 Apollo 17은
    기존 `PROGRAM_CANCELLATION`을 프로그램 종료 전이로 사용한다. 사건 제목과
    사실 요약은 "최종 달 착륙 임무의 완료"로 기록하며 Apollo가 취소되었다고
    표시하지 않는다. 이 전이는 L8을 지우지 않고 `program_end_date`만 설정해
    15년 뒤 휴면을 시작한다.
  - 후보 수 `210 → 212`는 35개 노드에 6건씩 할당한 결과가 아니다. 후보
    코퍼스는 노드 중립 사실 목록이고, 한 후보는 여러 노드를 지지할 수 있으며
    근거가 부족한 후보는 매핑되지 않을 수 있다. 이번 목표는 후보 212건
    전부 `READY_FOR_MAPPING`, 거절 0건, 청구 `140 → 146`, 고유 매핑 후보
    `136 → 140`, P1 청구 `26 → 32`다. 노드별 할당량은 두지 않는다.
  - 예상 현재상태는 `REUSE L5`, `REFUEL L8`, `DEEP-PROP L5 DORMANT`,
    `EDL L5`, `SURFACE-ASCENT L8 DORMANT`, `CREW-SAFE L8`,
    `ORBIT-LOGISTICS L8`, `TRANSPORT-INTEGRATION L3`이다. Apollo 종료일은
    `1972-12-19`, 휴면 시작일은 `1987-12-19`이며, `params-v1`의 현재
    휴면 계수 0.40을 적용한 P1 준비도 회귀값은 정확히 `0.4624`다. 이는
    사실·루브릭 결정을 고정한 뒤 계산되는 회귀 기대값이지 최적화 목표가 아니다.
    Apollo 휴면이 점수를 낮추더라도 유지하는 것이 굿하트 방지 조건이다.
  - ESA는 V2 `source_registry`와 기존 CNP에 이미 등록된 Tier 1 기관 피드다.
    검증기가 ESA 근거를 도출할 수 있도록 `historical-source-catalog-v1.json`에
    동일 메타데이터를 추가하되, Flyway와 egress 정책은 변경하지 않는다.
    백필 import는 계속 로컬 classpath만 읽고 시작 시 네트워크를 호출하지 않는다.
  - 저작권·저장 원칙은 그대로 유지한다. 새 후보에는 HTTPS URL, locator,
    접근일, 일시적으로 계산한 SHA-256, 직접 작성한 500자 이하 사실 요약만
    저장한다. 원문, 인용문, HTML, PDF, 이미지, 첨부, WARC 또는 응답 본문은
    저장하지 않는다. 증분은 후보 2행·청구 6건·historical evidence 6행뿐이며
    바이너리/본문 저장 열을 추가하지 않는다.
  - **WP2.2-A 검증 기준:** 구조 검증 212/212/0, 매핑 146건과 고유 후보
    140건, 모든 출처·사실 식별자·검증 수준·이중 승인 일치, P1 레벨/상태/
    날짜/준비도 `0.4624`, 금지 필드 0건, fresh H2 import와 2차 import no-op,
    집중 테스트와 전체 백엔드 회귀 통과다. `2175+` 또는 null ETA는 허용하며
    유한 연도 출력을 요구하지 않는다.
  - 2026-07-14 WP2.2-A 데이터·집중 검증: 기대값을 먼저 바꾼 RED에서 기존
    210/140/136, ESA 누락, P1 refuel L5가 원인인 5개 실패를 확인했다. 최소
    데이터 변경 뒤 구조·코퍼스·출처·재생 집중 테스트 45/45와 독립 코퍼스
    테스트 42/42가 통과했다. 생산 값은 후보 212/READY 212/REJECTED 0,
    청구 146, 고유 후보 140, P1 청구 32, 준비도 `0.4624`다. 코드·데이터·
    테스트는 `8d3c91b`에 기록했다. 전체 백엔드 회귀 280/280, GitOps egress
    정책, 금지 필드/NUL 검사와 `git diff --check`도 통과했다.
  - `backfill-v1`은 아직 G2를 통과하지 않은 미출시 코퍼스이므로 이번 감사를
    같은 리소스와 dataset version에 반영한다. 이전 해시를 import한 개발 DB는
    hash mismatch로 안전하게 실패해야 하며 우회하지 않는다. 로컬/비운영
    환경을 깨끗이 재시드한 뒤 검증하고, 운영 DB를 수동 수정하지 않는다.
    G2 승인 뒤에는 v1을 불변으로 동결하고 후속 데이터는 새 버전으로 낸다.
  - WP2.2-A는 P1 1차분이다. 완료 후에도 P2~P6 현재상태·휴면 감사와
    1957~현재 전 필라 주간 스냅샷 완료 전에는 WP2.2/G2를 닫지 않는다.
- [ ] **WP2.3 골든셋 + 회귀 하네스** — 정답 합의 기사 ~50건 라벨링, 주 1회 자동 재평가 + 일치율 리포트
- [ ] **WP2.4 관제 + 서킷 브레이커** — 피드 데드맨 스위치(중위 간격 2배 침묵 경보), 게이트 통과율·사건 수·점수 분포 관리도, **골든셋 임계 미달/관리도 위반 시 상태 갱신 자동 동결→인간 해제**(9절 v2.9)
  - 완료 기준: 모의 드리프트(프롬프트 고의 변조)로 서킷 브레이커 발동·해제 훈련 통과
- [ ] **WP2.5 검수 큐 정식 UI + 운영 런북** — 승인/반려/사유, 잠정 사건 조회. 런북: 주간 1~2h·월간 1h·분기 2~4h 루틴 절차화(13.2)

**게이트 G2:** 대시보드 ETA가 백필 기반 시계열로 산출. 골든셋 일치율 기준선 확립. 서킷 브레이커 훈련 통과. 임시 시드(WP1.1) 완전 대체.

### Phase 2 WP2.1/WP2.2-A 상세 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans`로
> 아래 작업을 순서대로 실행한다. 사용자가 서브에이전트 사용을 금지했으므로
> `subagent-driven-development`는 사용하지 않는다. 각 단계는 체크박스로
> 진행상황을 기록한다.

**Goal:** `nodes-v1.0` 구조를 폐쇄 감사하고, P1의 누락된 역사 근거와 Apollo
휴면을 참조형 백필에 반영해 현재상태를 재현한다.

**Architecture:** 기존 35개 노드·`r2.0`·준비도/ETA 수학은 동결한다. 두 개의
공식 후보와 여섯 개의 P1 청구만 기존 `backfill-v1`에 추가하고, 생산
validator와 fresh-H2 재생 테스트가 개수·상태·휴면·준비도를 함께 잠근다.
데이터 검증과 실행 기록은 기존 문서·런북에 동기화한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, JdbcClient, Flyway V1~V8,
H2 Oracle mode, JSON/JSONL, PowerShell 5.1+, Git.

#### 전역 제약

- 작업 위치는 `.claude/worktrees/tracker-mvp`, 브랜치는
  `feat/tracker-mvp`이다.
- `.claude/`, `application-demo.yml`, `application-refbackfill.yml`,
  `backfill-demo.json`은 사용자의 추적 제외 파일이므로 스테이징하지 않는다.
- `nodes-v1.0` 35개 코드·가중치·29개 간선, `r2.0`, 실제 도달 조건,
  `Params.defaults()`, `Readiness`, `LogitEta`를 변경하지 않는다.
- 최종 생산 데이터는 후보 212건, READY 212건, REJECTED 0건, 청구 146건,
  고유 사용 후보 140건, P1 청구 32건이다.
- 새 근거는 URL·locator·접근일·SHA-256·직접 작성한 사실 요약만 저장한다.
  원문, 인용문, HTML, PDF, 이미지, 첨부, WARC와 응답 본문은 저장하지 않는다.
- ESA는 기존 `source_registry`와 CNP를 재사용한다. Flyway와 네트워크 정책을
  변경하지 않으며 CI와 애플리케이션 import는 외부 사이트를 호출하지 않는다.
- 모든 Java 변경은 테스트를 먼저 실패시키고 최소 변경으로 통과시킨다.
- Maven은 캐시된 실행 파일을 `$Maven`에 해석한 뒤
  `-o '-Dmaven.repo.local=C:\Users\jang\.m2\repository'`로 실행한다.
- 작업별로 독립 검증 후 한 커밋을 만든다.

#### 파일 구조

- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`: WP2.1 감사와
  WP2.2-A 실행 결과를 단일 마스터 계획에 기록한다.
- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java`:
  생산 후보 정확 개수를 212로 고정한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`:
  기존 ESA 레지스트리 메타데이터를 검증 카탈로그에 노출한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`:
  Jules Verne와 Apollo 17 참조형 후보 두 행을 추가한다.
- Modify `apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json`:
  `BF-P1-027`~`BF-P1-032` 여섯 청구를 추가한다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java`:
  212/212/0 생산 개수를 잠근다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java`:
  146개 청구와 140개 고유 후보를 잠근다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java`:
  ESA 카탈로그/DB 일치를 검증하되 역사 전용 egress 금지 집합은 유지한다.
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java`:
  생산 P1 레벨·휴면·`0.4624` 준비도·재수입 no-op을 검증한다.
- Modify `docs/research/tracker-backfill-mapping-review.md`: 146개 매핑과 P1
  감사 판정을 기록한다.
- Modify `docs/runbooks/tracker-reference-backfill-validation.md`: 최종 개수,
  파일 해시/크기, import 로그·SQL 기대값을 갱신한다.
- Append `.superpowers/sdd/progress.md`: 로컬 실행 증거를 기록하되 Git에는
  추가하지 않는다.

#### Task 1: WP2.1 구조 폐쇄 감사

**Files:**

- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`
- Append `.superpowers/sdd/progress.md` (Git 제외)
- Test `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerNodesV1Test.java`

**Interfaces:**

- Consumes: Flyway V6 `nodes-v1.0`, `r2.0` classifier resource와 기존
  `TrackerNodesV1Test` 13개 계약.
- Produces: WP2.1 구조 동결 실행 기록. WP2.2의 데이터 변경은 이 구조를
  입력으로만 사용한다.

- [x] **Step 1: 작업트리와 캐시 Maven을 확인한다**

```powershell
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp status --short --branch
$Maven = (Get-ChildItem -LiteralPath "$HOME\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction Stop |
  Sort-Object LastWriteTimeUtc -Descending |
  Select-Object -First 1).FullName
& $Maven -version
```

Expected: 브랜치 `feat/tracker-mvp`, 보호 대상 파일만 untracked, Maven 3.x와
프로젝트 `pom.xml` 기준 Java 21이 출력된다.

- [x] **Step 2: 기존 WP2.1 구조 계약을 fresh 실행한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test'
  if ($LASTEXITCODE -ne 0) { throw "TrackerNodesV1Test failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
```

Expected: `TrackerNodesV1Test` 13개, failures/errors 0. 실패하면 WP2.2로
넘어가지 않고 실제 구조 결함을 진단한다.

- [x] **Step 3: 마스터 계획에 폐쇄 감사 결과를 기록한다**

WP2.1의 2026-07-14 항목 뒤에 다음 문단을 추가한다.

```markdown
  - 2026-07-14 폐쇄 감사: `TrackerNodesV1Test`의 35개 코드·정확 가중치·
    필라별 합 1.0·통합 노드 6개·필수 간선 29개·`r2.0` registry/해시 계약이
    fresh 실행에서 모두 통과했다. `nodes-v1.0` 구조를 동결한다. P2~P6
    현재상태 감사와 G2는 계속 열어 둔다.
```

`.superpowers/sdd/progress.md`에도 동일 사실과 실행 명령을 한 줄로 남긴다.

- [x] **Step 4: 문서 diff를 검증하고 단독 커밋한다**

```powershell
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- docs/plans/multiplanetary-tracker-execution-plan.md
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "docs(tracker): record WP2.1 closure audit"
```

Expected: staged/committed 파일은 마스터 실행계획 하나뿐이다.

#### Task 2: P1 참조형 데이터와 생산 재생 회귀

**Files:**

- Modify `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl`
- Modify `apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java`
- Modify `apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java`

**Interfaces:**

- Consumes: `BackfillDatasetValidator.validate(Resource, Resource)`,
  `BackfillLoader.loadDatasetIfNeeded()`, `TrackerRepository.findAllNodes()`,
  `Readiness.pillarReadiness(List<NodeRow>, Params)`.
- Produces: 212개 후보/146개 청구 생산 리소스와 P1 현재상태
  `5,8,5D,5,8D,8,8,3`, 준비도 `0.4624`.

- [x] **Step 1: 생산 개수·ESA·P1 재생 기대값을 먼저 테스트에 쓴다**

`HistoricalProductionCorpusTest`의 두 기대값을 212로 바꾼다.

```java
assertEquals(212, report.totalCount());
assertEquals(212, report.readyCount());
assertEquals(0, report.rejectedCount());
```

`BackfillDatasetValidatorTest`의 생산 기대값을 다음처럼 바꾼다.

```java
assertEquals(146, result.claims().size());
assertEquals(140, result.candidates().size());
assertHasError(result, "production corpus must contain exactly 212 candidates");
assertHasError(result, "production corpus must contain exactly 212 READY candidates");
```

`TrackerHistoricalSourceTest`의 DB 조회 집합과 카탈로그 기대 집합에 ESA를
추가한다. 역사 전용 집합은 변경하지 않는다.

```java
private static final Set<String> HISTORICAL_ONLY =
        Set.of("FAA", "UNOOSA", "GOVINFO", "LSA");
private static final Set<String> CATALOG_CODES =
        Set.of("NASA", "ESA", "FAA", "UNOOSA", "GOVINFO", "LSA");

// sourceRegistryMatchesApprovedHistoricalCatalog() query
WHERE code IN ('NASA','ESA','FAA','UNOOSA','GOVINFO','LSA')

// readCatalog()
assertEquals(CATALOG_CODES, result.keySet());
```

`BackfillLoaderTest`에 다음 imports와 생산 재생 테스트/helper를 추가한다.

```java
import com.aienterprise.backend.tracker.math.Params;
import com.aienterprise.backend.tracker.math.Readiness;

@Test
void productionP1AuditReplaysApprovedLevelsDormancyAndReadiness() {
    BackfillLoader production = loader(
            new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
            new ClassPathResource("tracker/backfill-v1.json"),
            "backfill-v1");

    production.loadDatasetIfNeeded();

    assertNodeState("P1-REUSE-LV", 5, "ACTIVE");
    assertNodeState("P1-ORBIT-REFUEL", 8, "ACTIVE");
    assertNodeState("P1-DEEP-PROP", 5, "DORMANT");
    assertNodeState("P1-EDL-HEAVY", 5, "ACTIVE");
    assertNodeState("P1-SURFACE-ASCENT", 8, "DORMANT");
    assertNodeState("P1-CREW-SAFE", 8, "ACTIVE");
    assertNodeState("P1-ORBIT-LOGISTICS", 8, "ACTIVE");
    assertNodeState("P1-TRANSPORT-INTEGRATION", 3, "ACTIVE");

    var surfaceAscent = repository.findNodeByCode("P1-SURFACE-ASCENT");
    assertEquals(LocalDate.of(1972, 12, 19), surfaceAscent.programEndDate());
    assertEquals(LocalDate.of(1987, 12, 19), surfaceAscent.dormantSince());

    var p1Nodes = repository.findAllNodes().stream()
            .filter(node -> node.pillar() == 1)
            .toList();
    assertEquals(0.4624,
            Readiness.pillarReadiness(p1Nodes, Params.defaults()), 1e-9);
    assertEquals(146, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
            .query(Integer.class).single());
    assertEquals(146, jdbc.sql("""
            SELECT record_count FROM backfill_import
             WHERE dataset_version = 'backfill-v1'
            """).query(Integer.class).single());

    production.loadDatasetIfNeeded();

    assertEquals(146, jdbc.sql("SELECT COUNT(*) FROM historical_evidence")
            .query(Integer.class).single());
    assertEquals(1, jdbc.sql("""
            SELECT COUNT(*) FROM backfill_import
             WHERE dataset_version = 'backfill-v1'
            """).query(Integer.class).single());
}

private void assertNodeState(String code, int level, String status) {
    var node = repository.findNodeByCode(code);
    assertEquals(level, node.currentLevel(), code);
    assertEquals(status, node.nodeStatus(), code);
}
```

- [x] **Step 2: 새 테스트가 기존 데이터에서 실패하는지 확인한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=HistoricalProductionCorpusTest,BackfillDatasetValidatorTest,TrackerHistoricalSourceTest,BackfillLoaderTest'
  if ($LASTEXITCODE -eq 0) { throw 'Expected the pre-data audit tests to fail' }
}
finally {
  Pop-Location
}
```

Expected failures: 실제 210/140/136과 기대 212/146/140 불일치, ESA 카탈로그
누락, P1 refuel/logistics L5 및 surface-ascent ACTIVE와 새 기대 상태 불일치.

- [x] **Step 3: 공식 페이지 지문이 검토 시점 값과 일치하는지 확인한다**

```powershell
& scripts/backfill/Get-SourceFingerprint.ps1 -Uri 'https://www.esa.int/Science_Exploration/Human_and_Robotic_Exploration/ATV/Premiere_for_Europe_Jules_Verne_refuels_the_ISS'
& scripts/backfill/Get-SourceFingerprint.ps1 -Uri 'https://www.nasa.gov/mission/apollo-17/'
```

2026-07-14 검토값:

```text
ESA  f520cc250af221e2906e4d09ef40eb3e9283c9353af2871c3192425d3a6b0b31  20,989 bytes
NASA 16252989b63672c56a0ba6dc1c5bfa7465bc99e5444071514cb1215893243065 327,213 bytes
```

도구 출력에는 원문이 없어야 한다. 페이지가 갱신돼 지문만 달라졌다면 실제
페이지에서 같은 사실을 재확인하고 새 지문만 사용한다.

- [x] **Step 4: 생산 후보 정확 개수를 212로 올린다**

`BackfillDatasetValidator`에 상수를 추가하고 두 검사를 교체한다.

```java
private static final int PRODUCTION_CANDIDATE_COUNT = 212;

if (corpusReport.totalCount() != PRODUCTION_CANDIDATE_COUNT) {
    errors.add("candidates: production corpus must contain exactly "
            + PRODUCTION_CANDIDATE_COUNT + " candidates");
}
if (corpusReport.readyCount() != PRODUCTION_CANDIDATE_COUNT) {
    errors.add("candidates: production corpus must contain exactly "
            + PRODUCTION_CANDIDATE_COUNT + " READY candidates");
}
```

- [x] **Step 5: ESA 메타데이터와 후보 두 행을 추가한다**

`historical-source-catalog-v1.json`에서 NASA 다음에 다음 객체를 추가한다.

```json
{
  "sourceCode": "ESA",
  "name": "European Space Agency",
  "domain": "esa.int",
  "sourceType": "AGENCY",
  "tier": 1,
  "feedActive": true
}
```

`historical-candidates-v1.jsonl` 끝에 다음 두 JSONL 행을 추가한다.

```json
{"candidateId":"HC-2008-JULES-VERNE-ISS-REFUEL","eventTitle":"Jules Verne transferred mission propellant to the ISS","candidateTopics":["orbital refueling","automated propellant transfer","station logistics"],"actor":"ESA","occurredOn":"2008-06-17","occurredOnPrecision":"DAY","evidence":[{"sourceCode":"ESA","url":"https://www.esa.int/Science_Exploration/Human_and_Robotic_Exploration/ATV/Premiere_for_Europe_Jules_Verne_refuels_the_ISS","locator":"Article opening and Automatic sections","accessedOn":"2026-07-13","contentSha256":"f520cc250af221e2906e4d09ef40eb3e9283c9353af2871c3192425d3a6b0b31","publicationPath":"PRIMARY","factSummary":"ESA's Jules Verne ATV automatically transferred 811 kilograms of propellant through its docking interface into the ISS propulsion tanks."}],"discoveryStatus":"READY_FOR_MAPPING","discoveryNote":"Operational in-orbit transfer at useful quantity; it does not establish routine multi-client tanker or depot service."}
{"candidateId":"HC-1972-APOLLO17-FINAL-LUNAR-MISSION","eventTitle":"Apollo 17 completed the final Apollo lunar landing mission","candidateTopics":["lunar surface ascent","Apollo program lifecycle","crewed lunar return"],"actor":"NASA","occurredOn":"1972-12-19","occurredOnPrecision":"DAY","evidence":[{"sourceCode":"NASA","url":"https://www.nasa.gov/mission/apollo-17/","locator":"Mission summary and launch/splashdown facts","accessedOn":"2026-07-13","contentSha256":"16252989b63672c56a0ba6dc1c5bfa7465bc99e5444071514cb1215893243065","publicationPath":"PRIMARY","factSummary":"Apollo 17 was the Apollo program's final lunar landing mission and ended with splashdown on December 19, 1972."}],"discoveryStatus":"READY_FOR_MAPPING","discoveryNote":"Lifecycle evidence closes the Apollo lunar-ascent operating line while preserving its demonstrated level."}
```

- [x] **Step 6: P1 청구 여섯 건을 추가한다**

`backfill-v1.json`에서 `BF-P1-026` 뒤에 다음 객체를 삽입한다. 기존 행의
식별자나 순서는 바꾸지 않는다.

```json
{
  "backfillId": "BF-P1-027",
  "candidateId": "HC-1981-STS2-ORBITER-REFLIGHT",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-REUSE-LV",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 5,
  "actor": "NASA",
  "occurredOn": "1981-11-12",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Columbia completed a second orbital mission",
  "rubricJustification": "Orbiter reflight demonstrates partial-stack reuse in flight and remains capped at L5 because the launch architecture was not fully reusable.",
  "evidenceRefs": ["HC-1981-STS2-ORBITER-REFLIGHT#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Second orbiter flight supports capped partial-reuse credit only."}
},
{
  "backfillId": "BF-P1-028",
  "candidateId": "HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-REFUEL",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 7,
  "actor": "DARPA and industry partners",
  "occurredOn": "2007-01-01",
  "occurredOnPrecision": "YEAR",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Orbital Express transferred propellant and replaceable modules in orbit",
  "rubricJustification": "Autonomous in-orbit fluid transfer exceeded a relevant-environment prototype but did not establish operational mission refueling or recurring service.",
  "evidenceRefs": ["HC-2007-ORBITAL-EXPRESS-SERVICING-TRANSFERS#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Fluid-transfer loop supports L7 without routine-service credit."}
},
{
  "backfillId": "BF-P1-029",
  "candidateId": "HC-2012-DRAGON-ISS-CARGO-BERTHING",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-LOGISTICS",
  "eventType": "FLIGHT_TEST",
  "claimedLevel": 7,
  "actor": "NASA and SpaceX",
  "occurredOn": "2012-05-25",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Dragon completed the first commercial cargo berthing at the ISS",
  "rubricJustification": "The demonstration completed orbital cargo rendezvous, berthing, and station handoff but preceded routine operational service.",
  "evidenceRefs": ["HC-2012-DRAGON-ISS-CARGO-BERTHING#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Cargo handoff supports L7 demonstration credit."}
},
{
  "backfillId": "BF-P1-030",
  "candidateId": "HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-LOGISTICS",
  "eventType": "OPERATIONAL_DEPLOYMENT",
  "claimedLevel": 8,
  "actor": "Orbital Sciences and NASA",
  "occurredOn": "2014-01-12",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "A second commercial provider began operational station cargo delivery",
  "rubricJustification": "An operational resupply mission completed accountable cargo delivery and later waste handoff through an established station interface.",
  "evidenceRefs": ["HC-2014-FIRST-OPERATIONAL-CYGNUS-RESUPPLY#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Operational cargo delivery satisfies the L8 logistics anchor."}
},
{
  "backfillId": "BF-P1-031",
  "candidateId": "HC-2008-JULES-VERNE-ISS-REFUEL",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-ORBIT-REFUEL",
  "eventType": "OPERATIONAL_DEPLOYMENT",
  "claimedLevel": 8,
  "actor": "ESA",
  "occurredOn": "2008-06-17",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Jules Verne transferred mission propellant to the ISS",
  "rubricJustification": "Automatic transfer of 811 kilograms into the ISS propulsion system satisfies operational refueling at useful quantity without proving routine multi-client service.",
  "evidenceRefs": ["HC-2008-JULES-VERNE-ISS-REFUEL#ESA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Useful-quantity operational transfer supports L8, not L9."}
},
{
  "backfillId": "BF-P1-032",
  "candidateId": "HC-1972-APOLLO17-FINAL-LUNAR-MISSION",
  "nodeSetVersion": "nodes-v1.0",
  "rubricVersion": "r2.0",
  "nodeCode": "P1-SURFACE-ASCENT",
  "eventType": "PROGRAM_CANCELLATION",
  "claimedLevel": null,
  "actor": "NASA",
  "occurredOn": "1972-12-19",
  "occurredOnPrecision": "DAY",
  "expectedVerificationLevel": "OFFICIAL",
  "eventTitle": "Apollo 17 completed the final Apollo lunar landing mission",
  "rubricJustification": "The final Apollo lunar landing mission is the supported program-end transition for the demonstrated lunar surface-ascent line; it preserves L8 and starts dormancy.",
  "evidenceRefs": ["HC-1972-APOLLO17-FINAL-LUNAR-MISSION#NASA"],
  "review": {"fact":"APPROVED","rubric":"APPROVED","reviewerNote":"Program completion records lifecycle end without reducing the demonstrated level."}
}
```

- [x] **Step 7: 집중 테스트와 정적 코퍼스 검증을 통과시킨다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test '-Dtest=TrackerNodesV1Test,HistoricalProductionCorpusTest,BackfillDatasetValidatorTest,TrackerHistoricalSourceTest,BackfillLoaderTest'
  if ($LASTEXITCODE -ne 0) { throw "Focused Phase 2 tests failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
& scripts/backfill/Test-HistoricalCorpus.ps1 -MavenPath $Maven
```

Expected: 선택한 모든 테스트 failures/errors 0, 코퍼스 리포트
`total=212 ready=212 rejected=0`.

- [x] **Step 8: 데이터·코드·테스트만 커밋한다**

```powershell
$stageFiles = @(
  'apps/backend/springboot-app/src/main/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidator.java',
  'apps/backend/springboot-app/src/main/resources/tracker/historical-source-catalog-v1.json',
  'apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl',
  'apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/HistoricalProductionCorpusTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/backfill/BackfillDatasetValidatorTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/TrackerHistoricalSourceTest.java',
  'apps/backend/springboot-app/src/test/java/com/aienterprise/backend/tracker/ingest/BackfillLoaderTest.java'
)
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- $stageFiles
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "data(tracker): audit P1 historical state"
```

Expected: 보호 대상 로컬 fixture와 문서는 이 커밋에 포함되지 않는다.

#### Task 3: 감사 문서 동기화와 전체 검증

**Files:**

- Modify `docs/research/tracker-backfill-mapping-review.md`
- Modify `docs/runbooks/tracker-reference-backfill-validation.md`
- Modify `docs/plans/multiplanetary-tracker-execution-plan.md`
- Append `.superpowers/sdd/progress.md` (Git 제외)

**Interfaces:**

- Consumes: Task 2의 검증된 212개 후보, 146개 청구, fresh-H2 P1 상태.
- Produces: 재현 가능한 릴리스 전 검증 절차와 WP2.2-A 실행 기록.

- [x] **Step 1: 최종 개수·분포·파일 크기·SHA-256을 산출한다**

```powershell
$candidatePath = 'apps/backend/springboot-app/src/main/resources/tracker/historical-candidates-v1.jsonl'
$mappingPath = 'apps/backend/springboot-app/src/main/resources/tracker/backfill-v1.json'
$claims = Get-Content -Raw -Encoding UTF8 -LiteralPath $mappingPath | ConvertFrom-Json
$candidates = Get-Content -Encoding UTF8 -LiteralPath $candidatePath
[pscustomobject]@{
  candidates = $candidates.Count
  claims = $claims.Count
  usedCandidates = ($claims.candidateId | Sort-Object -Unique).Count
  advancing = @($claims | Where-Object eventType -NotIn @(
    'SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE')).Count
  nonAdvancing = @($claims | Where-Object eventType -In @(
    'SETBACK','PROGRAM_CANCELLATION','ANNOUNCEMENT_ONLY','RETROSPECTIVE')).Count
  candidateBytes = (Get-Item -LiteralPath $candidatePath).Length
  candidateSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidatePath).Hash.ToLowerInvariant()
  mappingBytes = (Get-Item -LiteralPath $mappingPath).Length
  mappingSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $mappingPath).Hash.ToLowerInvariant()
}
$claims | Group-Object { $_.nodeCode.Substring(0, 2) } |
  Sort-Object Name | Select-Object Name, Count
```

Expected structural values: candidates 212, claims 146, usedCandidates 140,
advancing 114, nonAdvancing 32, pillar counts P1/P2/P3/P4/P5/P6 =
32/21/24/21/23/25.

- [x] **Step 2: 매핑 검토 문서를 현재 데이터로 교체한다**

`tracker-backfill-mapping-review.md`의 머리말·분포·P1 표·검증 체크포인트를
다음 값으로 갱신한다.

```markdown
- 검토일: 2026-07-14
- 입력 코퍼스: 212건
- 생산 매핑: 146개 청구
- 고유 사용 후보: 140건
- 미선정 후보: 72건
- 출처 코드: NASA, ESA, FAA, UNOOSA, GOVINFO, LSA
- 검증 기대값: 146건 모두 OFFICIAL

| 기둥 | 청구 수 |
|---|---:|
| P1 | 32 |
| P2 | 21 |
| P3 | 24 |
| P4 | 21 |
| P5 | 23 |
| P6 | 25 |
| 합계 | 146 |

| P1 노드 | 전체 | 진척 | 현재상태 | 준비도 기여 |
|---|---:|---:|---|---:|
| P1-REUSE-LV | 5 | 4 | L5 ACTIVE | 0.0540 |
| P1-ORBIT-REFUEL | 5 | 3 | L8 ACTIVE | 0.1360 |
| P1-DEEP-PROP | 4 | 1 | L5 DORMANT | 0.0144 |
| P1-EDL-HEAVY | 5 | 4 | L5 ACTIVE | 0.0420 |
| P1-SURFACE-ASCENT | 3 | 2 | L8 DORMANT | 0.0340 |
| P1-CREW-SAFE | 5 | 4 | L8 ACTIVE | 0.1020 |
| P1-ORBIT-LOGISTICS | 4 | 4 | L8 ACTIVE | 0.0680 |
| P1-TRANSPORT-INTEGRATION | 1 | 1 | L3 ACTIVE | 0.0120 |
| 합계 | 32 | 23 |  | 0.4624 |
```

기존 120건·117건·필라별 20건·P1 L0 설명은 삭제한다. P2~P6 현재상태
감사는 후속 WP2.2 작업임을 명시한다.

- [x] **Step 3: 롤아웃 런북을 검증된 데이터로 갱신한다**

`tracker-reference-backfill-validation.md`에서 210/120/117과 기존 파일
해시·크기를 Step 1의 값으로 교체한다. importer 계약은 212/212/0 및
110~150으로, 로그 기대값과 audit `record_count`/승인 참조는 146으로,
고유 후보는 140으로 바꾼다. ESA는 기존 실시간 feed/CNP를 재사용하고
추가 egress가 없다고 기록한다. 전체 저장 추정치는 실제 두 리소스 합계와
146행 기준으로 다시 계산하며 1 MiB 미만 조건을 유지한다.

- [x] **Step 4: 전체 백엔드와 정책 검증을 fresh 실행한다**

```powershell
Push-Location apps/backend/springboot-app
try {
  & $Maven -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
  if ($LASTEXITCODE -ne 0) { throw "Full backend regression failed: $LASTEXITCODE" }
}
finally {
  Pop-Location
}
& 'gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1'
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --check
```

Expected: backend failures/errors 0, egress verifier PASS, diff check exit 0.
프런트 계약 변경이 없으므로 프런트 소스·테스트·빌드는 이 배치에서 건드리지
않는다.

- [x] **Step 5: 실행계획과 진행 원장에 실제 검증 결과를 기록한다**

WP2.2-A 항목 끝에 후보/청구/상태/준비도와 Step 4의 실제 테스트 수를
기록한다. WP2.1/WP2.2/G2 상위 체크박스는 P2~P6 감사·골든셋·관제 완료
전까지 열어 둔다. `.superpowers/sdd/progress.md`에는 동일 실행 증거와
다음 단계 `WP2.2-B P2~P6 현재상태 감사`를 기록한다.

- [x] **Step 6: 문서만 단독 커밋한다**

```powershell
$docFiles = @(
  'docs/research/tracker-backfill-mapping-review.md',
  'docs/runbooks/tracker-reference-backfill-validation.md',
  'docs/plans/multiplanetary-tracker-execution-plan.md'
)
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp add -- $docFiles
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp diff --cached --name-only
git -c safe.directory=C:/Users/jang/ai-automated-enterprise/.claude/worktrees/tracker-mvp commit -m "docs(tracker): record Phase 2 P1 audit"
```

Expected: 세 문서만 커밋되고 `.superpowers/sdd/progress.md`와 로컬 fixture는
스테이징되지 않는다.

---

## Phase 3 — 앵커·관측

목표: 비-AI 실측 레이어를 붙여 "1측정 + 1앵커 + 1관측" 구조를 완성한다.

- [ ] **WP3.1 Layer B 수집 + 사건 채널 승격** — Launch Library 2 API(발사 횟수·성공률, 속도 제한 준수) + 월간 수동 ETL 템플릿($/kg, upmass, 체류 인일 — BryceTech 등 PDF 소스), 지표→필라 매핑 레지스트리. **LL2를 Layer C 사건 소스로도 승격** — 뉴스 커버리지와 무관한 발사·시험 전수 포착 (G0 피드백: 커버리지 다변화)
- [ ] **WP3.2 K-지수** — 연 1회 CSV 인제스트(대체법 기준 명시), 게이지 UI(소수점 4자리·Type I 거리)
- [ ] **WP3.3 수송 경제성 ETA + B쌍 정합 검사** — Wright's law($/kg **공표 가격** vs 누적 발사량) 외삽 — 가격 기반 추정치임과 목표 경제성 임계값이 선언된 가정임을 데이터·UI에 명시(컨셉 v2.10), 분기 정합 잡(B쌍만 경보·구간 확대, A쌍은 연 1회 관측 각주 — v2.8/2.9 규칙)
- [ ] **WP3.4 예측 대조 패널 + 숫자로 보는 우주 시대** — Metaculus 수집(수개월 이동 평균 평활) + 기관 목표 연도 수동 등록 테이블, 4열 대조 패널, Layer B 지표 대시보드
- [ ] **WP3.5 수집 채널 확장 (G0 피드백 신설)** — ① 비영어권 보강: ISRO 보도자료·중국 우주 프로그램 영문 전문 소스 등 피드 3~5개 추가(CNP egress 동반), ② 필라 6 구조화 원장: UNOOSA 조약 상태 DB·관보(FAA 발사 면허) 인제스트 — Tier 1 `source_registry` 항목으로 등록해 기존 검증 도출 규칙 그대로 통과 (상세: [tracker-infra-prework.md](wp/tracker-infra-prework.md) 7절)

**게이트 G3:** 대조 패널 4열(본 모델/수송 경제성/군중/기관) 표시, 첫 분기 정합 리포트 발행.

---

## Phase 4 — 신뢰도 프로그램 완성 → 정식 런칭

목표: 수학을 컨셉 v2.9 완전판으로 올리고, 신뢰도 장치를 공개한다.

- [ ] **WP4.1 의존성 DAG** — `capability_edge` 활성화, 위상 정렬 유효 준비도 `r_eff = min(r, D+δ_e)`, AND/OR 집계
  - 완료 기준: 컨셉 8.1 수치 예시(0.30+0.15=0.45)가 테스트로 재현
- [ ] **WP4.2 수학 정교화** — 사건 수 기반 수축(k=4), 필라별 W_p 규칙(m=6, 4~15년), 개입 분석(수준 이동 더미), 구조 단절(윈도우 리셋), Holt는 UI 모멘텀 전용으로 분리
  - 완료 기준: 계단형 합성 데이터에서 가짜 모멘텀 부재 확인(점프 직후 ETA 요동 < 지정 임계)
- [ ] **WP4.3 몬테카를로** — 회귀 계수·w_c·매핑·보정 파라미터(δ_e, k, 휴면 곡선) 섭동, 중앙값+80% 구간 산출로 잔차 구간 대체
- [ ] **WP4.4 백테스트 하네스 + 파라미터 보정** — 시점 절단 hindcast, **체제 경계 홀드아웃**(~2010 보정 / 2010~ 검증), m·k·δ_e 보정(W_p는 검증만)
- [ ] **WP4.5 마이크로 예측 + Brier + 라이브 캘리브레이션** — **확률 생성 규칙 정의가 첫 태스크**(사건 빈도 기반 위험률(hazard rate) 모델 — 컨셉 v2.10에서 위임된 미명세 갭), 6~24개월 예측 자동 생성·공표, 만기 처리, Brier 트랙 레코드 공개, 확률 재보정 레이어 + 드리프트 진단(구조 파라미터 직접 최적화 금지 — 굿하트 규칙)
- [ ] **WP4.6 방법론 투명성 페이지** — 수식·규칙·레이어 발산 현황·예측 실적·데이터 소스 전체 공개, 도달 판정·이정표 승계 규칙 게시, **정직성 표기 4건 필수 게시**(컨셉 v2.10 — ① ETA=시나리오 투영·모델 내 80%, ② $/kg=공표 가격 기반, ③ 측정 vs 구성 지수 구분, ④ 수송 경제성 임계값=선언된 가정)

**게이트 G4 (= 정식 런칭):** 백테스트 리포트 공개, 첫 마이크로 예측 세트 공표, 투명성 페이지 가동, 운영 런북 기준 2주 무개입 안정 운영.

---

## 컨셉 v2.11 ↔ WP 추적표

| 컨셉 조항 | 구현 WP |
|-----------|---------|
| 1절 도달 판정·종료 규칙 | WP1.6 (상태 검사) + WP4.6 (규칙 게시) |
| 3.1 K-지수·디커플링 조항 | WP3.2 |
| 3.2 Layer B·수송 경제성 | WP3.1, WP3.3 |
| 3.4 앵커 대조(매칭 쌍) | WP3.3 (B쌍), WP3.2 (A쌍 연례 각주) |
| 4절 도메인 모델·휴면 | WP0.1, WP1.1, WP2.2 (휴면 감사) |
| 5절 필라·통합 실증 노드 / 5.1 EGL | WP2.1 (노드 확정), WP1.1 (maturity_map), WP1.6 (rollback 특례) |
| 6절 파이프라인·검증 관문·플루크 필터 | WP1.2~1.6 |
| 7절 채점 규칙·8~9 인간 검수 | WP1.6 |
| 8.1 DAG·유효 준비도 | WP4.1 (스키마는 WP0.1) |
| 8.2 logit·클리핑·수축·W_p·개입·단절 | WP1.7 (단순판) → WP4.2 (완전판) |
| 8.3 ETA 다중 대조 | WP3.3, WP3.4 |
| 8.4 몬테카를로 | WP4.3 |
| 9절 신뢰도 프로그램·서킷 브레이커 | WP2.3, WP2.4, WP4.4~4.6 |
| 10절 엣지 케이스 표 | WP1.5~1.7 불변식 테스트 |
| 11절 UI | WP1.8, WP3.2, WP3.4, WP4.5~4.6 |
| 12.1 확정 사항(티어링·AHP 절차) | WP1.3~1.4 (모델 티어), WP2.1 (AHP) |
| 13.1~13.4 운영·로드맵 | WP1.2 (수집 원칙), WP2.4~2.5 (관제·런북), 본 문서 페이즈 구조 |

## 리스크 레지스터

| 리스크 | 영향 | 대응 |
|--------|------|------|
| free tier CPU request 포화 (기존 이력) | 배포 실패·파드 pending | WP0.4 리소스 예산표, CronJob 시간대 분산, 신규 워크로드 request 최소화 |
| CNP egress 누락으로 피드/API 차단 | 수집 무중단 실패 | WP0.4에서 도메인 목록 선확정, 데드맨 스위치가 조기 감지 |
| LLM 비용 폭주 (피드 오분류·루프) | 예산 초과 | 일일 비용 가드(전역 제약), 게이트 재현율 모니터링 |
| 콜드 스타트 노동 지연 | P2 장기화 | P1과 병행 착수, LLM 배치 초안으로 인간 노동을 검수로 한정 |
| ATP 무료 한도 (스토리지) | 원문 보존 정책 충돌 | 기사 원문 압축 저장 + 한도 모니터링, 초과 시 오브젝트 스토리지 이관 |
| 모델 은퇴 | 강제 마이그레이션 | 버전 스탬프 + 골든셋 재검증 절차(WP2.3)가 흡수 |

## 실행 방식

1. 각 WP 착수 시: `superpowers:writing-plans`로 상세 플랜(`docs/plans/wp/<wp-id>-<name>.md`) 작성 — 정확한 파일 경로·실패하는 테스트·커밋 단위 포함.
2. 실행: subagent-driven(권장) 또는 inline executing-plans.
3. 페이즈 게이트는 인간(사용자) 승인 항목이다 — 게이트 통과 없이 다음 페이즈 착수 금지.
4. 브랜치 전략: WP당 기능 브랜치 → PR → CI 통과 → 머지. GitOps 변경은 별도 커밋으로 분리.
