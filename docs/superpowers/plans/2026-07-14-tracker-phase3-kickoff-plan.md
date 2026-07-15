# Tracker Phase 3 (앵커·관측) 실행준비 계획

> 마스터 실행계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> 선행 조건: **G2 승인 완료(2026-07-14)** — 35/35 감사·주간 백필·골든셋·관제·
> 정식 검수 UI·로컬 임베딩 병합. 증거: [G2 증거 행렬](../../research/tracker-phase2-g2-evidence.md).
>
> 상태: **실행 중(2026-07-15).** WP3.1-A 측정 기반과 WP3.1-B LL2 Layer B
> 경로 완료. 각 후속 WP는 `superpowers:writing-plans`로 TDD 상세 계획을
> 별도 작성한 뒤 `superpowers:executing-plans`로 실행한다.

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
- **시크릿:** LL2·Metaculus는 공개 API지만 상향 rate가 필요하면 키를 OCI
  Vault + ESO로 주입(평문 커밋 금지). 신규 키 경로는 [인프라 사전작업
  문서](../../plans/wp/tracker-infra-prework.md)에 등록.
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
3. **WP3.3 공표가:** Falcon 9·Falcon Heavy·Starship 공개 가격과 BryceTech
   자료를 사용하며 항상 `PUBLISHED_PRICE`로 표시한다.
4. **API 키:** 초기에는 공개 rate로 충분하다고 보고 새 secret을 만들지 않는다.
   상향 rate가 필요해질 때만 OCI Vault + ESO로 추가한다.

## WP3.1 실행 결과 (2026-07-15)

- WP3.1-A: V11 Layer B 스키마, 3개 시드, 엄격 검증·멱등 로더, 공개 API와
  UI 정직성 라벨 완료.
- WP3.1-B: LL2 2.3 목록 페이지네이션(최대 10페이지), same-host HTTPS 검증,
  완료된 성공/실패/부분실패만 집계, 직전 완료 연도 멱등 upsert 완료.
- 배포 경계: `ll.thespacedevs.com:443` exact CNP, 월간 in-process 잡,
  `TRACKER_LL2_ENABLED=false`. LIVE_MODEL 및 Layer C 승격은 미활성/보류.
- 검증: backend 403/403, frontend 67/67, production build, GitOps verifier,
  416-line kustomize render, 로컬 브라우저 Tracker/Layer B 렌더·console error 0.

## 다음 행동

- [WP3.3 수송 경제성 ETA + B쌍 정합 설계](../specs/2026-07-15-tracker-wp33-transport-economics-design.md)를
  사용자 검토한 뒤 TDD 상세 계획을 작성·실행한다. 승인 기준은 중앙
  `$200/kg`, 민감도 `$100-$500/kg`이다.
- WP3.1 잔여 체류 인일 ETL은 WP3.3에 필요한 B쌍을 방해하지 않으므로 별도
  후속으로 유지한다. LL2→Layer C 승격은 LIVE_MODEL 활성화 전에는 실행하지 않는다.
