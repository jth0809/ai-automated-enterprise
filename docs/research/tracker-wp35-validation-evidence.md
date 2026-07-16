# WP3.5 수집 채널 확장 검증 증거

검증일: 2026-07-16 (KST)

상태: **완료 / 실제 백엔드·API 검증 완료 / LIVE_MODEL 미활성**

## 1. 구현 범위

- Flyway V14에 `article.evaluation_allowed` 격리 플래그와 필라 6
  `governance_record`·`governance_import` 원장을 추가했다.
- 기존 기사에는 `evaluation_allowed=Y`가 적용되고, ISRO·CNSA HTML 인덱스에서
  발견한 메타데이터 후보는 `evaluation_allowed=N`, 본문 추출 `SKIPPED`로만
  저장된다. 이 후보는 `INGESTED` 게이트 조회에서 제외된다.
- `ISRO`, `CNSA`, `CNSA_HOSTED_MEDIA`를 서로 다른 신뢰 경계로 등록했다.
  CNSA 자체 공지와 CNSA 사이트의 재전재 매체 자료를 같은 `OFFICIAL` 근거로
  취급하지 않는다.
- 세 개의 고정 HTML 인덱스 채널, same-host HTTPS 정규화, 중복 제거, 최대 40개
  링크 제한, 제한된 날짜 파서를 구현했다.
- 채널 반복 전체를 하나의 트랜잭션으로 묶지 않는다. 한 채널의 DB 실패가
  rollback-only로 다른 채널의 성공까지 취소하지 않도록 회귀 계약을 고정했다.
- UNOOSA·FAA·GovInfo의 인간 검수 사실 9건을 원문 저장 없이 참조형 원장으로
  적재하고 `GET /api/tracker/governance`로 읽기 전용 공개한다. 이 API는 P6 점수나
  ETA를 계산하지 않는다.

2026-07-16에 [ISRO Press](https://www.isro.gov.in/Press.html),
[CNSA 정책 인덱스](https://www.cnsa.gov.cn/english/n6465645/n6465648/index.html),
[CNSA 영문 홈](https://www.cnsa.gov.cn/english/)을 다시 확인했다. 현재 실제 링크는
각각 ISRO 루트 `*.html`, CNSA 정책·뉴스 `.../c숫자/content.html` 형태이며,
CNSA의 `MM/DD/YYYY`와 `YYYY-MM-DD` 혼용 및 잘못된 `chinadaily:` 링크가 테스트
fixture의 경계와 일치한다.

## 2. 검수 리소스

`tracker/governance-ledger-v1.json` 정적 감사 결과:

| 항목 | 결과 |
|---|---:|
| 레코드 | 9 |
| 고유 `recordId` | 9 |
| 유형 | `TREATY_STATUS`, `TREATY_ACTION`, `LICENSE_FRAMEWORK`, `REGULATORY_NOTICE` |
| 출처 | `UNOOSA`, `FAA`, `GOVINFO` |
| 잘못된 SHA-256 형식 | 0 |
| `PRIMARY` 이외 publication path | 0 |
| `HUMAN_REVIEWED` 이외 검수 상태 | 0 |

저장 대상은 URL·locator 성격의 메타데이터·확인일·해시·검수자 작성 사실 요약뿐이다.
원문, 인용문, HTML, PDF, 이미지, 바이너리와 node·score·readiness·ETA 키는 허용하지
않는다.

## 3. 비활성 수집 경계

- GitOps exact-host egress: `www.isro.gov.in:443`, `www.cnsa.gov.cn:443`
- 배포 기본값:
  - `TRACKER_OFFICIAL_INDEX_ENABLED=false`
  - `TRACKER_OFFICIAL_INDEX_CRON=0 23 4 * * WED`
  - `TRACKER_OFFICIAL_INDEX_MAX_LINKS=40`
- UNOOSA·FAA·GovInfo에는 신규 런타임 egress를 열지 않았다. 원장은 검수된 로컬
  리소스로만 가져온다.
- 신규 pod·Deployment·Kubernetes CronJob·secret은 없다. 기존 백엔드의 조건부
  `@Scheduled` 작업만 사용한다.
- 인덱스 수집을 켜더라도 격리 후보를 자동 게이트·분류·점수화하거나 Layer C
  사건으로 승격하지 않는다.

## 4. 실행된 검증

- V14 schema·parser·job·governance validator/repository/loader/controller 집중 계약과
  전체 Maven 회귀: **600 tests, failures 0, errors 0, skipped 0, BUILD SUCCESS**
- GitOps verifier: `OK: tracker egress policy and safe defaults verified`
- 프런트 전체 회귀: **Vitest 4.1.10, 17 files, 79/79 tests 통과**
- 잠금파일 정확 도구체인 Vite 8.1.4, plugin-react 6.0.3, TypeScript 7.0.2,
  Vitest 4.1.10 격리 설치에서 **17 files / 79/79**와 production build 통과
- `git diff --check`: 통과(CRLF 변환 경고만 존재)
- 구조 감사: 신규 workload 0, CronJob 0, secret 참조 0, scoring mutation 0,
  placeholder 0, 보호 픽스처 staged 0
- `kubectl kustomize gitops/apps/backend-springboot` 렌더 통과. ISRO·CNSA는
  exact-host TCP 443이고 wildcard tracker egress가 없다.
- Gitleaks 실행 파일은 로컬에 없어 tracked diff와 신규 파일의 private-key,
  AWS-key, 토큰 리터럴 대체 검사를 실행했고 0건을 확인했다.

## 5. 실제 Spring/API 검증

- Spring `test,demo,refbackfill` 서버를 8080에서 기동해 V14/V15를 포함한 15개
  migration을 실제 적용했다.
- 부트 로더가 `governance-ledger-v1` 9건을 가져왔고
  `GET /api/tracker/governance`는 HTTP 200, `CURRENT`, recordCount 9,
  UNOOSA·FAA·GovInfo 검수 행을 반환했다.
- 공개 응답에는 점수·node·readiness·ETA가 없고 원문 body·HTML·PDF·이미지·인용도
  없다. 사실 요약과 검수 provenance만 반환한다.
- 실제 Tracker 화면을 8080 API에 연결해 렌더했고 console error는 0건이었다.

## 6. 완료 판정과 활성화 경계

V14 migration, 격리 후보 경로, 참조 원장, 공개 API, GitOps 렌더가 모두 검증돼
WP3.5를 완료 처리한다. 완료는 live index polling 활성화를 뜻하지 않는다.
`TRACKER_OFFICIAL_INDEX_ENABLED=false`, `TRACKER_ENABLED=false`, LIVE_MODEL 비활성,
`evaluation_allowed=N` 경계를 유지한다.
