# Tracker WP3.5 수집 채널 확장 설계

> 마스터 계획: [multiplanetary-tracker-execution-plan.md](../../plans/multiplanetary-tracker-execution-plan.md)
>
> Phase 3 순서: WP3.1 → WP3.3 → WP3.2 → **WP3.5** → WP3.4 → G3

**상태:** Phase 3 확정 후보군과 사용자 전체 구현 승인에 따라 확정(2026-07-15).

## 1. 목표

미국·유럽 중심 RSS 편향을 줄이기 위해 ISRO와 CNSA 영문 공식 채널을
추가하고, JAXA 영문 RSS의 기존 경로를 재확인한다. 동시에 필라 6의 조약·
면허·규제 근거를 인간 검수 구조화 원장으로 적재한다.

WP3.5는 **후보 발견과 근거 원장**을 확장한다. LIVE_MODEL이 비활성인 동안
신규 HTML 후보를 자동 게이트·분류·점수화하거나 Layer C 사건으로 승격하지
않는다.

## 2. 접근 비교와 채택안

1. 외부 RSS 변환 프록시는 사이트별 파서를 줄이지만 새 서비스, 새 신뢰 경계,
   비용과 추가 egress가 필요해 제외한다.
2. 전부 수동 스냅샷은 안전하지만 신규 보도자료 발견 지연이 커서 채널 보강
   목적을 충족하지 못한다.
3. **하이브리드 방식을 채택한다.** RSS가 있는 JAXA는 기존 수집기를 재사용하고,
   RSS가 없는 ISRO·CNSA만 제한된 HTML 인덱스로 수집한다. UNOOSA·FAA·GovInfo는
   페이지 구조를 운영 파서에 결합하지 않고 검수 원장으로 적재한다.

## 3. 채널과 신뢰 분리

### 3.1 JAXA

- 기존 소스 코드: `JAXA`
- 기존 RSS: `https://global.jaxa.jp/rss/press.rdf`
- 기존 분류: `AGENCY`, Tier 1
- 변경: 새 파서 없이 기존 RSS 경로와 CNP를 회귀 검증한다.

### 3.2 ISRO

- 소스 코드: `ISRO`
- 인덱스: `https://www.isro.gov.in/Press.html`
- 링크 허용: 같은 호스트의 루트 `.html` 문서만 허용하고 인덱스 자신과
  탐색·연도 링크는 제외한다.
- 분류: `AGENCY`, Tier 1

ISRO Press에 다른 인도 정부기관 발표가 포함될 수 있으나 공식 정부 발표를
ISRO가 재게시한 경우다. 향후 분류 단계에서 `publication_path`를 별도로
판정하며, WP3.5 수집 단계는 공식성 수준을 확정하지 않는다.

### 3.3 CNSA

CNSA 영문 홈페이지에는 기관 직접 발표와 Xinhua·China Daily 재전재가 섞여
있다. 호스트만 보고 전부 Tier 1로 취급하면 검증 수준이 과대평가되므로 두
채널로 분리한다.

- `CNSA`: `https://www.cnsa.gov.cn/english/n6465645/n6465648/index.html`
  정책·공고 인덱스, `AGENCY`, Tier 1
- `CNSA_HOSTED_MEDIA`: `https://www.cnsa.gov.cn/english/` 영문 뉴스 인덱스,
  `GENERAL_MEDIA`, Tier 3
- 호스트: `www.cnsa.gov.cn`
- 허용 링크: `/english/` 아래의 다단계 `.../content.html`만 허용
- 외부 도메인, IP literal, 잘못 합성된 `chinadaily:` 경로, PDF 링크는 제외

이 분리는 후보를 버리지 않으면서도 재전재가 `OFFICIAL`로 자동 승격되는 것을
막는다. 정확한 게시 경로 판정은 LIVE_MODEL 활성화 이후 인간 검수와 함께한다.

## 4. 제한 HTML 인덱스 수집기

구성요소:

- `OfficialIndexChannel`: 소스 코드, 인덱스 URI, 채널 종류, 허용 링크 규칙
- `OfficialIndexParser`: Jsoup 기반 순수 HTML→후보 파서
- `OfficialIndexJob`: allowlist fetch, 파싱, 멱등 article 삽입

