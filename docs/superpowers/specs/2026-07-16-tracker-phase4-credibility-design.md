# Tracker Phase 4 신뢰도 프로그램 설계

날짜: 2026-07-16
상태: 사용자 승인 완료
범위: WP4.1–WP4.6, G4 소프트웨어 게이트
브랜치: `codex/tracker-phase4`

## 1. 목적

Phase 4는 기존 Tracker의 단순 준비도·회귀 ETA를 검증 가능하고 공개 가능한
신뢰도 프로그램으로 완성한다. 최종 산출물은 다음 여섯 작업 패키지다.

1. 의존성 DAG에 따른 유효 준비도
2. 사건 빈도 기반 회귀 창, 추세 수축, 개입·구조 단절
3. 파라미터 섭동 몬테카를로와 모델 내 80% 구간
4. 시점 절단 hindcast와 2010 체제 경계 홀드아웃
5. 6–24개월 마이크로 예측, 만기 판정, Brier 트랙 레코드
6. 수식·파라미터·검증 결과·정직성 표기를 공개하는 방법론 페이지

Phase 4의 핵심 원칙은 관측된 노드 상태와 모델이 계산한 유효 상태를 분리하고,
장기 ETA를 세계에 대한 확률이 아닌 **명시된 가정 하의 시나리오 투영**으로
표시하는 것이다.

## 2. 승인된 범위와 경계

### 2.1 포함

- WP4.1부터 WP4.6까지 순서대로 구현한다.
- 기존 역사 백필을 사용해 오프라인 백테스트와 첫 마이크로 예측 세트를 만든다.
- 읽기 API와 React 공개 화면을 실제 로컬 Spring/Vite 서버에서 검증한다.
- 각 계산 결과에 노드·루브릭·파라미터·데이터셋 버전을 기록한다.
- 기존 Phase 1–3 API는 호환성을 유지하되 ETA 구간의 계산 근거는 Phase 4
  몬테카를로 결과로 승계한다.

### 2.2 제외 또는 지연

- `LIVE_MODEL`, LL2 Layer C, official-index polling, Metaculus polling은 활성화하지
  않는다.
- 외부 네트워크 연결, 신규 secret, 신규 상시 workload는 추가하지 않는다.
- G4의 “2주 무개입 안정 운영”은 소프트웨어 완성 후 실제 시간 경과로 검증하는
  운영 게이트로 남긴다.
- Brier 점수를 `delta_e`, `k`, `m`, `W_p` 또는 노드 가중치의 직접 최적화
  목표로 사용하지 않는다.
- 기존 파일과 보호된 로컬 fixture를 삭제하거나 스테이징하지 않는다.

### 2.3 변경 불가 불변식

- LLM은 수치·확률·준비도·ETA를 생성하지 않는다.
- 관측된 `current_level`은 DAG가 낮추거나 높이지 않는다.
- Layer A, Layer B, 외부 예측은 Layer C 준비도를 자동 갱신하지 않는다.
- 모든 상태·결과 쓰기는 멱등이며, 실패한 실행은 마지막 정상 결과를 덮어쓰지
  않는다.
- 역사 보정 구간과 검증 홀드아웃 사이에는 데이터 누수가 없어야 한다.
- 라이브 발행·수집 플래그의 GitOps 기본값은 `false`다.

## 3. 구현 순서

권장 구조는 수학적 선행관계를 따르는 단일 신뢰도 스택이다.

```text
WP4.1 DAG
  -> WP4.2 완전 추세 모델
     -> WP4.3 몬테카를로
        -> WP4.4 백테스트·보정
           -> WP4.5 마이크로 예측·Brier
              -> WP4.6 투명성 페이지
```

각 WP는 별도 상세 계획과 검증 커밋을 갖는다. 후속 WP는 앞선 WP의 공개
인터페이스만 사용하고 내부 구현에 직접 결합하지 않는다.

## 4. WP4.1 — 의존성 DAG와 유효 준비도

### 4.1 현재 불일치

