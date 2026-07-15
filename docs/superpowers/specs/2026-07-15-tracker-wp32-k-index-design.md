# Tracker WP3.2 K-지수 설계

> 마스터 계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> Phase 3 순서: WP3.1 → WP3.3 → **WP3.2** → WP3.5 → WP3.4 → G3

**상태:** 사용자 설계 승인 완료(2026-07-15). 구현 전 확정 사양이다.

## 1. 목표

뉴스·LLM과 독립적인 연례 세계 에너지 관측을 세이건 보간식으로 변환해
Layer A 카르다쇼프 K-지수 게이지로 제공한다. K-지수는 다행성 도착 ETA나
필라 점수가 아니라 문명 규모의 장기 맥락을 보여 주는 구성 게이지다.

## 2. 확정 결정

1. 기존 V1 `k_index` 테이블을 현재값 저장소로 재사용한다.
2. 입력은 저장소에 포함된 검수 CSV이며 런타임 외부 API를 호출하지 않는다.
3. 최초 자료는 Our World in Data가 Energy Institute·U.S. EIA 자료를 가공한
   세계 1차 에너지 소비 시계열을 사용한다.
4. 회계 기준은 `SUBSTITUTION`이며 UI와 API에 반드시 노출한다.
5. K-지수는 P4 readiness, ETA, 스냅샷, 경보를 자동 변경하지 않는다.
6. 정확한 행 수·고정 시작연도·연속 연도는 검증 조건으로 사용하지 않는다.
7. 소수점 네 자리는 제품 표시 정밀도이며 측정 정확도를 의미하지 않는다.

## 3. 데이터 출처와 저작권 경계

- 데이터 페이지: `https://ourworldindata.org/grapher/primary-energy-cons`
- CSV: `https://ourworldindata.org/grapher/primary-energy-cons.csv`
- 최초 검수일: `2026-07-15`
- 최초 채택 범위: `World`, 1965–2024, 60개 연례 관측
- 최신 관측: 2024년 `176737.1 TWh`
- 출처 표기: `U.S. Energy Information Administration (2026); Energy Institute
  Statistical Review of World Energy (2025), processed by Our World in Data`

저장소에는 전 세계 전체 원본이 아니라 `World` 행과 필요한 메타데이터만 담은
작은 파생 CSV를 보존한다. UI와 API에서 출처 URL과 접근일을 노출한다.

## 4. 산식

연간 1차 에너지 `E_TWh`를 평균 전력으로 변환한다.

```text
P_watts = E_TWh × 10^12 / 8760
K = (log10(P_watts) - 6) / 10
type_one_gap = 1 - K
type_one_multiplier = 10^16 / P_watts
```

8760시간은 연간 에너지를 평균 전력으로 바꾸는 명시적 표시 관례다. 계산은
서버에서 수행하며 CSV가 `power_watts`나 `k_value`를 주장하지 않는다.

2024년 기준 기대값은 다음과 같다.

```text
P = 2.017546803652968 × 10^13 W
K = 0.7304823618403994
표시 K = 0.7305
Type I까지 에너지 배수 = 495.6514506575
```

## 5. CSV 계약

파일: `apps/backend/springboot-app/src/main/resources/tracker/k-index-energy-v1.csv`

```csv
year,primary_energy_twh,accounting_basis,source_name,source_url,accessed_on
2024,176737.1,SUBSTITUTION,"U.S. EIA; Energy Institute; Our World in Data",https://ourworldindata.org/grapher/primary-energy-cons,2026-07-15
```

검증 규칙:

- 헤더와 각 필드는 필수다.
- 관측은 10개 이상 200개 이하다. 최초 60개라는 수치는 고정하지 않는다.
- 연도는 고유하고 `1800..현재연도-1` 범위다.
- 연도 간 누락은 허용하며 API가 시계열 그대로 표시한다.
- 에너지는 유한한 양수다.
- 한 데이터셋 안의 회계 기준·출처명·출처 URL·접근일은 동일해야 한다.
- 회계 기준은 `SUBSTITUTION` 또는 `USEFUL`이다.
- 출처 URL은 사용자정보·fragment·비표준 포트 없는 HTTPS URL이다.
- 접근일은 미래일 수 없다.
- 행 순서는 입력과 무관하며 로더가 연도순으로 정렬한다.

오래된 최신 연도는 가져오기 실패 사유가 아니다. 대신 API 상태를 `STALE`로
표시한다. 자료 갱신 지연을 숨기지 않되 과거 연구 데이터의 적재를 막지 않는다.

