# Tracker Phase 3 (앵커·관측) 실행준비 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 선행 조건: **G2 승인 완료(2026-07-14)** — 35/35 감사·주간 백필·골든셋·관제·
> 정식 검수 UI·로컬 임베딩 병합. 증거: [G2 증거 행렬](../../research/tracker-phase2-g2-evidence.md).
>
> 상태: **Phase 3 및 G3 완료(2026-07-16).** WP3.1 Layer B·궤도 인일,
> WP3.2 K-지수, WP3.3 수송 경제성, WP3.5 수집 채널 확장, WP3.4 4열 대조
> 패널을 구현하고 전체 회귀·실제 API·browser·GitOps 통합 검증을 완료했다.
> LIVE_MODEL과 모든 신규 live polling은 비활성이다.

**목표:** 비-AI 실측 레이어를 붙여 "1측정 + 1앵커 + 1관측" 구조를 완성한다.
게이트 G3 = 4열 대조 패널(본 모델/수송 경제성/군중/기관) 표시 + 첫 분기 정합
리포트 발행.

## WP 개요와 의존 순서

| WP | 핵심 산출물 | 선행 | 외부 egress 신규 |
|---|---|---|---|
| **3.1** Layer B 수집 + 사건 채널 승격 | LL2 API 수집(발사 수·성공률), 월간 수동 ETL($/kg·upmass·체류 인일), 지표→필라 매핑, LL2를 Layer C 사건 소스로 승격 | G2 | **Launch Library 2** |
| **3.3** 수송 경제성 ETA + B쌍 정합 | Wright's law($/kg 공표가 vs 누적 발사량) 외삽, 분기 정합 잡(B쌍만 경보) | 3.1 | 없음(3.1 데이터 사용) |
| **3.2** K-지수 | 연 1회 CSV 인제스트, 게이지 UI(소수점 4자리·Type I 거리) | G2 | 없음(수동 CSV) |
| **3.5** 수집 채널 확장 | 비영어권 피드 3~5개, 필라 6 구조화 원장(UNOOSA 조약 DB·FAA 관보) | G2 | **ISRO·중국 우주(영문)·UNOOSA·GOVINFO** |
| **3.4** 예측 대조 패널 | Metaculus 수집(이동평균 평활) + 기관 목표 연도 테이블, 4열 패널, Layer B 대시보드 | 3.1·3.3 | **Metaculus** |

**권장 실행 순서:** 3.1(기반) → 3.3(3.1 의존) → 3.2·3.5(독립, 병행 가능) →
3.4(3.1·3.3·Metaculus 의존, G3 마무리). 3.1을 먼저 상세화한다.

## 인프라 사전작업 (전역 제약 — 착수 전 확정)

- **CNP egress default-deny:** 신규 도메인마다 `toFQDNs` 화이트리스트 등록 필수.
  - WP3.1: Launch Library 2 (`ll.thespacedevs.com` 계열) — 속도 제한 준수.
  - WP3.4: Metaculus (`metaculus.com` API) — 수개월 이동평균으로 평활.
  - WP3.5: ISRO 보도자료, 중국 우주 프로그램 영문 전문 소스, UNOOSA 조약
    상태 DB, GOVINFO(FAA 발사 면허 관보).
- **시크릿:** LL2 공개 경로에는 현재 키가 필요하지 않지만 Metaculus API는 모든
  요청에 토큰과 데이터 이용 승인이 필요하다. 승인 전에는 수집을 비활성으로
  유지하고 secret 참조도 만들지 않는다. 승인 후에만 토큰을 OCI Vault + ESO로
  주입하며(평문 커밋 금지), 신규 키 경로를 [인프라 사전작업
  문서](../../plans/wp/tracker-infra-prework.md)에 등록한다.
- **리소스 예산(free tier CPU 포화 이력):** 신규 pod/Kubernetes CronJob은
  만들지 않는다. 기존 backend의 조건부 `@Scheduled` + ShedLock을 저빈도로
  시간대 분산한다. LL2는 매월 8일 03:17 UTC, 수송 경제성 정합은 분기 단위로
  기존 수집·스냅샷·소멸 잡과 겹치지 않게 배치한다.
- **GitOps 전용:** 모든 인프라 변경은 `gitops/` 커밋 → Flux reconcile. CNP는
  별도 커밋으로 분리.

