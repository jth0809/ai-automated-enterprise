# WP0.4 — 인프라 사전 작업 (피드·CNP·시크릿·리소스 예산)

> 기준: 컨셉 v2.11 / 루트 AGENTS.md 전역 제약. **접지 결과 요약: 필요 인프라의 상당수가 이미 존재한다** — `ANTHROPIC_API_KEY`는 Vault+ESO 제공 중, `api.anthropic.com` egress는 CNP에 개방됨, ATP JDBC는 배선 완료. 신규 작업은 피드 도메인 egress와 시크릿 키 1개 추가로 한정된다.

## 1. 피드 목록 v0 (12개) + Tier 배정

아래 URL은 2026-07-12에 실제 GET으로 상태·Content-Type·최종 리다이렉트를 검증했다. JAXA와 Ars Technica의 폐기된 경로는 공식 피드 디렉터리에서 현재 경로로 교체했다.

| # | 소스 | Tier | type | 피드(초안) | egress 도메인 |
|---|------|------|------|-----------|---------------|
| 1 | NASA News Releases | 1 | AGENCY | `https://www.nasa.gov/news-release/feed/` | www.nasa.gov, science.nasa.gov |
| 2 | ESA Space News | 1 | AGENCY | esa.int RSS (Our_Activities) | www.esa.int |
| 3 | JAXA Press Releases | 1 | AGENCY | `https://global.jaxa.jp/rss/press.rdf` | global.jaxa.jp |
| 4 | arXiv (astro-ph.EP) | 2 | PREPRINT | `https://rss.arxiv.org/rss/astro-ph.EP` | rss.arxiv.org, arxiv.org, export.arxiv.org |
| 5 | SpaceNews | 2 | SPECIALIZED_MEDIA | spacenews.com/feed | spacenews.com |
| 6 | NASASpaceflight | 2 | SPECIALIZED_MEDIA | nasaspaceflight.com/feed | www.nasaspaceflight.com |
| 7 | Spaceflight Now | 2 | SPECIALIZED_MEDIA | spaceflightnow.com/feed | spaceflightnow.com |
| 8 | The Planetary Society | 2 | SPECIALIZED_MEDIA | planetary.org RSS | www.planetary.org |
| 9 | Phys.org – Space | 3 | GENERAL_MEDIA | phys.org/rss-feed/space-news/ | phys.org |
| 10 | Space.com | 3 | GENERAL_MEDIA | `https://www.space.com/feeds.xml` | www.space.com |
| 11 | Ars Technica – Science | 3 | GENERAL_MEDIA | `https://feeds.arstechnica.com/arstechnica/science` | feeds.arstechnica.com, arstechnica.com |
| 12 | Universe Today | 3 | GENERAL_MEDIA | `https://www.universetoday.com/rss.xml` | www.universetoday.com |

레지스트리 전용 항목(피드 없음, 주체 매핑·발표 경로 판정용): SpaceX(CORPORATE T3), Blue Origin(CORPORATE T3), Nature/Science(JOURNAL T1 — arXiv와 달리 동료 심사 성립).

**본문 추출 egress 정책:** 기사 본문은 **위 화이트리스트 도메인에서만** 추출한다. RSS가 외부 도메인으로 링크하면 본문 추출을 생략하고 RSS 요약만 사용 (`article.body_extracted='N'`) — egress 통제가 커버리지보다 우선한다는 명시적 트레이드오프.

**2026-07-12 검증 기록:** 12개 최종 feed가 모두 HTTP 200과 RSS/RDF XML Content-Type을 반환했다. NASA의 구 URL은 `www.nasa.gov/news-release/feed/`, Space.com은 `/feeds.xml`, Universe Today는 `/rss.xml`로 리다이렉트됐다. JAXA 구 `/press/rss/press_e.rdf`와 Ars 구 `/arstechnica/space`는 404였으며 각각 공식 `/rss/press.rdf`와 공식 Science section feed로 교체했다. 최근 기사 링크 표본의 본문 호스트도 위 egress 목록과 일치했다. arXiv는 주말에 항목 없는 유효 채널을 반환하므로 공식 `arxiv.org`/`export.arxiv.org` 본문 호스트를 정책에 함께 등록한다.

