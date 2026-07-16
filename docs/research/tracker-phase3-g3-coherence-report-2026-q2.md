# Phase 3 G3 분기 정합 보고서 — 2026 Q2

보고 기간 종료: **2026-06-30**

발행일: **2026-07-16 (KST)**

판정: **PARTIAL / G3 승인(비활성 live 경계 유지)**

## 1. 핵심 판정

네 열을 같은 목표의 동등한 예측으로 합치지 않는다. 현재 값이 있는 수송경제
시나리오는 착륙·정착을 가능하게 할 수 있는 비용 조건일 뿐 사건 연도가 아니며,
군중예측과 기관 목표의 빈 값은 임의의 연도로 채우지 않는다.

| 목표 | 트래커 모델 | 수송경제 | 군중예측 | 기관 목표 |
|---|---|---|---|---|
| 첫 유인 화성 착륙 | `NOT_APPLICABLE` | 2030.4 중앙, 2026.0–2049.4 민감도 — `SUPPORTING` | 질문 메타데이터만 존재, `AWAITING_AUTHORIZATION` | NASA·SpaceX 현재 설명은 `UNDATED`; NASA 과거 2030–2039는 `LEGACY`; SpaceX 2028은 무인 화물 `PRECURSOR` |
| 유인 임무 귀환 | 독립 모델 없음 | 직접 적용 불가 | 검토 질문 미선정 | NASA·SpaceX 요구·설명은 `UNDATED` |
| 자립 가능한 정착 | 전체 readiness 0.1524, 표시 가능한 ETA 없음 — `INSUFFICIENT_DATA` | 2030.4 중앙, 2026.0–2049.4 민감도 — `SUPPORTING` | 화성 인구 100명 질문 메타데이터만 존재, 더 약한 `PROXY`, `AWAITING_AUTHORIZATION` | NASA 인프라 목표·SpaceX 자립 도시 설명은 `UNDATED` |

화면의 `2175+`는 2175년 예측이 아니라 현재 추세에서 표시 가능한 전체 ETA가
없다는 상한 표기다. 이 보고서에서는 이를 숫자 관측값으로 사용하지 않는다.

## 2. 수송경제 B↔C 정합

| 항목 | 결과 |
|---|---:|
| 상태 / 충분성 | `PROVISIONAL` / `PROVISIONAL` |
| 유효 관측 | 3 |
| R² | 0.5873613316 |
| 누적 Falcon 계열 발사 | 427 |
| 중앙 / 빠른 / 느린 cadence | 69.4 / 97.0 / 41.4 launches/year |
| 중앙 목표 | constant 2025 USD `$200/kg` |
| 민감도 | `$100~$500/kg` |
| 중앙 ETA | 2030.4 |
| 민감도 ETA | 2026.0–2049.4 |
| 2026 Q2 정합 상태 | `INSUFFICIENT_DATA` |
| 경보 | `false` |

첫 분기 실행 시 비교할 Layer C snapshot이 아직 없어 `COHERENT`, `WATCH`,
`DIVERGENT` 중 하나로 강제하지 않았다. 두 분기 연속 불일치 규칙도 아직 발동하지
않았다.

## 3. K-지수 관측

- 검수 원천: OWID World 연례 1차 에너지, 1965–2024
- 최신 관측: 2024년 `176737.1 TWh`
- 평균 전력: `20175468036530 W`
- 대체법 K: **0.7305**
- 연간 변화: 0.0011
- Type I 거리: 0.2695, 현 에너지의 약 495.7배
- 용도: 구성 관측 게이지. readiness·ETA·경보에 자동 효과 없음

## 4. 예측·출처 커버리지

- Metaculus: 질문 ID와 정의만 검수했다. API·데이터 이용 승인과 Vault 토큰이
  없으므로 숫자 수집은 `AUTHORIZATION_REQUIRED`이다.
- 기관: NASA·SpaceX 현재 공식 설명에서 검증 가능한 연도를 찾지 못한 항목은
  `UNDATED`로 유지한다. 과거 NASA 2030년대 목표와 SpaceX 2028 화물 선행 목표는
  현재 약속으로 승격하지 않는다.
- WP3.5: ISRO 1개, CNSA 직접 1개, CNSA hosted-media 1개 인덱스 채널을 구현했지만
  모두 dark launch 상태다. 발견 후보는 `evaluation_allowed=N`으로 격리된다.
- 필라 6: UNOOSA·FAA·GovInfo의 검수 사실 9건이 참조 원장에 있으나, 점수나 ETA를
  자동 변경하지 않는다.
- 종합 source coverage: **PARTIAL**. 신규 live polling과 LIVE_MODEL은 비활성이다.

## 5. G3 게이트

첫 분기 보고서와 4열 패널을 발행했고 다음 게이트를 모두 확인했다.

1. 잠금파일 정확 프런트 도구체인(Vite 8.1.4, plugin-react 6.0.3,
   TypeScript 7.0.2, Vitest 4.1.10)에서 **17 files / 79 tests**와 production build 통과
2. V14/V15를 포함한 백엔드 전체 **600 tests**, failures/errors/skips 0,
   실제 Spring 기동과 migration·로더·비교 API 확인
3. 실제 8080 API에 연결한 5176 Tracker 화면에서 세 목표와 네 출처 열,
   빈 값·프록시·선행·과거 목표 라벨 확인
4. 375px에서 페이지 가로 넘침 0, 비교표 자체 스크롤, 브라우저 console error 0
5. GitOps verifier와 `kubectl kustomize` 렌더에서 신규 workload·token·wildcard
   tracker egress 부재 확인

별도 WP3.3 검증 실행에서 얻은 2030.4 수송 시나리오는 이 분기 보고서의 검수된
보조 관측이다. 새 in-memory E2E DB에는 scheduled projection을 일부러 실행하지
않았으므로 실제 패널은 수송 열을 `INSUFFICIENT_DATA`로 표시했다. 두 상태를 섞거나
빈 DB에 2030.4를 강제로 주입하지 않았다.

따라서 보고서의 자료 판정은 `PARTIAL`로 유지하면서, 패널과 보고서의 가동 여부를
판정하는 G3 오프라인 게이트는 승인한다.

LIVE_MODEL과 Metaculus 수집을 켜는 것은 G3 완료 조건이 아니며 별도 인간 승인
사항이다.
