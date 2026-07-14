# Tracker Phase 2 G2 증거 행렬

> 상세 계획: [WP2.5/2.6/G2](../superpowers/plans/2026-07-14-tracker-phase2-wp25-wp26-g2-plan.md)
>
> 작성일: 2026-07-14. 이 문서는 **실제 실행 결과만** 완료로 표기한다.
> 미활성 항목은 `NOT_ACTIVATED`, CI 위임 항목은 `CI`로 남기고 가짜 수치를
> 만들지 않는다.

## 요약 판정

WP2.1~WP2.6의 소프트웨어·데이터 증거는 완결됐다. G2 게이트를 닫기 위해
남은 것은 **① LIVE_MODEL 활성화(현재 미활성), ② PR CI 보안 스캔, ③ 인간(사용자)
G2 승인** 세 가지뿐이다. LIVE_MODEL은 정직하게 미활성으로 표기한다.

## 소프트웨어 증거 (이번 세션 실측)

| 항목 | 상태 | 증거 |
|---|---|---|
| 백엔드 전체 회귀 | ✅ | `mvn test` = **379/379**, failures/errors 0 |
| 프런트 전체 Vitest | ✅ | `vitest run` = **65/65**, 12 파일 |
| 프런트 프로덕션 빌드 | ✅ | `tsc && vite build` 성공 (index 169.71 kB) |
| 화이트스페이스/충돌 마커 | ✅ | `git diff --check` exit 0 |
| 추적 파일 allowlist | ✅ | 보호 미추적 파일(`.claude/`, `application-demo.yml`, `application-refbackfill.yml`, `backfill-demo.json`) 미스테이징 유지 |

## WP2.5 정식 검수 UI + 운영 런북

| 항목 | 상태 | 증거 |
|---|---|---|
| 페이지네이션 검수 API·필터·이력 | ✅ | Task 1·3 (`e486e0f`, `31f216f`), 필터/탭/페이지 Vitest |
| Ops 콘솔·인간 동결 해제 | ✅ | Task 2·4 (`ab554de`, `bd71479`), `OpsPanel` 8 테스트 |
| 운영 런북(주간/월간/분기) | ✅ | [tracker-phase2-operations.md](../runbooks/tracker-phase2-operations.md) (`0f776b2`) |
| 토큰 저장 안전 | ✅ | 토큰은 fetch 헤더·React 메모리에만, storage/URL/DOM 0 |

## WP2.6 로컬 임베딩 병합 — bounded 증거

| 항목 | 상태 | 증거 |
|---|---|---|
| 외부 API/모델/vector DB 미사용 | ✅ | `TextFeatureEmbedding`은 JDK FNV-1a 서명 해싱만 사용, 요청 메모리 계산 |
| 새 egress/평문 secret 0 | ✅ | 병합 경로에 네트워크 호출 없음, 신규 CNP/secret 없음 |
| 임베딩 결정성 | ✅ | `TextFeatureEmbeddingTest` 6/6 (bit-for-bit, 256차원, L2 0/1) |
| 매처 안전 가드 | ✅ | `SemanticCandidateMatcherTest` 12/12 (cosine≥0.82, margin≥0.02, actor conflict/ambiguity→no match, ≤50 cap) |
| exact-first 병합·멱등 | ✅ | `EventMergerTest` 7/7 (exact 우선, cross-key 링크, 모호성→신규, 재실행 불변) |

## 콜드 스타트·데이터·관제 (WP2.2~2.4 완료, 참조)

| 항목 | 상태 | 증거 |
|---|---|---|
| 35/35 현재상태 감사 | ✅ | WP2.2-B/C (`716c2c3`) — [주간 백필 런북](../runbooks/tracker-weekly-backfill-validation.md) |
| 1957~현재 전 필라 주간 cadence | ✅ | 1957-01-07~2026-07-13 = 3,628주 × 6필라 = 21,768행 멱등 투영, 마지막 주 = fresh DB 감사 상태 |
| weekly-history SnapshotJob ETA | ✅ | 주간 이력 기반 ETA 산출(WP1.7 단순판 + WP2.2 주간 투영) |
| 골든셋 count/size/provenance | ✅ | 50 케이스, 34,305 B, 저작권 안전 (`golden-set-v1.json`, WP2.3) |
| OFFLINE 계약 합의 리포트 | ✅ | WP2.3 OFFLINE/LIVE/DRILL 회귀·0.90 기준선 — [WP2.3/2.4 증적](tracker-phase2-wp23-wp24-evidence.md) |
| DRILL 동결/해제 | ✅ | WP2.4 44/50 모의 drift — [서킷 브레이커 런북](../runbooks/tracker-circuit-breaker-drill.md) |
| 임시 시드(WP1.1) 완전 대체 | ✅ | 참조형 백필 코퍼스가 시드를 대체 |

## 참조형 백필 — 저작권/저장 증거 (실측)

| 항목 | 값(측정 2026-07-14) |
|---|---|
| 후보 코퍼스 | **213** (READY 213, REJECTED 0 — `HistoricalProductionCorpusTest`) |
| 생산 청구 | **147** (고유 사용 후보 **141**) |
| 진척/비진척 청구 | 진척 **116** / 비진척 **31** |
| 필라별 청구 (P1~P6) | **32 / 21 / 25 / 21 / 23 / 25** |
| 출처 코드 | NASA, ESA, FAA, UNOOSA, GOVINFO, LSA (6, 전부 Tier 1) |
| 금지 필드 저장 | ✅ 0 — `BackfillDatasetValidator.PROHIBITED_KEYS`가 quote/body/html/pdf/image 등을 **키 단위**로 차단(379 green). 산문 요약 내 단어 등장은 필드 저장이 아님 |
| 저장 크기 | 후보 214,159 B + 매핑 138,038 B ≈ **344 KiB (< 1 MiB)** |

## 명시적 미활성/CI 위임 (정직 표기)

| 항목 | 상태 |
|---|---|
| LIVE_MODEL 활성화 | **NOT_ACTIVATED** — Anthropic API 키를 test/로컬 profile에 넣지 않음. 골든은 OFFLINE/DRILL만. 활성화는 CNP allowlist + 보안 검토 후 별도 결정 |
| 보안 스캔 (Semgrep/Trivy/Gitleaks) | **CI** — PR CI 보안 스캔에서 수행. 로컬 실행/가짜 결과 없음 |
| 새 egress/평문 secret/수동 kubectl | ✅ 0 — 이번 WP2.5/2.6은 신규 워크로드·egress·secret 없음 |

## G2를 닫기 위해 남은 것

1. PR → CI 보안 스캔(Semgrep/Trivy/Gitleaks) 통과.
2. LIVE_MODEL 활성화 여부 결정(활성화 시 CNP allowlist + 보안 검토 동반).
3. 위 확인 후 인간(사용자)의 G2 승인 — 게이트는 사용자 승인 항목이다.