경계:

- 요청은 HTTPS, TCP 443, 정확한 채널 호스트만 허용한다.
- 리다이렉트의 모든 hop도 같은 allowlist를 통과해야 한다.
- 기존 `ArticlePageFetcher`의 3회 리다이렉트·2 MiB·HTML 전용 제한을 재사용한다.
- 채널당 한 번에 최대 40개 링크만 처리한다.
- 링크는 같은 호스트의 허용 path 정규식과 일치해야 한다.
- URL fragment를 제거하고 중복 URL은 한 번만 반환한다.
- 제목은 공백 정규화 후 3–1000자다.
- 게시일을 확실히 파싱할 수 없으면 null을 허용한다. 추측한 날짜를 만들지 않는다.
- 한 채널 실패가 다른 채널을 중단시키지 않는다.

스케줄은 수요일 04:23 UTC 주 1회다. 신규 pod나 Kubernetes CronJob을 만들지
않고 backend의 조건부 `@Scheduled`와 ShedLock을 사용한다.

## 5. LIVE_MODEL 전 격리

V14는 `article.evaluation_allowed CHAR(1)`을 추가한다.

- 기존 RSS article: 기본 `Y`, 현재 동작 유지
- WP3.5 HTML 후보: 항상 `N`
- `TrackerRepository.findByStatus("INGESTED", ...)`는 `Y`만 반환
- HTML 후보는 body extraction도 `SKIPPED`로 저장

따라서 `TRACKER_OFFICIAL_INDEX_ENABLED=true`가 되더라도 HTML 후보는 자동으로
게이트·분류·점수화되지 않는다. 이후 LIVE_MODEL 활성화 작업은 별도 인간 승인
API나 검수 배치를 통해 `evaluation_allowed=Y`와 본문 추출 상태를 함께 전환해야
하며 WP3.5 범위에는 포함하지 않는다.

## 6. 필라 6 구조화 원장

파일: `tracker/governance-ledger-v1.json`

최초 원장은 다음 종류를 포함한다.

- UNOOSA 최신 국제 우주협정 상태 문서 스냅샷
- 2025년 확인된 Outer Space Treaty·Registration Convention 가입 기록
- FAA 상업 우주 운송 면허·허가 체계와 현재 활성 데이터 경로
- GovInfo의 Part 450 통합 발사·재진입 면허 규칙과 최근 공식 공고

각 기록 필드:

```json
{
  "recordId": "UNOOSA-STATUS-2026",
  "recordType": "TREATY_STATUS",
  "jurisdiction": "INTERNATIONAL",
  "subject": "Status of international space agreements",
  "status": "PUBLISHED",
  "effectiveOn": "2026-01-01",
  "effectiveOnPrecision": "DAY",
  "sourceCode": "UNOOSA",
  "sourceUrl": "https://www.unoosa.org/oosa/en/ourwork/spacelaw/treaties/status/index.html",
  "accessedOn": "2026-07-15",
  "contentSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "publicationPath": "PRIMARY",
  "factSummary": "Reviewer-authored factual summary.",
  "reviewStatus": "HUMAN_REVIEWED"
}
```

저장소에는 원문·인용·HTML·PDF·이미지·바이너리를 저장하지 않는다.
`contentSha256`는 검수한 출처 표현의 지문이며 원문 대체물이 아니다.

## 7. 원장 검증과 멱등성

`GovernanceLedgerValidator` 규칙:

- 최상위는 JSON 배열이며 6–100개 기록을 허용한다. 최초 건수에 고정하지 않는다.
- 알 수 없는 키와 중복 `recordId`를 거부한다.
- `recordType`은 `TREATY_STATUS`, `TREATY_ACTION`, `LICENSE_FRAMEWORK`,
  `REGULATORY_NOTICE`만 허용한다.
- `effectiveOnPrecision`은 `DAY`, `MONTH`, `YEAR`만 허용한다.
- `sourceCode`는 `UNOOSA`, `FAA`, `GOVINFO`만 허용한다.
- 출처 URL은 해당 소스의 정확한 공식 호스트, HTTPS, 표준 포트, 사용자정보·
  fragment 없음 조건을 만족해야 한다.
