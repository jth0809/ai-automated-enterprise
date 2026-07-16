# Tracker WP4.2 완전 추세 모델 검증 증거

검증일: 2026-07-16 KST

브랜치: `codex/tracker-phase4`

범위: V17, 활성 파라미터 로딩, 역사 DAG 재생, 적응형 추세, 스냅샷 통합

## 결론

WP4.2 완료 조건을 충족했다. 현재 스냅샷과 역사 재생은 모두
`graph-v1.0`과 유일한 활성 `params-v2`를 사용한다. 필라별 추세는 확인된
상태 변화 사건만으로 창과 사건 수를 결정하고, rollback 수준 이동 더미와 인간이
승인한 구조 단절을 적용한 뒤 여섯 필라 공통 사전값으로 부분 풀링한다. 양의 기울기
하한은 없으며 `trend_used <= 0`이면 ETA를 만들지 않는다.

현재 `eta_low`·`eta_high`는 WP4.3 전까지의 회귀 잔차 기반 임시 구간이다.
WP4.3에서 오른쪽 검열을 포함한 몬테카를로 모델 내 80% 구간으로 교체한다.

## 구현·커밋 추적

| 작업 | 커밋 | 핵심 결과 |
|---|---|---|
| 상세 실행계획 | `e79ed3c` | 무누출·부분 풀링·개입·단절 계약 고정 |
| V17 모델 레지스트리 | `c3812a7` | `params-v2`, 불확실성 9종, 단절·projection 스키마 |
| 활성 파라미터 로더 | `443f99a` | 정확히 하나의 활성 버전, 범위·맵 fail-closed 검증 |
| 역사 DAG 재생 | `d061a97` | `weekly-projector-v2`, raw/effective·graph·params 감사 |
| 완전 추세 수학 | `86ac834` | 적응형 창, 단계 회귀, 수축, 모멘텀 분리 |
| 운영 스냅샷 통합 | `101bcb4` | `params-v2`와 여섯 필라 추세를 원자적으로 저장 |

기존 V1–V16 파일과 기존 스냅샷 행은 삭제하거나 다시 쓰지 않았다.

## 스키마와 파라미터

- 적용 Flyway 버전: V1–V17, 총 17개
- 활성 파라미터: 정확히 1개, `params-v2`
- 중앙값: `epsilon=.01`, `k=4`, `m=6`, fallback 10년, 창 4–15년
- 불확실성 레지스트리: 정확히 9개
  - `mc_samples`: 1,000 / 4,000 / 10,000
  - `trend_covariance_scale`, `node_weight_concentration`, `mapping_sigma`
  - `delta_scale`: 0.75 / 1.00 / 1.25
  - `k_log_sigma`, `dormancy_start`, `dormancy_step_per_decade`,
    `dormancy_floor`
- 승인 구조 단절 초기 행: 0개. 2010년을 자동 단절로 시드하지 않았다.
- `projection_run`의 현재 결과는 완료 run에만 허용된다.

## 계산 불변식

### 적응형 창과 무누출

확인된 `node_state_history`의 실제 level/status 전이와 확인된 원인 event만
사용한다. SQL에서 `occurred_on <= asOfDate`로 제한한 후 Java에서 다시 검사한다.
미래 snapshot·사건·단절은 조용히 제외하지 않고 실패시킨다.

```text
W_p = clamp(ceil(m × median_positive_interval_years), 4, 15)
trend_used = (n × trend_fit + k × trend_prior) / (n + k)
```

윤년 때문에 정확한 연간 주기가 6년에서 7년으로 튀지 않도록 일 단위 관측
해상도 안의 정수 연도 오차만 정규화한다. 세 개 미만의 서로 다른 상태 변화
날짜는 10년 fallback을 사용한다.

### 개입·부분 풀링·모멘텀

- rollback은 post-event 수준 이동 더미다. 합성 계열에서 실제 달력 기울기
  `+0.10/year`와 일회성 이동 `-0.50`을 각각 복원했다.
