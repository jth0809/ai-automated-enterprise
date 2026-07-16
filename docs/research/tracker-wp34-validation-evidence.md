# WP3.4 예측 대조 패널 검증 증거

검증일: 2026-07-16 (KST)

상태: **완료 / 실제 백엔드·API·브라우저 통합 검증 완료 / live collector 비활성**

## 1. 비교 계약

패널은 `LANDING`, `RETURN`, `SETTLEMENT` 세 목표와 다음 네 출처군을 한 표에서
보이되, 서로 같은 예측으로 취급하지 않는다.

| 열 | 의미 |
|---|---|
| 트래커 모델 | 자립 정착 준비도 ETA의 직접 프록시. 착륙·귀환에는 적용하지 않음 |
| 수송경제 | 중앙 `$200/kg`, 민감도 `$100~$500/kg`의 가능 조건. 사건 날짜가 아님 |
| 군중예측 | 승인 후 90일 이동평균. 100명 화성 인구 질문은 약한 정착 프록시 |
| 기관 목표 | NASA·SpaceX의 검수된 현재·과거 설명. 공식 연도가 없으면 `UNDATED` |

## 2. 구현 범위

- Flyway V15 `forecast_reference`, `forecast_reference_import`와 식별 가능한
  `external_forecast` 관측 열·유니크 인덱스
- 엄격한 참조 리소스 검증, 같은 버전 해시 잠금, 트랜잭션 멱등 로더
- 일별 군중 관측 이력과 결정적 90일 산술 이동평균
- Metaculus 고정 `/api/posts/{id}/?with_cp=true` 클라이언트: redirect 금지,
  1 MiB 제한, `Authorization: Token`, 게시물 최대 2개, 게시물별 실패 격리
- 게시물 반복 전체에는 트랜잭션을 두지 않고 각 `saveCrowdObservation`만
  트랜잭션으로 유지해, 한 DB 실패가 다음 게시물의 성공을 rollback하지 않게 했다.
- `tracker.enabled`, `metaculus-enabled`, `metaculus-terms-approved`, 비어 있지 않은
  Vault 토큰의 삼중 활성화 경계. 현재 두 플래그는 모두 false이고 토큰 참조는
  GitOps에 추가하지 않았다.
- 승인 플래그만 켜고 토큰이 누락·공백·형식 오류인 경우 애플리케이션 시작을
  실패시키지 않고 수집기 빈 자체를 만들지 않는 조건과 회귀 테스트를 추가했다.
- 비교 API의 `crowdLiveStatus`도 같은 토큰 형식 게이트를 사용하므로, 수집 잡이
  비활성인데 화면만 `ENABLED`라고 표시하는 상태는 만들 수 없다.
- `GET /api/tracker/forecast-comparison`과 반응형 React 4열 패널
- 비교 API 실패를 핵심 Tracker API와 분리해, 비교 패널 오류가 기존 대시보드를
  내리지 않도록 처리

`tracker.enabled=false` 컨텍스트에서 조건부 `TrackerRepository`를 무조건 요구하던
위험을 정적 감사 중 발견해 `ForecastComparisonService`에도 동일한 조건부 경계를
적용하고 회귀 테스트를 추가했다.