## 6. 저장과 멱등성

V13은 기존 `k_index`에 다음 provenance 필드를 추가한다.

- `primary_energy_twh`
- `source_url`
- `accessed_on`
- `dataset_version`

`k_index_import`는 `dataset_version`, 원본 SHA-256, 행 수, 출처 URL, 접근일,
가져온 시각을 기록한다.

- 같은 버전·같은 해시: no-op
- 같은 버전·다른 해시: 실패
- 새 버전: 연도 키로 현재값 upsert 후 import 감사행 기록
- 과거 값 수정은 새 데이터셋 버전에서만 허용하며 Git 이력과 import 해시로
  변경 근거를 추적한다.

## 7. 백엔드 경계

구성요소:

- `KIndexCalculator`: TWh→W→K 및 Type I 거리 계산
- `KIndexCsvValidator`: CSV 스키마·범위·메타데이터 일관성 검증
- `KIndexLoader`: 해시 멱등 import
- `KIndexRepository`: 현재 시계열과 최신 import 조회
- `KIndexController`: 공개 read-only API

`GET /api/tracker/k-index` 응답:

```json
{
  "status": "CURRENT",
  "latestYear": 2024,
  "primaryEnergyTwh": 176737.1,
  "powerWatts": 20175468036530,
  "kValue": 0.7305,
  "annualDelta": 0.0000,
  "typeOneGap": 0.2695,
  "typeOneMultiplier": 495.7,
  "accountingBasis": "SUBSTITUTION",
  "sourceName": "U.S. EIA; Energy Institute; Our World in Data",
  "sourceUrl": "https://ourworldindata.org/grapher/primary-energy-cons",
  "accessedOn": "2026-07-15",
  "series": []
}
```

상태 규칙:

- 관측 0개: `INSUFFICIENT_DATA`, 수치 필드는 null, 시계열은 빈 배열
- 최신 연도가 현재연도-2 이상: `CURRENT`
- 그보다 오래됨: `STALE`

시계열은 최대 80개 최신 관측만 반환한다. 이 상한은 응답 크기 경계이며 입력
행 수를 강제하는 기준이 아니다.

## 8. 프런트엔드

Tracker 탭에 `KIndexCard`를 추가한다.

필수 표시:

- `인류 문명 지수 K = 0.7305`
- `구성 게이지 · 연례 관측 · 자동 효과 없음`
- `Type I까지 ΔK 0.2695 · 현재 에너지의 약 496배`
- 최신 관측연도와 연간 변화
- `대체법 기준` 또는 `유효 에너지 기준`
- 출처 링크와 접근일
- 최근 최대 20개 연도의 작은 추이선

K=1 도달연도는 계산하거나 표시하지 않는다. `STALE`은 경고 문구만 표시하고
게이지 값을 숨기지 않는다. `INSUFFICIENT_DATA`는 준비 중 상태를 표시한다.

## 9. 정직성·보안·운영

- K는 실측 에너지에서 계산한 **구성 지수**다.
- 소수점 네 자리는 시각적 추이용이며 네 자리 측정 정확도를 주장하지 않는다.
- 우주 활동에 할당된 에너지를 뜻하지 않는다.
- P4와 장기 정합 비교는 후속 관측 리포트의 각주일 뿐 자동 경보가 아니다.
- 런타임 egress, 신규 secret, 신규 pod, Kubernetes CronJob은 없다.
- Flyway 변경과 애플리케이션 배포는 GitOps를 통해서만 적용한다.

## 10. 테스트와 완료 조건

1. 계산기 기준값·경계값 테스트
2. CSV 정상·중복·잘못된 수치·혼합 기준·위험 URL 테스트
3. 정확한 60행에 고정되지 않는 검증 테스트
4. 같은 해시 no-op·해시 충돌·새 버전 upsert 통합 테스트
5. API 현재·오래됨·데이터 없음 테스트
6. UI 현재·오래됨·데이터 없음·출처·정직성 문구 테스트
7. 전체 backend 회귀, frontend 전체 테스트, production build
8. 로컬 Tracker 화면과 브라우저 console error 0 확인

## 11. 범위 밖

- K=1 도달 ETA
- K값으로 readiness·ETA·스냅샷 변경
- 분기 에너지 추정
- 라이브 OWID/IEA API 잡
- 우주 에너지 할당 비율 추정
- A↔P4 자동 경보 또는 불확실성 확대