- 여섯 유한 `trend_fit`의 동일 cutoff 산술 평균만 `trend_prior`가 된다.
- `n=0`은 사전값, `n>>k`는 개별 적합, 음수 사전값은 음수 그대로다.
- `MomentumService`는 `ACCELERATING`, `STEADY`, `DECELERATING`,
  `INSUFFICIENT_DATA`만 반환한다. ETA 경로는 이 타입을 받지 않는다.

## TDD와 회귀 증거

의도한 RED를 먼저 관측했다.

- V17 테이블 부재와 활성 버전 불일치
- `CompleteTrendModel`·적응형 창·단계 회귀 클래스 부재
- 기존 `SnapshotJob`의 `params-v1`, null 사건 수, 단절 미적용, 잘못된
  활성 파라미터를 무시하는 네 실패

구현 중 발견한 두 문제도 원인별로 고쳤다.

- 윤년 365.5일의 `ceil` 경계: 일 단위 해상도 정규화 후 적응형 창 5/5
- Spring 다중 생성자 선택: 운영 생성자에 명시적 주입 후 컨텍스트 정상화

최종 검증:

| 명령/범위 | 결과 |
|---|---:|
| 적응형 창·단계 회귀·완전 모델·feature·모멘텀 집중 | 19/19 |
| snapshot·controller·transport 영향 집중 | 33/33 |
| Maven 전체 회귀 | 650/650 |
| Vitest 전체 회귀 | 79/79 |
| TypeScript + Vite production build | 성공, 48 modules |
| `git diff --check` | 통과 |
| 신규 `tracker/math` egress 패턴 검색 | 일치 0 |

## 실제 참조 백필 런타임

실행 프로필은 `test,demo,refbackfill`이며 외부 네트워크 수집은 사용하지 않았다.
V17 적용 후 승인 claim 147건을 가져오고 2026-07-16 snapshot을 계산했다.

| 필라 | 유효 준비도 | `trend_used` | ETA |
|---:|---:|---:|---:|
| P1 수송 | 0.4624 | 0.04134272 | 2072.1 |
| P2 생명 유지 | 0.1842 | 0.05509935 | 2085.0 |
| P3 거주 인프라 | 0.1470 | 0.05800415 | 2086.8 |
| P4 자원·에너지 | 0.1920 | 0.05001275 | 2090.0 |
| P5 로봇·자율 운영 | 0.2991 | 0.06815510 | 2064.5 |
| P6 경제·거버넌스 | 0.2667 | 0.04340188 | 2089.8 |

- 전체 유효 준비도: 0.1470
- 전체 ETA: 2090.0
- 임시 잔차 기반 구간: 2088.2–2091.7
- readiness 병목: P3, ETA 제한 필라: P4
- Spring 로그: `overall readiness 0.147, eta 2089.953807271339`

API의 기존 `momentum` 숫자 필드는 현재 하위 호환을 위해 `trend_used`를 노출한다.
WP4.6 공개 계약에서 표시 전용 모멘텀 enum과 ETA 기울기 설명을 분리한다.

## 브라우저 검증

최신 표준 Vite 프록시 `5176 -> 8080`에서 Tracker를 새로 고쳤다.

- 도착 시나리오: `2090`, `2088–2092`
- 필라 표시: `2072 / 2085 / 2087 / 2090 / 2065 / 2090`
- console error: 0

보호된 로컬 `vite.wp33.local.config.ts`를 쓰는 5175 서버는 의도적으로 8082의
과거 격리 백엔드를 가리켰다. 검증 기준이나 변경 파일로 사용하지 않았고,
사용자에게 열어 둔 최신 화면은 5176이다.

## 안전·범위 확인

- 새 HTTP 클라이언트, URL, socket, feed key 또는 API key 참조 없음
- `TRACKER_ENABLED=false`와 Phase 4 자동 실행·live polling 기본값 불변
- 신규 egress, secret, pod, CronJob, GitOps 리소스 없음
- `.claude/`, demo/refbackfill fixture, 로컬 Vite 설정은 미추적·미스테이징 유지
- WP4.3–WP4.6과 G4 2주 무개입 관찰은 아직 완료하지 않았다.