`capability_edge`의 명세는 동일 `(to_node_id, or_group)` 안의 간선을 OR로
집계하고 그룹 간에는 AND로 집계한다. 그러나 V6의 29개 필수 통합노드 입력은
모두 `or_group=1`로 저장돼 있다. 따라서 “모든 입력 필수”라는 노드 레지스트리와
달리 현재 스키마 의미로는 가장 높은 선행 노드 하나만 사용하게 된다.

기존 V6 파일은 수정하지 않는다. 후속 Flyway migration에서 nodes-v1.0의 필수
입력을 각각 안정적인 별도 그룹으로 재번호화한다. 실제 대체 경로만 동일 그룹을
공유한다.

### 4.2 계산 규칙

노드 `c`의 관측 준비도를 `r_c`, 입력 간선을 `e`, 간선의 선행 노드를 `from(e)`,
허용 마진을 `delta_e`라고 한다.

```text
edge_cap(e) = min(1, r_eff[from(e)] + delta_e)
group_cap(g) = max(edge_cap(e) for e in group g)       # OR
dependency_cap(c) = min(group_cap(g) for groups of c)  # AND
r_eff[c] = min(r_c, dependency_cap(c))
```

입력이 없는 노드는 `r_eff[c] = r_c`다. 이 정의는 간선별 `delta_e`를 보존하며,
선행 0.30과 기본 마진 0.15가 후행을 0.45로 제한하는 컨셉 예제를 그대로
재현한다.

### 4.3 컴포넌트

- `CapabilityEdgeRow`: 저장소 경계의 간선 값 객체
- `CapabilityGraph`: 노드·간선의 불변 스냅샷
- `CapabilityGraphValidator`: 존재하지 않는 노드, 자기 간선, 중복, 범위 밖
  `delta_e`, 빈 그룹, 순환을 거부
- `EffectiveReadinessEngine`: 위상 정렬 한 번으로 raw/effective/cap 원인을 계산
- `ReadinessResult`: 노드별 raw/effective, 제한 그룹, 제한 선행 노드와 필라 합계

그래프 검증 실패 시 snapshot 실행을 중단하고 기존 정상 snapshot을 보존한다.
오류는 `ops_state`에 bounded 진단으로 남기며, 그래프 일부만 적용하지 않는다.

### 4.4 역사 재생

`WeeklyBackfillProjector`도 동일 `EffectiveReadinessEngine`을 사용한다. 현재 상태와
역사 상태가 서로 다른 공식을 사용하지 않도록, 특정 날짜의 노드 상태를 재생한 뒤
같은 그래프 스냅샷으로 필라 준비도를 계산한다.

## 5. WP4.2 — 완전 추세 모델

### 5.1 파라미터 로딩

기존 `parameter_set`을 실제 계산의 진실 원천으로 활성화한다. `params-v2`는
애플리케이션 시작 시 엄격히 검증하고 immutable `ModelParameters`로 로드한다.
`Params.defaults()`는 테스트·부트스트랩 fallback으로만 남기며 운영 계산은 활성
DB 버전을 사용한다.

검증 항목은 다음과 같다.

- `epsilon`은 `(0, 0.5)`
- `k > 0`, `m > 0`
- `4 <= window_min <= window_fixed <= window_max <= 15`
- TRL/EGL 사상은 0–1 범위에서 단조 증가하고 L9가 1.0
- 휴면 곡선은 단조 비증가하고 0–1 범위
- ETA clamp와 표시 감쇠 값은 양수

### 5.2 필라별 회귀 창

상태 변화 사건은 확인된 전진, rollback, 휴면·복원 전이만 포함한다. 기사 수,
중복 보도, 발표 전용 사건은 세지 않는다.

```text
W_p = clamp(m * median_interval_years, 4, 15)
```

