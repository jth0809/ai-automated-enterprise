# WP3.2 K-지수 게이지 검증 증거

검증일: 2026-07-15
상태: 로컬 구현·검증 완료, 런타임 외부 수집과 자동 모델 효과 없음

## 1. 계약과 출처

- 원천: [Our World in Data — Primary energy consumption](https://ourworldindata.org/grapher/primary-energy-cons)
- 원출처 표기: U.S. EIA(2026), Energy Institute Statistical Review of World Energy(2025),
  Our World in Data 가공
- 범위: World, 1965–2024, 단위 TWh, 원천 갱신일 2026-05-05
- 로컬 검수 CSV 최신값: 2024년 `176737.1 TWh`
- 환산: `P(W) = TWh × 10^12 / 8760`, `K = (log10(P) - 6) / 10`
- 회계 기준: `SUBSTITUTION`
- 최신 산출: `20175468036530 W`, `K=0.7305`, 연간 변화 `0.0011`,
  Type I 거리 `0.2695`, 현재 에너지의 약 `495.7배`

현재 리소스는 60개 연례 관측을 담지만 구현은 그 수를 고정하지 않는다.
검증기는 10–200개의 연속·고유 연도, 유한 양수 에너지, 동일 World 범위,
허용 회계 기준, HTTPS 출처와 접근일을 검사한다.

## 2. 구현 커밋

| 커밋 | 내용 |
|---|---|
| `0c161ab` | V13 영속 원장과 K 계산기 |
| `32d2b7d` | 검수 에너지 CSV와 유연한 검증 계약 |
| `f4b3723` | 버전·해시 잠금 멱등 가져오기 |
| `a517356` | 공개 K-지수 API와 신선도 상태 |
| `93aa267` | 연례 K 게이지 UI와 정직성 표기 |

## 3. 자동 검증

### Backend

전체 Maven 회귀 결과:

- Tests run: **510**
- Failures: **0**
- Errors: **0**
- Skipped: **0**
- Total time: **30.671 s**

### Frontend

- Vitest: **15 files, 74/74 tests 통과**
- Production build: **47 modules transformed**
- 산출물: HTML `0.42 kB`, CSS `18.49 kB`, JS `176.60 kB`

## 4. 로컬 런타임/API

`GET /api/tracker/k-index` 확인값:

| 필드 | 값 |
|---|---:|
| status | `CURRENT` |
| latestYear | `2024` |
| annualEnergyTwh | `176737.100` |
| averagePowerWatts | `20175468036530` |
| kIndex | `0.7305` |
| annualDelta | `0.0011` |
| typeOneGap | `0.2695` |
| typeOneEnergyMultiplier | `495.7` |
| accountingBasis | `SUBSTITUTION` |
| series | 60개 반환(상한 80 이하) |

## 5. 브라우저 검증

- Tracker 화면에서 `인류 문명 지수 K = 0.7305` 렌더 확인
- `구성 게이지 · 연례 관측 · 자동 효과 없음` 표기 확인
- `Type I까지 ΔK 0.2695 · 현재 에너지의 약 496배` 표기 확인
- 최근 20개 연례 시계열 sparkline과 출처 링크 확인
- Type I ETA를 표시하지 않음을 확인
- 브라우저 console error: **0**

## 6. 안전 경계

- 런타임 API 호출·외부 egress·신규 secret·CNP·pod·Kubernetes CronJob 없음
- CSV 가져오기는 애플리케이션 내부 멱등 로더이며 운영 외부 수집기가 아님
- K-지수는 구성 게이지로만 사용하고 readiness·ETA·snapshot·coherence·alert를
  자동 변경하지 않음
- stale 데이터도 삭제하지 않고 `STALE`로 공개하며 데이터 부족은
  `INSUFFICIENT_DATA`로 구분
- LIVE_MODEL은 비활성 상태 유지

## 7. 다음 단계

WP3.5에서 ISRO·CNSA 영문 공지의 제한된 HTML 인덱스 수집과 JAXA 기존 RSS
커버리지를 확장하고, UNOOSA·FAA/GOVINFO 구조화 원장을 추가한다. 모든 자동
점수화와 Layer C 승격은 LIVE_MODEL 활성화 전까지 금지한다.