## 데이터 모델 (WP0.1 스키마 재사용 확인)

- `prediction`(마이크로 예측용, P4 예정) 테이블을 Metaculus·기관 목표 저장에
  재사용 가능한지 먼저 확인한다. Layer B 지표는 신규 `layer_b_metric`(지표코드·
  값·단위·출처·관측일·필라 매핑) 테이블이 필요할 가능성이 높다.
- LL2 사건 승격은 기존 `event`/`source_registry`/검증 도출 규칙을 그대로
  통과해야 한다(LL2를 Tier 1 AGENCY 또는 적정 Tier로 등록). 새 스키마
  필드는 최소화한다.

## 정직성 표기 (컨셉 v2.10 — WP3.3·3.4 필수)

1. ETA = "현 추세 지속 시나리오·모델 내 80% 구간"(이미 카운트다운에 표기).
2. **$/kg = 공표 가격 기반 추정**임을 데이터·UI에 명시(측정 원가 아님).
3. **측정 지표 vs 구성 지수** 구분 표기(K-지수는 구성 게이지).
4. **수송 경제성 임계값 = 선언된 가정**임을 명시.

## 확정 결정

1. **WP3.5 피드:** ISRO, CNSA 영문, JAXA 영문(ESA는 기존 소스 유지).
2. **WP3.4 질문셋:** 화성 유인 착륙·귀환·정착 Metaculus 질문과 NASA/SpaceX
   기관 목표 연도를 대조한다.
3. **WP3.3 공표가:** 운용 Falcon 9·Falcon Heavy 공개 가격만 사용하고 Starship은
   운용 전 추정에서 제외하며, 항상 `PUBLISHED_PRICE`로 표시한다.
4. **API 키:** LL2는 초기 공개 rate를 사용한다. Metaculus는 인증·데이터 이용
   승인 전에는 수집하지 않으므로 새 secret을 만들지 않는다. 양쪽 승인이 확인된
   뒤에만 토큰을 OCI Vault + ESO로 추가한다.

## WP3.1 실행 결과 (2026-07-15~16)

- WP3.1-A: V11 Layer B 스키마, 3개 시드, 엄격 검증·멱등 로더, 공개 API와
  UI 정직성 라벨 완료.
- WP3.1-B: LL2 2.3 목록 페이지네이션(최대 10페이지), same-host HTTPS 검증,
  완료된 성공/실패/부분실패만 집계, 직전 완료 연도 멱등 upsert 완료.
- 배포 경계: `ll.thespacedevs.com:443` exact CNP, 월간 in-process 잡,
  `TRACKER_LL2_ENABLED=false`. LIVE_MODEL 및 Layer C 승격은 미활성/보류.
- WP3.1-C(2026-07-16): 전 세계 궤도 인구 전이를 UTC로 적분해 2024년
  4,241.8711 인일/최대 19명, 2025년 3,922.2028 인일/최대 14명을 Pillar 2
  Layer B에 멱등 적재했다. 준궤도 제외와 자동 점수 효과 없음을 UI에 명시했다.
- 통합 검증: backend 600/600, frontend 79/79, 잠금파일 정확 TypeScript·Vite
  build, GitOps verifier, kustomize 렌더, 실제 8080/5176 API·browser,
  375px 문서 가로 overflow 0, console error 0. 상세:
  [궤도 인일 검증 증거](../../research/tracker-wp31-human-presence-validation-evidence.md).

## WP3.3 실행 결과 (2026-07-15)

- V12 immutable corpus, Wright log-log OLS, `$200/kg` 중앙·`$100-$500/kg`
  민감도, 독립 150년 horizon과 잠정/약한 적합도 표기를 구현했다.
- 분기 B↔C 정합은 두 번 연속 같은 극성일 때만 경보하며, 최대 10개 read-only
  감사 표본과 P1 API 구간 1.5배 overlay만 허용한다. node·event·snapshot 원본은
  변경하지 않는다.
- 공개/admin API와 compact UI 카드를 추가했고, GitOps에는 in-process job의
  정확한 schedule·목표값을 넣되 `TRACKER_TRANSPORT_ECONOMICS_ENABLED=false`로 유지했다.
- 최종 검증: backend 481/481, frontend 71/71, production build, GitOps verifier,
  428-line render, 신규 CronJob/secret/egress 0, browser console error 0.