유효한 상태 변화 간격이 두 개 미만이면 기존 `window_fixed_years=10`을 사용한다.
필라별 임의 창을 백테스트로 탐색하지 않고 공통 계수 `m`만 보정한다.
`median_interval_years`, 사건 수 `n`, 활성 regime은 모두 계산의 `asOfDate` 이하
사건만으로 산출한다. hindcast에서는 cutoff 이후 사건이 회귀 창이나 희소성 판정에
간접적으로도 들어갈 수 없다.

### 5.3 사건 수축

```text
trend_used = (n * trend_fit + k * trend_prior) / (n + k)
```

- `n`: 현재 regime과 `W_p` 안의 상태 변화 사건 수
- `trend_fit`: 시간 감쇠 가중 회귀의 기울기
- `trend_prior`: 보정 데이터에서 계산한 여섯 필라의 유한 기울기 산술 평균
- `k`: 기본 4, 백테스트 보정 대상

`trend_prior`가 0 이하인 경우도 그대로 보존한다. 양의 ETA를 만들기 위한 임의
floor는 두지 않으며, `trend_used <= 0`이면 ETA는 미해결 상태다.

### 5.4 개입과 구조 단절

- 확인된 경미한 rollback은 가중 회귀의 수준 이동 더미로 처리한다. 낙하는
  readiness에 즉시 반영하지만 일회성 낙하를 영구 하락 기울기로 외삽하지 않는다.
- 구조 단절은 자동 추론하지 않는다. 인간이 승인한 `model_regime_break` 레코드만
  사용하며, 필라·발생일·원인 사건·검수 메모·버전을 저장한다.
- 구조 단절 뒤의 추세는 마지막 승인 단절 이후 데이터만 사용한다. 단절 이전
  데이터는 현재 기울기에는 사용하지 않지만 감사·백테스트에서 보존한다.
- 2010년은 WP4.4의 홀드아웃 경계이며 자동 runtime 단절로 취급하지 않는다.

### 5.5 모멘텀 분리

Holt 계열 계산은 ETA에 사용하지 않는다. 별도 `MomentumService`가 UI의
`ACCELERATING`, `STEADY`, `DECELERATING`, `INSUFFICIENT_DATA`만 계산한다.

## 6. WP4.3 — 몬테카를로 시나리오 투영

### 6.1 실행과 재현성

- 기본 4,000회, 허용 범위 1,000–10,000회
- seed는 snapshot 날짜, 데이터셋 hash, 노드·파라미터 버전으로 결정
- 동일 입력은 byte-stable 요약 결과를 생성
- 주간 snapshot 안에서 실행하되 기존 정상 결과를 원자적으로 교체
- 모든 개별 표본은 저장하지 않고 분위수·검열 비율·진단 요약만 저장

### 6.2 섭동 대상

- 회귀 절편·기울기: 적합 공분산 기반 정규 표본
- 노드 가중치: 양수 표본 후 필라별 합 1.0으로 재정규화
- TRL/EGL 사상: bounded perturbation 후 단조성 재투영
- `delta_e`: registry의 간선별 상대값은 고정하고 모든 간선에 공유되는 하나의
  배율 `s_delta`를 표본화한 뒤 0–0.5 범위로 clamp
- `k`: 양수 log-normal 분포
- 휴면 시작·감쇠·floor: 순서와 범위를 보존하는 bounded 분포

분포 폭과 concentration은 파라미터 버전에 포함하며 소스 코드 상수로 숨기지
않는다. 잘못된 표본은 조용히 버리지 않고 invalid-sample 카운트를 기록한다.

### 6.3 오른쪽 검열

각 표본의 ETA가 `현재+150년`을 넘거나 기울기가 양수가 아니면
`HORIZON_CENSORED`로 취급한다. 검열 표본을 제외하고 분위수를 계산하면 결과가
낙관적으로 치우치므로, 정렬 시 상한 뒤의 질량으로 포함한다.

- 요청 분위수가 검열 질량에 걸리면 해당 분위수는 `null`/`2175+`다.
- `censoredFraction`을 필라와 전체 결과에 공개한다.
- 전체 ETA는 표본별 여섯 필라 ETA의 최대값이며 하나라도 검열이면 그 표본의
  전체 ETA도 검열이다.