## 2. CNP egress 추가 (gitops/apps/backend-springboot/network-policy.yaml)

기존 패턴(news.google.com 규칙)을 그대로 확장. 추가 블록 초안:

> **2026-07-13 적용 기록 (Phase 1b Task 7):** 아래 초안이 `network-policy.yaml`에
> 반영됐다. 단, 실제 적용 집합은 V4 시드의 active `source_domain` 전체와 일치해야
> 하므로 NASA 본문 호스트 `science.nasa.gov`를 포함한 **16개 호스트**다(초안 15개
> + science.nasa.gov). 집합 동등성·PR-1 안전 기본값·시크릿 부재는
> `gitops/apps/backend-springboot/tests/tracker-egress-policy.ps1`이 검증하며,
> 호스트 변경 시 시드·CNP·검증 스크립트 세 곳을 함께 갱신한다. deployment에는
> 비밀 아닌 수집 한도(`TRACKER_EXTRACT_CRON`, `TRACKER_EXTRACT_BATCH_SIZE`)와
> `TRACKER_FLUKE_ENABLED=false`가 추가됐다.

```yaml
    # Tracker RSS feeds + article body extraction (WP0.4 whitelist).
    # Every TRACKER_FEEDS host MUST appear here or fetches are dropped.
    - toFQDNs:
        - matchName: www.nasa.gov
        - matchName: www.esa.int
        - matchName: global.jaxa.jp
        - matchName: rss.arxiv.org
        - matchName: arxiv.org
        - matchName: export.arxiv.org
        - matchName: spacenews.com
        - matchName: www.nasaspaceflight.com
        - matchName: spaceflightnow.com
        - matchName: www.planetary.org
        - matchName: phys.org
        - matchName: www.space.com
        - matchName: feeds.arstechnica.com
        - matchName: arstechnica.com
        - matchName: www.universetoday.com
      toPorts:
        - ports:
            - port: "443"
              protocol: TCP
```

- `api.anthropic.com`(기존), ATP 엔드포인트(기존)는 변경 불요.
- P3에서 추가 예정: `ll.thespacedevs.com`(Launch Library 2), Metaculus API 도메인 — 해당 페이즈 PR에서 추가.
- 리다이렉트 주의: 일부 피드는 www↔apex 도메인 리다이렉트가 있으므로 WP1.2 검증 시 최종 호스트 기준으로 목록 확정.

## 3. 시크릿·설정 (기존 ESO 패턴 준수)

| 항목 | 상태 | 조치 |
|------|------|------|
| `ANTHROPIC_API_KEY` | ✅ Vault + ESO 제공 중 (externalsecret.yaml) | 없음 — tracker가 동일 env 소비 |
| `TRACKER_FEEDS` | 신규 | Vault 키 생성 + externalsecret.yaml에 항목 추가 (NEWS_FEEDS 선례 준수 — 재배포 없이 피드 변경 가능. ESO는 원자적 동기화이므로 **Vault에 키를 먼저 생성한 뒤** 매니페스트 커밋) |
| `SPRING_DATASOURCE_*` (ATP) | ✅ 배선 완료 | 없음 |
| `tracker.enabled`, 모델 ID 2종 | 신규(비밀 아님) | deployment.yaml env 평문 (`TRACKER_ENABLED=false`로 최초 배포 → 스키마·기동 검증 후 true) |

## 4. 스케줄·리소스 예산 (free tier CPU 포화 이력 대응)

**핵심 결정: 신규 파드 0개.** 모든 잡은 기존 backend Deployment의 in-process `@Scheduled` (WP0.2) — CPU request 증가분 없음. 그 대가로 지켜야 할 것:

- **틱당 처리 상한:** tracker-process는 틱(15분)당 기사 ≤30건 — LLM 호출이 I/O 바운드라 CPU 영향 미미, 메모리 스파이크 방지.
- **시간대 분산:** news 잡(매시 :00)과 tracker-ingest(:10), 야간 배치들(01:10~02:00 UTC 분산) — WP0.2 표 참조.
- **JVM 메모리:** CLOB 본문은 스트리밍 처리하지 않고 기사당 ≤512KB로 절단 저장(추출기 상한). 힙 상승 관측 시 배치 크기 우선 축소.
- **ShedLock:** 캐너리 이중 기동 시 잡 중복 실행 방지 (LLM 비용 이중 지출 차단).

## 5. LLM 비용 예산 (요율은 구현 시점 공식 단가로 산정 — 토큰 기준 견적)

| 단계 | 볼륨/일 | 호출당 토큰 (in / out) | 비고 |
|------|---------|------------------------|------|
| 게이트 (Haiku급) | ~2,000 | ~600 / 1 | 제목+발췌만 |
| 심층 분류 (Opus급) | 100~200 | ~5,000 (시스템 ~3,500은 캐시 히트) / ~400 | 캐싱으로 비캐시분 ~1,500 |
| 플루크 필터 | 0~5 | 분류와 동일 | 희소 |

- 가드: `parameter_set.daily_cost_cap_usd = 20` (기본). `llm_usage` 당일 합산 초과 시 처리 중지 + 경보. 한도의 실측 조정은 P1 운영 첫 주에 수행.
- 백필(P2)은 Batch API(50% 할인) + 별도 일회성 예산으로 분리 — 일일 캡과 무관.

## 6. ATP 스토리지 예산

- 원문 CLOB 평균 20KB × 게이트 통과분만 전문 보존(연 ~50K건) ≈ ~1GB/년 + 인덱스 — Always Free 20GB 한도 내. 탈락 기사(GATE_REJECTED)는 본문을 90일 후 NULL 처리하는 정리 잡(P2)으로 상한 관리.

## 7. 수집 채널 확장 로드맵 (G0 피드백 반영 — 커버리지 갭 대응)

RSS 12개는 Phase 1의 파이프라인 검증용 최소 구성이다. 실재하는 커버리지 갭(비영어권 프로그램, 뉴스가 아닌 사건 원장)은 **피드 증설이 아니라 센서 종류 확장**으로 해소한다 (13.1 "구조화 소스 우선" 원칙의 연장):

| 채널 | 커버 갭 | Tier/유형 | Phase |
|------|---------|-----------|-------|
| Launch Library 2 API → **Layer C 사건 소스로 승격** | 뉴스 커버리지와 무관한 전 세계 발사·시험 전수 | Tier 1급 구조화 원장 | P3 (WP3.1 확장) |
| UNOOSA 조약 상태 DB, 관보(FAA 발사 면허 등) | 필라 6 거버넌스·면허 사건의 원본 | Tier 1 구조화 | P3 (WP3.5) |
| 비영어권 보강: ISRO 보도자료, 중국 우주 프로그램 영문 전문 소스 (피드 3~5개) | 비서방 프로그램 | Tier 1~2 | P3 (WP3.5) |
| NASA TechPort API (기관 자체 TRL 평가 데이터) | 저TRL 프로젝트 + Layer C 보정 앵커 | 보조 데이터 | P4 |
| 기업 직발표 (SpaceX 등 — RSS 없음) | 전문지(Tier 2)가 수 시간 내 전량 중계하므로 프록시 커버리지로 충분 | **의도적 제외** (사이트별 파서 금지 원칙) | — |

구조화 채널의 사건은 `publication_path=PRIMARY`, 해당 원장을 `source_registry`에 Tier 1 항목으로 등록해 기존 검증 도출 규칙을 그대로 통과시킨다 — 파이프라인 수정 불요.

## 8. 배포 순서 (P1 WP1.9에서 실행할 GitOps 커밋 순서)

1. Vault에 `TRACKER_FEEDS` 키 생성 (수동, Git 외부)
2. PR-1: CNP egress 블록 + externalsecret 항목 + deployment env(`TRACKER_ENABLED=false`)
3. 이미지 배포 (앱 코드 — Flyway 마이그레이션은 기동 시 자동 적용)
4. PR-2: `TRACKER_ENABLED=true` 전환 → Flux reconcile로 가동
