# WP3.3 수송 경제성·B쌍 정합 검증 증거

검증일: 2026-07-15 (KST)
상태: **구현·로컬 검증 완료 / GitOps 기본 비활성 / LIVE_MODEL 미활성**

## 1. 검증 범위와 고정 가정

- 중앙 목표: constant 2025 USD `$200/kg`
- 가정 민감도: 쉬운 목표 `$500/kg`, 어려운 목표 `$100/kg`
- 가격 의미: 공표 서비스 가격 ÷ 동일 구성 최대 LEO 탑재량
- 허용 입력: 운용 Falcon 9/Falcon Heavy 공표가; Starship·사전 운용 추정 제외
- 결과 의미: 선언된 가정하의 P1 보조 시나리오이며 정착 도착연도나 공급자 내부 원가가 아님
- 초기 3–4개 유효 관측은 `PROVISIONAL`; `R² < 0.50`은 유한 ETA를 숨기지 않고
  `WEAK_FIT` 자격 플래그로 표시

입력 출처·locator·접근일·해시는
[WP3.3 출처 증거](tracker-wp33-source-evidence.md)에 기록되어 있다.

## 2. 구현 계보

| 커밋 | 산출물 |
|---|---|
| `0e2ba82` | Flyway V12와 transport persistence |
| `91d570f` | 검수된 immutable 숫자 corpus와 멱등 import |
| `5e34b18` | Wright log-log OLS와 세 cadence 시나리오 |
| `3fbce07` | 조건부 월간 projection job과 Falcon 연간 집계 |
| `20bf444` | 분기 B↔C 정합·결정적 표본·비파괴 ETA overlay |
| `1b152cd` | 공개 projection/coherence 및 관리자 검수 API |
| `4de34bb` | compact 수송 경제성 카드와 pillar bounds 계약 |
| `2499ead` | `tracker.enabled=false` 컨텍스트의 coherence 빈 경계 보강 |

## 3. 자동 검증

### Backend

```powershell
& 'C:\Users\jang\.m2\wrapper\dists\apache-maven-3.9.9\8e74001100ff70d6af083c5511fcc5ec49282d7017cde82c3698eee8fdf86698\bin\mvn.cmd' `
  -o '-Dmaven.repo.local=C:\Users\jang\.m2\repository' test
```

- 최종 결과: **481/481 통과**, failures 0, errors 0, skipped 0
- 최초 전체 실행은 `tracker.enabled=false` 테스트 컨텍스트에서
  `TransportCoherenceService`가 조건부 `TrackerRepository`를 요구하는 결함을 발견했다.
  서비스에 동일한 tracker 활성화 경계를 적용한 뒤 최초 실패군 37/37과 전체 481/481을
  다시 통과했다.

### Frontend

```powershell
npm test -- --run
npm run build
```

- Vitest: **14 files, 71/71 통과**
- Build: `tsc && vite build` 성공, 46 modules transformed
- 산출물: CSS 16.43 kB (gzip 3.86 kB), JS 173.39 kB (gzip 55.00 kB)

### GitOps

```powershell
& gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1
kubectl kustomize gitops/apps/backend-springboot
```

- verifier: `OK: tracker egress policy and safe defaults verified`
- kustomize render: **428 lines**
- 신규 Kubernetes `CronJob`: 0
- transport 전용 secret: 0
- exact `matchName` 고유 호스트: 기존 허용 집합 **20개**, 신규 transport host 0
- 배포 기본값: `TRACKER_ENABLED=false`, `TRACKER_LL2_ENABLED=false`,
  `TRACKER_TRANSPORT_ECONOMICS_ENABLED=false`

## 4. 최초 로컬 projection과 완료 분기 보고서

8080/5174를 이전 로컬 프로세스가 점유해 검증 인스턴스만 8082/5175로 격리했다.
이는 추적되지 않는 로컬 실행 설정이며 repository·GitOps 값은 바꾸지 않았다.

로컬 실행 안전 경계:

- `SPRING_PROFILES_ACTIVE=test,demo,refbackfill`
- `TRACKER_ENABLED=true`, `TRACKER_TRANSPORT_ECONOMICS_ENABLED=true`
- `TRACKER_LL2_ENABLED=false`, `TRACKER_GOLDEN_LIVE_ENABLED=false`
- `TRACKER_FEEDS=''`, `ANTHROPIC_API_KEY=''`
- 외부 API·LLM 호출 없음

`GET /api/tracker/transport-economics` 결과:

| 필드 | 값 |
|---|---:|
| status / sufficiency | `PROVISIONAL` / `PROVISIONAL` |
| 유효 관측 | 3 |
| alpha / beta | 12.3896524517 / -1.0752186646 |
| R² | 0.5873613316 |
| 현재 누적 Falcon 발사 | 427 |
| 중앙 / 빠른 / 느린 cadence | 69.4 / 97.0 / 41.4 launches/year |
| 중앙 필요 누적 발사 | 731.6112 |
| 쉬운 / 어려운 목표 필요 발사 | 312.0174 / 1393.9632 |
| 중앙 ETA | 2030.4 |
| 민감도 ETA | 2026.0–2049.4 |
| 150년 horizon 초과 | 중앙/양끝 모두 `false` |
| qualification flags | 없음 (`R² ≥ 0.50`) |

`GET /api/tracker/coherence/transport`의 첫 완료 분기 보고서:

- report period: `2026-06-30`
- state: `INSUFFICIENT_DATA`
- alert: `false`
- 데이터 로드 직후 첫 분기 실행 시 Layer C snapshot이 아직 없었던 결과를 그대로 보존했다.
  허용 상태를 강제로 `COHERENT`/`WATCH`/`DIVERGENT`로 만들기 위한 데이터 조작은 하지 않았다.

후속 snapshot 생성 결과는 전체 readiness 0.1524, P1 readiness 0.4624였다.
P1 capability ETA는 현재 추세 기준으로 null이며 overlay도 비활성이다. 따라서 2030.4는
별도 수송 경제성 시나리오이지 P1 capability 완료연도나 전체 정착 ETA가 아니다.

## 5. 브라우저 검증

로컬 Tracker 탭에서 다음을 확인했다.

- 기존 countdown, radar, 6개 pillar ETA, historical timeline, Layer B, review/ops 섹션 유지
- 수송 경제성 카드 1개: `잠정 3개 관측`
- `중앙 가정 $200/kg · 민감도 $100–$500/kg (2025 USD)`
- 중앙 `2030`, 민감도 `2026–2049`
- `공표가÷동일 구성 최대 LEO 탑재량 — 실제 원가 아님`
- browser console error: **0**

## 6. 남은 경계

- WP3.3 구현은 완료됐지만 GitOps 활성화는 별도 승인 전까지 하지 않는다.
- LIVE_MODEL과 LL2 live polling은 계속 미활성이다.
- G3는 WP3.2·WP3.5와 WP3.4 4열 대조 패널이 남아 있으므로 아직 완료가 아니다.