표시값은 중앙값과 10/90 분위수다. 라벨은
**“현 추세 지속 시나리오 기준 · 모델 내 80% 구간”**으로 고정한다.

### 6.4 저장

새 저장 모델은 run/result로 분리한다.

- `projection_run`: 입력 hash, seed, 표본 수, 버전, 상태, 진단, 시작·완료 시각
- `projection_result`: run, 필라(0–6), readiness, p10/p50/p90,
  censored fraction, momentum

동일 입력 hash의 완료 run은 재사용하며 실패 run은 공개 API의 current 결과가
되지 않는다.

## 7. WP4.4 — 백테스트와 파라미터 보정

### 7.1 데이터 흐름

기존 검수 완료 역사 claim을 날짜순으로 재생한다. 각 cutoff에서는 그 날짜 이후의
사건·결과·현재 상태를 전혀 읽지 않고 당시 알 수 있었던 상태만 구성한다.
적응형 회귀 창, 사건 수축, 승인된 regime break를 포함한 모든 파생 feature도
cutoff 시점의 가시 데이터만 사용한다.

```text
historical claims
  -> cutoff replay
  -> DAG effective readiness
  -> complete trend model
  -> Monte Carlo projection
  -> later observed readiness/milestone comparison
```

### 7.2 체제 경계 홀드아웃

- 보정 구간: 1957-01-07부터 2009-12-31
- 검증 구간: 2010-01-01부터 최신 완료 주
- 보정 구간 결과만 파라미터 선택에 사용
- 홀드아웃은 한 번 계산한 뒤 결과를 보고 파라미터를 다시 고치지 않음
- 파라미터나 모델족을 바꾸면 새 버전과 새 run으로 전체 과정을 다시 실행

### 7.3 보정 대상과 목적함수

보정 대상은 공통 `m`, `k`와 공유 간선 배율 `s_delta`의 제한된 후보 집합이다.
`s_delta`는 정확히 `[0.75, 1.00, 1.25]` 중 하나이며
`delta_e' = clamp(delta_e * s_delta, 0, 0.5)`로 계산한다. 따라서 29개 간선을
독립 파라미터로 맞추지 않는다. 간선별 기준값을 바꾸려면 인간 검수를 거친 새
graph registry 및 모델 버전이 필요하다. 필라별 `W_p`, 노드 가중치, 루브릭 수준,
역사 정답은 자유 탐색하지 않는다.

목적함수는 보정 구간의 다음 항목으로 사전 고정한다.

- readiness MAE
- logit readiness MAE
- 다음 상태 전진 방향 정확도
- 80% 모델 구간의 경험적 coverage 편차
- cutoff 사이 ETA 과잉 변동 penalty

Brier 점수는 이 목적함수에 포함하지 않는다. 동점은 기본값에 가장 가까운 더
단순한 파라미터가 이긴다.

### 7.4 산출물

- `backtest_run`: 데이터·노드·루브릭·파라미터·코드 버전과 입력 hash
- `backtest_fold`: cutoff, regime, 예측 horizon, 상태
- `backtest_metric`: metric code, pillar, calibration/holdout 값, 표본 수
- machine-readable JSON과 사람이 읽는 Markdown 보고서

보고서는 보정 성능과 홀드아웃 성능을 나란히 표시하고, 표본 부족 필라는 숨기지
않고 `INSUFFICIENT_DATA`로 게시한다.

## 8. WP4.5 — 마이크로 예측, Brier, 라이브 캘리브레이션

### 8.1 예측 단위

예측 문장은 LLM이 만들지 않는다. 노드 레지스트리에서 결정론적으로 생성한다.

```text
<노드 이름>이 <due_on>까지 검증된 수준 L<target_level> 이상에 도달한다.
```

- 현재 L0–L7인 ACTIVE 노드만 후보
- target은 현재보다 한 단계 높은 등록 수준
- integration 노드는 자체 통합 증거가 있어야 해결되며 구성요소 최대값으로
  적중 처리하지 않음
