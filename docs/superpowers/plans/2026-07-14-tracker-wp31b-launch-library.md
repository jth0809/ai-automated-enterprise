# WP3.1-B Launch Library 2 통합 Implementation Plan

> 상위: [Phase 3 실행준비](2026-07-14-tracker-phase3-kickoff-plan.md) · [WP3.1-A](2026-07-14-tracker-wp31a-layer-b-measurement.md)
>
> **For agentic workers:** superpowers:executing-plans로 실행. Bash 셸이 깨져 PowerShell(unsandboxed)로 빌드/git.

**Goal:** Launch Library 2(LL2)를 저작권 안전·egress 통제 방식으로 통합해 발사 실측 데이터를 Layer B 케이던스 지표로 반영한다.

**정직한 스코프 분리 (제약 반영):**
- **지금 (네트워크 독립, TDD):** LL2 JSON 파서, 주입형 transport 클라이언트(모킹), LL2 발사 → Layer B 케이던스 지표(연간 발사 수 등, `MEASURED`) 집계·upsert.
- **배포 시점 (gitops):** CNP `toFQDNs`로 `ll.thespacedevs.com` 허용, 저빈도 폴링 CronJob(free tier request 최소·시간대 분산). 실제 라이브 폴링은 이때 활성화.
- **LIVE_MODEL 활성화 후:** LL2 발사를 Layer C 분류 사건으로 승격(Stage 2 LLM 분류 필요). 현재 LIVE_MODEL은 `NOT_ACTIVATED`이므로 이 단계는 보류한다. LL2를 지금은 **Layer B 측정 소스**로만 쓰고, 자동 역량 전진 사건은 만들지 않는다(과도한 추정 방지).

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, Jackson, JdbcClient.

## Global Constraints

- 외부 원문·인용·바이너리 저장 금지. LL2에서 발사 메타(id·이름·일시·제공자·상태)만 파싱해 케이던스 수치로 축약 저장.
- 신규 egress는 CNP `toFQDNs` + 보안 검토 없이 활성화하지 않는다. 애플리케이션/CI 테스트는 외부 사이트를 호출하지 않는다(모킹).
- `Params.defaults()`·`Readiness`·`LogitEta`·35노드·`r2.0` 불변.
- 작업별 독립 검증 후 한 커밋.

## Tasks

- **Task 1 (지금):** `LaunchRecord` + `LaunchLibraryParser.parse(json)` — LL2 `results[]`에서 id·name·net·provider·status·successful 추출, 불량 항목 skip. 순수 단위 테스트(카세트 fixture).
- **Task 2 (지금):** `LaunchLibraryClient(transport)` — 주입형 `Function<URI,String>` transport로 페이지 조회+파싱. 테스트는 canned JSON 반환 transport 사용(네트워크 없음). 기본 생성자는 HTTP transport(배포 시 CNP 필요).
- **Task 3 (지금):** LL2 발사 → Layer B 케이던스 집계(`ANNUAL_LAUNCH_COUNT` 등 `MEASURED`) upsert + 멱등 통합 테스트.
- **Task 4 (gitops):** `ll.thespacedevs.com` CNP `toFQDNs`, 저빈도 폴링 CronJob 매니페스트. 별도 커밋.
- **Task 5 (LIVE_MODEL 후, 보류):** LL2 → Layer C 분류 사건 승격.