- `contentSha256`는 64자리 소문자 hex다.
- `publicationPath=PRIMARY`, `reviewStatus=HUMAN_REVIEWED`만 허용한다.
- 요약은 40–1000자이며 공백만 있는 값은 거부한다.
- `nodeCode`, `claimedLevel`, `score`, `readiness`, `eta`, `quote`, `body`,
  `html`, `pdf`, `image` 등 자동 점수·원문 저장 키를 모든 깊이에서 거부한다.

`governance_import`는 데이터셋 버전, SHA-256, 기록 수, 가져온 시각을 보존한다.

- 같은 버전·같은 해시: no-op
- 같은 버전·다른 해시: 실패
- 새 버전: `record_id` 기준 upsert 후 새 import 감사행 기록

원장은 node, event, node_state_history, pillar_snapshot을 변경하지 않는다.

## 8. API와 관측성

`GET /api/tracker/governance`는 최신 검수 원장을 최대 50개 반환한다.

```json
{
  "status": "CURRENT",
  "datasetVersion": "governance-ledger-v1",
  "recordCount": 8,
  "latestEffectiveOn": "2026-01-01",
  "records": []
}
```

- 0개: `INSUFFICIENT_DATA`
- 최신 import가 400일 이내: `CURRENT`
- 더 오래됨: `STALE`

응답에는 준비도, TRL/EGL, ETA, 자동 영향 필드가 없다. 운영자는 article의
`evaluation_allowed=N` 개수와 governance import 감사행으로 수집 상태를 확인한다.

## 9. 데이터베이스와 소스 등록

V14 변경:

- `article.evaluation_allowed`
- `governance_record`
- `governance_import`
- `ISRO`, `CNSA`, `CNSA_HOSTED_MEDIA` source_registry 등록
- ISRO·CNSA exact-host source_domain 등록

기존 `JAXA`, `UNOOSA`, `FAA`, `GOVINFO` 행은 재사용한다. V7의 역사 전용
`UNOOSA`·`FAA`·`GOVINFO`는 runtime feed로 활성화하지 않는다.

## 10. GitOps와 보안

- CNP 신규 egress: `www.isro.gov.in:443`, `www.cnsa.gov.cn:443`
- JAXA `global.jaxa.jp:443`는 기존 규칙 유지
- UNOOSA·FAA·GovInfo는 검수 로컬 원장이므로 신규 runtime egress를 열지 않는다.
- `TRACKER_OFFICIAL_INDEX_ENABLED=false`
- `TRACKER_OFFICIAL_INDEX_CRON="0 23 4 * * WED"`
- `TRACKER_OFFICIAL_INDEX_MAX_LINKS="40"`
- 신규 secret 없음. `TRACKER_FEEDS` 값은 저장소나 매니페스트에 쓰지 않는다.
- 신규 pod·CronJob 없음. 운영 변경은 GitOps 커밋으로만 적용한다.

## 11. 테스트와 완료 조건

1. V14가 H2 Oracle mode에서 적용되고 신규 소스 신뢰 등급과 격리 열이 정확하다.
2. 기존 article은 평가 가능하고 신규 HTML 후보는 gate 조회에서 제외된다.
3. 파서는 ISRO·CNSA 정상 링크·날짜를 수용하고 외부 호스트·IP·PDF·잘못된
   경로·41번째 이후 링크를 거부한다.
4. 수집기는 채널 실패 격리, 멱등 URL 삽입, `evaluation_allowed=N`을 보장한다.
5. 원장 검증기는 유연한 건수, 해시, host, 금지 키, enum을 검사한다.
6. 로더는 same-hash no-op, hash collision 실패, 새 버전 upsert를 보장한다.
7. API는 CURRENT·STALE·INSUFFICIENT_DATA와 최대 50행을 검증한다.
8. GitOps 검증기는 두 exact-host 443, 기본 비활성, 최대 40을 검증하고
   HTTP·secret·신규 workload를 허용하지 않는다.
9. 전체 backend 회귀, frontend 회귀, production build, GitOps render가 통과한다.
10. LIVE_MODEL, tracker 본체, LL2, 수송 경제성, WP3.5 live collector는 모두
    비활성 상태로 남는다.