- 발행 후 statement, 확률, due date, 버전은 immutable

### 8.2 위험률 모델

노드의 검증된 상태 전진을 Poisson 사건으로 두고 필라 사전분포로 수축한다.

```text
lambda_p = pillar advances / pillar node-years
lambda_node = (N_node + kappa * lambda_p) / (E_node + kappa)
P_raw(T) = 1 - exp(-lambda_node * T)
```

- `N_node`: 노출 구간 안에서 검증된 노드 전진 수
- `E_node`: 1957-01-07부터 계산 cutoff까지 주 단위 상태를 재생해 합산한 exposure
  years. 노드가 `ACTIVE`이고 L8 미만이며 다음 등록 수준이 유효한 기간만 포함하고,
  휴면 기간과 L8–L9 기간은 제외
- `lambda_p`: 같은 필라의 pooled rate
- `kappa`: hazard prior strength, 예측 파라미터 버전에 저장
- `T`: 0.5, 1.0, 1.5, 2.0년

이는 Gamma–Poisson posterior mean과 동치이며 희소 노드가 단일 사건으로 100%에
가까워지는 것을 막는다. 확률은 최종적으로 `[0.02, 0.98]`에 제한한다.
현행 node set을 1957-01-07부터 동일한 목표 체계로 재생하는 것은 명시된 모델
가정이다. 노드가 후대에 의미를 얻었다고 판단되면 과거를 자동 단축하지 않고,
인간 승인된 registry 유효 시작일을 새 버전에 기록해 그 시점부터 노출한다.

### 8.3 발행 정책

- 한 cohort에 최대 12개, 필라당 최대 2개
- 6·12·18·24개월 중 정보량 `p(1-p)`이 가장 큰 horizon 하나를 선택
- 동일 정보량이면 노드 weight, code 순으로 결정
- `0.10 <= p <= 0.90` 후보를 우선하고 부족하면 경계 밖 후보를
  `LOW_INFORMATION` 표시와 함께 채움
- 첫 세트는 승인된 역사 데이터로 오프라인 생성해 공개 API에 게시
- 이후 자동 발행 job은 기본 비활성이고 별도 인간 승인 전 실행하지 않음

### 8.4 만기 판정과 Brier

- due date 이전에 target 이상으로 확인된 최초 상태 전이가 있으면 `HIT`
- due date까지 없으면 `MISS`
- node-set 승계 또는 루브릭 의미 변경으로 발행 당시 술어 자체를 더는 판정할 수
  없는 경우만 `VOID`
- 불리한 결과, 프로그램 취소, 데이터 부족은 `VOID` 사유가 아님
- `brier = (issued_probability - outcome_binary)^2`
- cohort, horizon, pillar, 전체의 평균과 표본 수를 함께 공개

해결 job은 멱등이며 outcome evidence event와 해결 시각을 저장한다. 인간 검수
필수 수준은 기존 상태 전이 관문을 그대로 통과해야 한다.

### 8.5 확률 재보정과 드리프트

- 해결 예측 30건 미만 또는 4개 분기 미만이면 identity calibration 사용
- 조건 충족 후 시간 순서 out-of-sample fold에서 PAVA isotonic calibration 적용
- 보정 전·후 확률을 모두 보존하고 calibration version을 게시
- calibration-in-the-large, Brier 추세, 예측 빈도 분포가 관리 한계를 넘으면
  drift alert를 생성
- drift는 예측 발행을 동결할 수 있지만 구조 파라미터를 자동 변경하지 않음

30건·4개 분기는 과적합을 줄이기 위해 사전 선언한 최소 운영 gate이지 통계적
보정 품질을 보장하는 기준이 아니다. gate 통과 뒤에도 표본 수와 신뢰도 진단을
공개하며 근거가 부족하면 `INSUFFICIENT_CALIBRATION_DATA`를 유지한다.

## 9. WP4.6 — 방법론 투명성 페이지

