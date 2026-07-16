# WP3.1-B Launch Library 2 통합 Implementation Plan

> 상위: [Phase 3 실행준비](2026-07-14-tracker-phase3-kickoff-plan.md) · [WP3.1-A](2026-07-14-tracker-wp31a-layer-b-measurement.md)
>
> **For agentic workers:** superpowers:executing-plans로 실행. Bash 셸이 깨져 PowerShell(unsandboxed)로 빌드/git.

> **상태: 완료(2026-07-15).** Task 1~4 완료, Task 5는 LIVE_MODEL 이후로
> 명시 보류. 커밋 `0d1dd5d`, `459485e`, `f1256d0`, `6f0b8a8`, `1e41927`.

**Goal:** Launch Library 2(LL2)를 저작권 안전·egress 통제 방식으로 통합해 발사 실측 데이터를 Layer B 케이던스 지표로 반영한다.

**정직한 스코프 분리 (제약 반영):**
- **지금 (네트워크 독립, TDD):** LL2 JSON 파서, 주입형 transport 클라이언트(모킹), LL2 발사 → Layer B 케이던스 지표(연간 발사 수 등, `MEASURED`) 집계·upsert.
- **배포 시점 (gitops):** CNP `toFQDNs`로 `ll.thespacedevs.com:443`만 허용한다.
  기존 tracker 아키텍처의 "신규 pod/CronJob 0개" 원칙에 따라 별도 Kubernetes
  CronJob 대신 backend 내부 월간 `@Scheduled` + ShedLock을 사용한다. egress가
  준비되어도 `TRACKER_LL2_ENABLED=false`이므로 실제 라이브 폴링은 비활성이다.
- **LIVE_MODEL 활성화 후:** LL2 발사를 Layer C 분류 사건으로 승격(Stage 2 LLM 분류 필요). 현재 LIVE_MODEL은 `NOT_ACTIVATED`이므로 이 단계는 보류한다. LL2를 지금은 **Layer B 측정 소스**로만 쓰고, 자동 역량 전진 사건은 만들지 않는다(과도한 추정 방지).

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, Jackson, JdbcClient.

## Global Constraints

- 외부 원문·인용·바이너리 저장 금지. LL2에서 발사 메타(id·이름·일시·제공자·상태)만 파싱해 케이던스 수치로 축약 저장.
- 신규 egress는 CNP `toFQDNs` + 보안 검토 없이 활성화하지 않는다. 애플리케이션/CI 테스트는 외부 사이트를 호출하지 않는다(모킹).
- `Params.defaults()`·`Readiness`·`LogitEta`·35노드·`r2.0` 불변.
- 작업별 독립 검증 후 한 커밋.

## Tasks

- [x] **Task 1:** `LaunchRecord` + 안전 파서. 불량 항목 skip, 원문 미저장 (`0d1dd5d`).
- [x] **Task 2:** 주입형 transport LL2 클라이언트 (`459485e`). 공식 2.3
  `launches` API의 최대 100건 페이지와 `next`를 따르되, exact HTTPS host,
  순환·불완전·페이지 상한 초과 시 전체 실패로 처리한다(`f1256d0`).
- [x] **Task 3:** 완료된 성공·실패·부분 실패만 연간 발사 시도로 집계하고,
  `ANNUAL_LAUNCH_COUNT`·`ANNUAL_LAUNCH_SUCCESS_RATE`를 `MEASURED`로 멱등
  upsert한다. TBD·Go·Cancelled는 제외한다 (`f1256d0`).
- [x] **Task 4:** 직전 완료 UTC 연도를 매월 8일 03:17 UTC에 수집하는 조건부
  in-process 잡(`6f0b8a8`)과 exact-host CNP·기본 비활성 GitOps 게이트
  (`1e41927`). 신규 workload/secret 없음.
- [ ] **Task 5 (LIVE_MODEL 후, 보류):** LL2 → Layer C 분류 사건 승격.

## 검증 증거

- TDD: 페이지 파서 3, 클라이언트 5, 집계 3, 멱등 importer 1, 작업 4개 계약.
- 전체 backend: **403/403**, 실패·오류·skip 0.
- frontend: **67/67**, `tsc && vite build` 성공.
- GitOps: `tracker-egress-policy.ps1` 성공, `kubectl kustomize` 416줄 렌더 성공.
- 런타임: `test,demo,refbackfill` backend 200, Vite 5174 200, Tracker 탭·
  Layer B 3개 지표 렌더, console error 0. 로컬/배포 모두 LL2 live flag는 false.