2026-07-16 [Metaculus 공식 API 문서](https://www.metaculus.com/api/)와
[이용약관](https://www.metaculus.com/terms-of-use/)을 재확인했다. 현재 공식 계약은
모든 API 요청에 `Authorization: Token`을 요구하고 커뮤니티 집계 접근을 제한하며,
게시물 상세 경로는 `GET /api/posts/{postId}/`이다. 따라서 숫자 없는 질문 메타데이터와
승인 대기 상태만 배포하고 live collector를 끄는 현재 경계를 유지한다.

기관 목록의 `PRECURSOR`를 “현재 공식 설명”으로 표시할 수 있던 문구도 직접 리뷰에서
발견했다. 선행 목표·과거 목표·현재 설명을 각각 다른 라벨로 렌더하도록 계약과
회귀 테스트를 보강했다.

## 3. 검수 참조 리소스

`tracker/forecast-reference-v1.json` 정적·정규 해시 감사 결과:

| 항목 | 결과 |
|---|---:|
| 레코드 | 10 |
| 목표 | `LANDING`, `RETURN`, `SETTLEMENT` |
| 출처 | `NASA`, `SPACEX`, `METACULUS` |
| canonical record hash 불일치 | 0 |

구성은 NASA 현재 설명 3건, SpaceX 현재 설명 3건, SpaceX 2028 화물 선행 목표
1건, NASA 과거 2030년대 착륙 목표 1건, Metaculus 질문 메타데이터 2건이다.
Metaculus 행에는 커뮤니티 예측값이 없고 `AWAITING_AUTHORIZATION`만 저장한다.
검증기와 관측 저장소는 `NUMBER(6,1)`보다 정밀한 값 및 2026.0~2300.0 밖의 값
(실제 `0.0` 포함)을 DB 반올림·제약 단계 전에 거부한다.

## 4. 실행된 검증

- WP3.4 집중 테스트와 전체 백엔드 회귀를 포함한 Maven: **600 tests,
  failures 0, errors 0, skipped 0, BUILD SUCCESS**
- 프런트 전체: **Vitest 4.1.10, 17 files, 79/79 tests 통과**
- 원본 작업 트리의 TypeScript·Vite production build 통과: **48 modules transformed**,
  CSS 22.60 kB (gzip 5.00 kB), JS 183.97 kB (gzip 58.24 kB)
- 기존 `node_modules`를 변경하지 않고 `C:\tmp` 새 격리 복사본에 잠금파일 버전을
  설치했다. Vite 8.1.4, `@vitejs/plugin-react` 6.0.3, TypeScript 7.0.2,
  Vitest 4.1.10을 확인했고 **17 files / 79/79**와 production build를 통과했다
  (`37 modules transformed`, CSS 22.29 kB, JS 231.82 kB).
- GitOps verifier: `OK: tracker egress policy and safe defaults verified`
- Metaculus exact-host `www.metaculus.com:443` 1개, wildcard 0,
  `TRACKER_METACULUS_ENABLED=false`,
  `TRACKER_METACULUS_TERMS_APPROVED=false`, max posts 2, token 참조 0
- `kubectl kustomize gitops/apps/backend-springboot` 렌더 통과. 신규 Job/CronJob,
  Metaculus token secret, wildcard tracker egress는 없다.
- `git diff --check`, placeholder·secret 리터럴·workload·보호 파일 정적 감사 통과.
  Gitleaks 실행 파일은 로컬에 없어 private-key/AWS-key/토큰 패턴 대체 검사를
  실행했고 0건을 확인했다.

프런트 전체 회귀에서 기존 범용 fetch stub가 HTTP 200 `{error:"invalid"}`를
반환하자 패널이 `status.toLowerCase()`에서 실패하는 문제를 발견했다. API 경계에
3개 목표·4개 출처군의 런타임 응답 검증을 추가해 잘못된 200 응답도 패널 전용
실패로 격리했다.

## 5. 실제 API·화면 확인 결과

- Spring을 `test,demo,refbackfill` 프로필로 **8080**에 기동했다. V14/V15가 포함된
  15개 Flyway migration을 적용하고 forecast reference 10건과 governance 9건을
  classpath에서 가져왔다.
- `GET /api/tracker/forecast-comparison`은 HTTP 200, 정확히 `LANDING`, `RETURN`,
  `SETTLEMENT` 세 행과 model/transport/crowd/institutional 네 출처군을 반환했다.
  실제 로컬 DB에 projection이 없는 수송 열은 `INSUFFICIENT_DATA`로 유지됐고,
  Metaculus는 `AUTHORIZATION_REQUIRED`, 현재 기관 설명은 `UNDATED`로 유지됐다.
- 기존 5175가 mock 8082를 사용한다는 차이를 발견해 최종 증거에서는 제외했다.
  표준 Vite 설정(`/api → 8080`)을 **5176**에 별도 기동해 실제 API로 확인했다.
- 상단 `Tracker` 탭은 존재하고 활성화됐다. 세 목표×네 출처 열, 빈 값, 약한
  프록시, 선행 목표, 과거 목표 라벨과 안전한 출처 링크를 확인했다.
- 375px viewport에서 문서 자체 가로 넘침은 없고, 920px 표만 256px 컨테이너
  내부에서 자체 가로 스크롤했다. 브라우저 console error는 **0건**이다.

## 6. 완료 판정과 활성화 경계

4열 패널과 첫 분기 보고서가 실제 백엔드 연결 상태에서 검증됐으므로 WP3.4와 G3의
오프라인 게이트를 완료 처리한다. `PARTIAL`과 `INSUFFICIENT_DATA`는 검증 실패가
아니라 현재 자료 상태를 정직하게 나타내는 계약이다. LIVE_MODEL, LL2 Layer C,
WP3.5 live polling, Metaculus live polling은 계속 비활성이고, 이를 켜는 작업은
별도 인간 승인 사항이다.