### 9.1 공개 API

- `GET /api/tracker/methodology`
- `GET /api/tracker/dag`
- `GET /api/tracker/projections/current`
- `GET /api/tracker/backtests/latest`
- `GET /api/tracker/predictions`
- `GET /api/tracker/predictions/scorecard`

응답에는 계산값뿐 아니라 입력 hash, 버전, as-of date, sample count, 표본 부족·검열
상태가 포함된다. admin 실행 API는 기존 token gate를 사용하며 public API와 분리한다.

### 9.2 React 화면

Tracker 안에 `방법론·신뢰도` 섹션을 추가한다.

- 현재 ETA와 모델 내 80% 구간, 검열 비율
- DAG 제한을 받은 노드와 제한 원인
- 활성 파라미터·공식·버전
- 보정/홀드아웃 백테스트 비교
- 마이크로 예측 목록, 만기, 확률, 결과, Brier
- Layer B 발산과 외부 예측 대조 상태
- 데이터 소스·갱신일·라이브 비활성 상태

표는 375px에서 문서 전체 overflow를 만들지 않고 자체 스크롤 영역을 가져야 한다.
키보드 접근, 명시적 heading, 상태 텍스트를 제공하며 색만으로 상태를 전달하지
않는다.

### 9.3 필수 정직성 표기

다음 네 문장은 축약하거나 숨기지 않는다.

1. ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델
   내부의 80%다. 모형족 오류와 미지의 구조 단절 확률은 포함하지 않는다.
2. 수송 `$ / kg`은 실제 원가가 아니라 공개된 가격을 바탕으로 한 추정치다.
3. 관측 사건은 측정값이고 TRL/EGL 사상·가중치·DAG 집계는 구성 지수다.
4. 수송 경제성 임계값은 자연상수가 아니라 공개된 모델 가정이다.

## 10. 오류 처리와 운영 안전

- 그래프·파라미터·데이터셋 검증 실패: fail closed, 기존 current 결과 유지
- 몬테카를로 invalid sample: 개수와 원인을 기록하고 허용 비율 초과 시 run 실패
- 백테스트 데이터 누수 탐지: run 전체 실패
- prediction 중복 발행: cohort/node/target unique key로 멱등 거부
- 만기 해결 충돌: 동일 outcome은 재실행 허용, 다른 outcome은 인간 큐로 격리
- read API는 마지막 완료 run만 노출
- 계산 job은 ShedLock을 사용하며 free-tier CPU를 고려해 동시 실행하지 않음
- 백테스트는 admin/manual 전용, production CronJob을 추가하지 않음

GitOps 기본값:

```text
TRACKER_ENABLED=false
TRACKER_PHASE4_PROJECTION_ENABLED=false
TRACKER_PHASE4_BACKTEST_ENABLED=false
TRACKER_PHASE4_PREDICTION_ISSUANCE_ENABLED=false
TRACKER_PHASE4_PREDICTION_RESOLUTION_ENABLED=false
```

`TRACKER_ENABLED=false`이면 GitOps 운영 환경의 Tracker API와 job 전체가
비활성이다. 하위 Phase 4 플래그는 이 상위 gate를 우회할 수 없다. 로컬·테스트·
demo에서는 `tracker.enabled=true`로 읽기 API를 검증하되 live/polling 및 Phase 4
자동 job 플래그는 계속 false로 둔다. 읽기 API와 classpath 검수 데이터 로딩
자체는 네트워크 egress를 요구하지 않는다.

## 11. 데이터 마이그레이션 전략

적용된 migration은 수정하지 않고 V16 이후에만 추가한다.

- V16: DAG 그룹 의미 교정, 그래프 registry/version, graph audit 제약
- V17: parameter uncertainty, regime break, projection run/result
- V18: backtest run/fold/metric
- V19: prediction 확장, cohort, resolution evidence, calibration/scorecard

