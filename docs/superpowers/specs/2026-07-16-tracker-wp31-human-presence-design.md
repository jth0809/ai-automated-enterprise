# Tracker WP3.1 궤도 체류 인일 설계

> 마스터 계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> Phase 3 상세: [2026-07-14-tracker-phase3-kickoff-plan.md](../plans/2026-07-14-tracker-phase3-kickoff-plan.md)

**상태:** 사용자 전체 승인 완료(2026-07-16). 구현 전 확정 사양이다.

## 1. 목표

공개된 전 세계 궤도 인구 변화 기록을 검수 CSV로 고정하고, 각 완료 연도의
`궤도 체류 인일`과 `최대 동시 궤도 인원`을 결정론적으로 계산해 Layer B 필라 2
관측값으로 제공한다. 이 지표는 실제 인간 우주 체류 실적을 보여 주는 비-AI
앵커이며 역량 노드, 준비도, ETA를 직접 변경하지 않는다.

## 2. 확정 범위

1. 지리·운영 범위는 **전 세계 궤도 우주비행**이다.
2. ISS, Tiangong, 자유비행 우주선을 포함한다.
3. 준궤도 비행은 제외한다. 입력의 `orbit_population` 열만 사용한다.
4. 공개 지표는 다음 두 개다.
   - `ANNUAL_ORBITAL_HUMAN_PERSON_DAYS`
   - `MAX_SIMULTANEOUS_HUMANS_IN_ORBIT`
5. 두 지표 모두 필라 2, basis `MEASURED`다.
6. 연간 체류 인일은 UTC 변화 시점 사이의 궤도 인원을 구간 적분한다.
7. 명시적으로 닫힌 완료 연도만 적재한다. 진행 중 연도는 검수 CSV에 둘 수 있지만
   `complete_through_utc` 이후 구간은 공개 지표에서 제외한다.
8. Layer C 사건 승격과 LL2→Layer C 승격은 LIVE_MODEL 활성화 이후 별도 설계로
   유지한다. 이번 구현은 어떤 사건·노드·스냅샷도 쓰지 않는다.

## 3. 지표 정의

연도 `y`와 궤도 인구 변화점 `(t_i, p_i)`에 대해 다음 변화점까지 인구가
`p_i`로 유지된다고 본다.

```text
person_days(y) = Σ p_i × duration([t_i, t_(i+1)) ∩ year(y)) / 86400 seconds
max_population(y) = max p_i over non-empty clipped intervals in year(y)
```

- 계산 시간대는 UTC다.
- 윤년은 실제 초 수로 반영한다.
- 체류 인일은 `HALF_UP` 네 자리로 반올림한다.
- 최대 인원은 정수다.
- 연도 경계에서 구간을 절단하므로 한 구간이 두 연도에 이중 계산되지 않는다.

검수 데이터의 기대 결과:

| 연도 | 궤도 체류 인일 | 최대 동시 궤도 인원 |
|---:|---:|---:|
| 2024 | 4241.8711 | 19 |
| 2025 | 3922.2028 | 14 |

## 4. 출처와 저작권 경계

- 주 출처: Jonathan McDowell, `Human Spaceflight Population - Time History`
  (`https://planet4589.org/space/astro/web/pop.html`)
- 정의 대조: 같은 사이트의 연간 통계 설명
  (`https://planet4589.org/space/astro/web/annual.html`)
- 운영 맥락 대조: NASA ISS expedition 목록
  (`https://www.nasa.gov/international-space-station/expedition-missions/`)
- 최초 검수일: `2026-07-16`

저장소에는 이름, 임무 설명, 페이지 본문, 인용문, HTML, PDF, 이미지가 들어가지
않는다. 필요한 UTC 변화 시점과 정수 궤도 인원, 출처 메타데이터만 보존한다.
각 Layer B 행의 `content_sha256`은 원격 페이지 본문 해시가 아니라 **검수 CSV
스냅샷의 SHA-256**이다. 이 의미를 코드와 증거 문서에 명시한다.

## 5. 검수 CSV 계약

파일:
`apps/backend/springboot-app/src/main/resources/tracker/human-presence-transitions-v1.csv`

```csv
# dataset_version=human-presence-v1
# source_label=Jonathan's Space Report orbital population time history
# source_url=https://planet4589.org/space/astro/web/pop.html
# accessed_on=2026-07-16
# complete_through_utc=2026-01-01T00:00:00Z
timestamp_utc,orbit_population
2024-01-01T00:00:00Z,10
2026-01-01T00:00:00Z,10
```

검증 규칙:

- UTF-8, 최대 256 KiB, 최대 5000개 변화점이다.
- 메타데이터 키 5개와 두 열 헤더가 정확히 한 번 존재해야 한다.
- 버전은 `human-presence-vN`, URL은 사용자정보·fragment·비표준 포트 없는
  `https://planet4589.org/...`다.