- 최초 로컬 결과: `PROVISIONAL`, 3개 관측, R² 0.5874, 중앙 2030.4,
  민감도 2026.0–2049.4. 첫 완료 분기(`2026-06-30`) 정합 상태는 입력 순서에 따라
  `INSUFFICIENT_DATA`로 정직하게 보존했다. 상세:
  [WP3.3 검증 증거](../../research/tracker-wp33-validation-evidence.md).

## WP3.2 실행 결과 (2026-07-15)

- V13 `k_index`·`k_index_import` 원장, 대체법 계산기, 검수 CSV의 엄격하지만
  행 수 비고정(10–200행) 검증과 같은 버전 해시 잠금을 구현했다.
- 검수 데이터는 OWID World 연례 1차 에너지 1965–2024 관측이며, 최신 2024년
  `176737.1 TWh`를 `20175468036530 W`, `K=0.7305`로 환산한다.
- 공개 API는 `CURRENT/STALE/INSUFFICIENT_DATA`를 구분하고 최대 80개 연례
  시계열만 반환한다. UI는 구성 게이지·연례 관측·자동 효과 없음, Type I까지의
  거리와 필요 에너지 배수를 표시하고 Type I ETA는 만들지 않는다.
- 신규 런타임 egress·secret·pod·CronJob은 없고, 준비도·ETA·스냅샷·정합 경보에
  자동 효과를 주지 않는다. 최종 검증은 backend 510/510, frontend 74/74,
  production build와 브라우저 console error 0이다. 상세:
  [WP3.2 검증 증거](../../research/tracker-wp32-validation-evidence.md).

## WP3.5 구현 결과 (2026-07-16)

- V14 평가 격리, ISRO·CNSA 직접·CNSA hosted-media 세 고정 인덱스, 최대 40개
  metadata-only 후보 수집 경로를 구현했다. 후보는 `evaluation_allowed=N`이라
  LIVE_MODEL과 무관하게 자동 평가·점수·승격되지 않는다.
- UNOOSA·FAA·GovInfo 인간 검수 사실 9건을 원문 없이 구조화 원장에 넣고 읽기
  API를 추가했다. readiness·ETA에는 자동 효과가 없다.
- exact-host egress와 in-process job은 dark launch이며 신규 workload·secret은 없다.
- Maven 전체 600/600, governance 실제 API 9건, GitOps verifier와 kustomize,
  프런트 회귀·build를 통과해 WP3.5를 완료했다. 상세:
  [WP3.5 검증 증거](../../research/tracker-wp35-validation-evidence.md).

## WP3.4 구현 결과와 G3 상태 (2026-07-16)

- V15 검수 참조 10건, 90일 군중예측 평활, 삼중 비활성 Metaculus 수집기,
  세 목표×네 출처 비교 API와 React 패널을 구현했다.
- 백엔드 600/600, 프런트 79/79, 잠금파일 정확 Vite 8.1.4/plugin-react 6.0.3/
  TypeScript 7.0.2 build, GitOps verifier·kustomize를 통과했다. 잘못된 HTTP 200
  비교 응답도 패널 전용 실패로 격리한다.
- 실제 Spring 8080 API와 표준 Vite 5176 브라우저에서 4열 패널과 상태 라벨을
  확인했다. 375px에서 표만 자체 가로 스크롤하고 console error는 0건이었다.
- [2026 Q2 정합 리포트](../../research/tracker-phase3-g3-coherence-report-2026-q2.md)는
  `PARTIAL`로 발행했다. 수송 중앙 2030.4는 `$200/kg` 보조 조건이고, 군중은
  `AUTHORIZATION_REQUIRED`, 기관의 현재 목표는 `UNDATED`, 전체 모델 ETA는
  `INSUFFICIENT_DATA`로 유지한다.
- 보고서 판정 `PARTIAL`은 현재 자료 상태이며 게이트 실패가 아니다. 필요한 통합
  검증을 모두 통과해 WP3.4와 G3를 완료·승인했다. 상세:
  [WP3.4 검증 증거](../../research/tracker-wp34-validation-evidence.md).

## 다음 행동

- 보호 픽스처를 제외한 Phase 3 파일만 커밋·push해 기존 PR 40을 갱신한다.
- LL2→Layer C 승격, official index polling, Metaculus polling, LIVE_MODEL 활성화는
  Phase 3 완료와 분리된 후속 인간 승인 사항으로 유지한다.