마이그레이션은 H2 테스트와 Oracle 호환 SQL을 모두 통과해야 한다. 기존
`pillar_snapshot`과 `prediction` 데이터는 삭제하지 않고 새 구조로 승계한다.

## 12. 테스트 전략

### 12.1 WP4.1

- AND/OR 혼합 그래프와 `0.30 + 0.15 = 0.45` 예제
- 위상 정렬, 순환, self-edge, unknown node, delta 범위
- raw level 불변과 effective cap 전파
- 현재 계산과 역사 replay의 동일성

### 12.2 WP4.2

- 사건 간격 median과 4–15년 clamp
- sparse fallback 10년
- 사건 수축의 `n=0`, `n>>k`, 음수 prior
- rollback level-shift와 승인 구조 단절
- Holt 결과가 ETA에 영향을 주지 않음

### 12.3 WP4.3

- 동일 seed byte-stable 결과
- 가중치 합, mapping 단조성, bounded parameter property tests
- 오른쪽 검열 분위수와 전체 max 규칙
- invalid sample 임계·원자적 current 교체

### 12.4 WP4.4

- cutoff 이후 데이터 접근을 실패시키는 leakage test
- 2010 보정/검증 분리
- holdout 결과가 parameter selection에 입력되지 않음
- 보고서 hash와 재실행 멱등성

### 12.5 WP4.5

- hazard 식, 희소 수축, horizon 선택
- 발행 immutability와 최대 12/필라 2 제한
- HIT/MISS/VOID 규칙과 Brier 경계값
- PAVA 최소 표본 gate, drift가 구조 파라미터를 바꾸지 않음

### 12.6 WP4.6 및 회귀

- API schema와 honesty label exact-text tests
- React loading/error/partial/censored/responsive/accessibility tests
- 전체 Maven·Vitest·production build
- 실제 Spring API와 브라우저 desktop/375px/console 검증
- `git diff --check`, Gitleaks, Semgrep, GitOps egress verifier, kustomize

## 13. 성능·저장 예산

- 그래프: 35 노드, 현재 29 간선 규모에서 위상 계산은 선형 시간
- Monte Carlo: 기본 4,000회, 주간 실행, 단일 JVM 내 bounded executor
- DB에는 개별 표본 대신 7개 필라 요약과 진단만 저장
- 백테스트는 운영 요청 경로에서 실행하지 않음
- 공개 API는 완료 run 조회만 수행하고 계산을 유발하지 않음
- 신규 egress, LLM 호출, token 비용은 0

## 14. 완료 기준

### 14.1 Phase 4 소프트웨어 완료

- WP4.1–WP4.6의 명명된 기능과 테스트가 모두 구현됨
- 최신 역사 데이터로 백테스트 보고서가 생성되고 calibration/holdout이 분리됨
- 첫 마이크로 예측 cohort가 공개 API와 UI에 표시됨
- 방법론 페이지에 필수 정직성 표기 4건이 표시됨
- 기존 전체 회귀, build, GitOps, 보안 검사, 실제 브라우저 검증이 통과함
- 라이브 플래그는 모두 비활성이고 보호 fixture는 미추적 상태를 유지함

### 14.2 G4 운영 완료

위 소프트웨어 완료 뒤 승인된 운영 환경에서 2주 동안 무개입 안정 운영을
관찰한다. 이 시간 기반 증거가 없으면 Phase 4 코드는 완료될 수 있어도 G4
정식 런칭 게이트는 `PENDING_OBSERVATION`으로 남는다.

## 15. 작업·브랜치 정책

- 현재 linked worktree를 유지하고 `codex/tracker-phase4`에서 작업한다.
- PR #42가 병합되기 전에는 Phase 4를 stacked branch로 취급한다.
- 각 WP는 스펙/계획, RED, GREEN, 전체 회귀, 증거 기록 순으로 진행한다.
- 서브에이전트를 사용하지 않는다.
- 기존 파일을 삭제하지 않는다.
- `.claude/`, demo/refbackfill fixture, 로컬 Vite 설정은 스테이징하지 않는다.