- 접근일은 ISO 날짜다.
- `complete_through_utc`는 1월 1일 00:00:00 UTC이며 해당 변화점이 실제로
  존재해야 한다.
- 변화 시점은 UTC `Z` ISO instant이며 엄격히 증가한다. 중복 시점은 거부한다.
- 궤도 인원은 `0..50` 정수다. 이 상한은 입력 오류 경계이지 기술 예측이 아니다.
- 첫 변화점과 모든 계산 대상 연도의 1월 1일 경계가 존재해야 한다.
- 최소 한 개 완료 연도를 포함해야 한다.
- `complete_through_utc` 이후 변화점은 검수 중 초안으로 허용하지만 집계에서
  제외한다.
- 빈 행, 추가 열, 따옴표 CSV, 알 수 없는 메타데이터는 거부한다.

## 6. 백엔드 구조

- `HumanPresenceTransition`: UTC 시각과 궤도 인원.
- `HumanPresenceDataset`: 버전·출처·검수일·완료 경계·변화점.
- `HumanPresenceCsvValidator`: 엄격한 CSV/메타데이터 검증.
- `HumanPresenceAggregator`: 순수 구간 적분과 연도별 결과 계산.
- `HumanPresenceLoader`: 원본 바이트 SHA-256, 같은 버전 해시 잠금, 충돌 사전
  검사, 트랜잭션 적재, import 감사행 기록.

기존 V11 `layer_b_metric`과 `layer_b_metric_import`를 재사용하므로 새 Flyway
마이그레이션은 없다. 가져오기 규칙은 다음과 같다.

- 같은 버전·같은 해시: no-op
- 같은 버전·다른 해시: 실패
- 새 버전: 완료 연도 지표를 생성하고 자연 키 충돌을 먼저 검사한 뒤 적재
- 기존 같은 자연 키의 모든 값·출처가 동일: 호환으로 인정
- 기존 같은 자연 키의 값 또는 provenance가 다름: 쓰기 전에 전체 실패
- import `record_count`는 변화점 수가 아니라 생성된 Layer B 지표 수다.

## 7. API와 프런트엔드

기존 `GET /api/tracker/layer-b`와 `LayerBPanel`을 재사용한다. 최신 연도 지표 두
개가 Pillar 2 행으로 함께 표시된다.

표시 요구사항:

- `연간 궤도 인류 체류`와 `연중 최대 동시 궤도 인원`이라는 사람이 읽는 이름
- `인일`, `명` 단위
- 체류 인일 네 자리 보존
- basis `측정`
- 관측일, 출처 링크, 검수 접근일
- `전 세계 궤도 기준 · 준궤도 제외 · 자동 점수 효과 없음` 정직성 문구

API 변경은 기존 필드를 유지하고 `accessedOn`만 추가하는 후방 호환 확장이다.

## 8. 보안·운영 경계

- 런타임 네트워크 호출, 신규 egress, secret, pod, Kubernetes CronJob이 없다.
- 앱 시작 시 로컬 검수 리소스를 적재하며 `TRACKER_HUMAN_PRESENCE_ON_BOOT`
  기본값은 true다. tracker 전체가 false이면 실행되지 않는다.
- CSV 갱신은 새 버전 파일과 새 dataset version으로 PR 검수한다.
- `Params.defaults()`, 35노드, r2.0, readiness, ETA, event, snapshot을 변경하지
  않는다.
- LIVE_MODEL, LL2 Layer C, 공식 인덱스 live polling, Metaculus live polling은
  계속 비활성이다.

## 9. 테스트와 완료 조건

1. 정상 CSV, 메타데이터 누락, 위험 URL, 중복·역순 시각, 범위 밖 인원,
   연도 경계 누락, 미완료 연도 제외 검증 테스트
2. 윤년·연도 경계·최대 인원·네 자리 반올림 집계 테스트
3. production CSV가 2024/2025 기준값을 재현하는 테스트
4. 첫 import, 같은 해시 no-op, 같은 버전 해시 충돌, 자연 키 충돌 rollback 테스트
5. Layer B API에 Pillar 2 두 지표와 `accessedOn`이 노출되는 테스트
6. UI 이름·단위·정직성·출처·네 자리 표시 테스트
7. 전체 backend/frontend/build/GitOps 검증과 실제 Spring API 브라우저 확인

## 10. 범위 밖

- 준궤도 체류 인일
- 우주정거장 전용 지표
- 개인·임무별 데이터 저장
- 진행 중 연도의 잠정값 공개
- 체류 인일에서 TRL/EGL 또는 26개월 통합 수송 노드 자동 추론
- LIVE_MODEL 활성화와 LL2 Layer C 사건 생성
