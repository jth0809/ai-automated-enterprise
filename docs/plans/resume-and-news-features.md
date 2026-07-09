# 애플리케이션 기능 계획: 인증형 이력서 + AI 뉴스 다이제스트

기존 계획과의 관계:
- **Phase 5(애플리케이션 레이어)의 첫 실제 도메인 기능** — 지금까지 백엔드는 컨트롤러 1개의 스켈레톤이었다([BACKLOG_BY_DOMAIN.md](../../BACKLOG_BY_DOMAIN.md) 개발 파트의 "파이프라인 상 / 앱 하" 불균형을 해소).
- **뉴스의 "속보 알림"은 Phase 3 Kafka/Redis 재도입의 구체적 소비자**다. [ARCHITECTURE_EVOLUTION.md](../../ARCHITECTURE_EVOLUTION.md) §3.3의 "코드 없이 인프라만 먼저 올리지 말 것" 원칙을 지키는 실제 이벤트 워크로드가 된다.
- 모든 신규 코드는 기존 게이트(Trivy·Semgrep·SBOM·cosign)와 카나리 배포를 그대로 통과한다.

기준: 2026-07-08. 기본 AI 모델은 `claude-opus-4-8`(Anthropic Java SDK). 문서 내 **결정 포인트**는 사용자가 veto/변경할 수 있는 기본값이다.

---

## 기능 1: 인증형 이력서 (Gated Résumé)

**목표**: 이력서 페이지를 공개 URL로 노출하되, 인증 코드를 입력해야만 내용을 볼 수 있게 한다(리크루터에게 링크+코드 전달).

### 결정 포인트 — 인증 모델
- **[권장] 서버 발급 액세스 코드**: 관리자가 코드를 발급(리크루터별 1개, 만료·조회수 제한 가능) → 방문자가 코드 입력 → 단기 세션(JWT/쿠키) 발급 → 이력서 API 접근. 누가 언제 열람했는지 추적 가능, 코드별 폐기 가능.
- (대안) 단일 공유 패스프레이즈: 가장 단순하나 추적·폐기 불가. 링크가 새면 전체가 샌다.

### 설계 (권장안 기준)
- **데이터 모델**(ATP): `access_code`(code_hash, label, expires_at, max_views, view_count, revoked), `resume_view_log`(code_id, viewed_at, ip_hash) — 감사 추적.
- **API**(Spring Boot, `/api` 프리픽스):
  - `POST /api/resume/redeem` {code} → 검증(해시 비교, 만료/횟수/폐기 체크) → 단기 JWT(15분) 반환. **코드는 해시로만 저장**, 평문 비교 금지.
  - `GET /api/resume` (JWT 필요) → 이력서 JSON. `/actuator`처럼 인증 없는 접근은 거부.
  - `POST /api/admin/codes` (관리자 인증) → 코드 발급.
- **프런트엔드**(React): 코드 입력 화면 → redeem → 이력서 렌더. 이력서 콘텐츠는 프런트 번들이 아니라 **API에서만** 오게 해서 번들 정적 분석으로 유출되지 않게 한다.
- **보안(Zero-Trust 연장)**:
  - redeem 엔드포인트에 **rate limit + 상수시간 비교**(코드 열거·타이밍 공격 방지). Redis가 있으면 IP·코드별 시도 카운팅(속보 기능과 Redis 공유).
  - 에러 응답은 "유효하지 않음" 하나로 통일(존재/만료/폐기 구분 노출 금지).
  - 신규 엔드포인트는 주간 ZAP DAST 범위에 자동 포함.

### 단계
- **P1**: 단일 관리자 발급 + 코드 검증 + 이력서 렌더(세션 15분). ATP만 사용.
- **P2**: 코드별 만료·조회수 제한 + 열람 로그 대시보드.

---

## 기능 2: AI 뉴스 다이제스트 + 속보 알림 + Q&A

**목표**: 관심 토픽의 뉴스를 매일 수집·AI 요약해 피드로 제공하고, 속보는 실시간 알림하며, 사용자가 기사에 대해 질문(Q&A)할 수 있게 한다.

### 결정 포인트 — 뉴스 소스
- **[권장] RSS 피드**: 무료·합법·안정적. 토픽별 RSS(구글 뉴스 RSS, 언론사 피드)를 설정으로 관리. 웹 스크래핑(법적·안정성 리스크)과 유료 News API를 피한다.
- 파서는 소스 플러그블하게 설계(나중에 API 소스 추가 가능).

### AI 연동 (Anthropic Java SDK — 백엔드가 Spring Boot/Java)
의존성 `com.anthropic:anthropic-java`. 클라이언트는 `AnthropicOkHttpClient.fromEnv()`(`ANTHROPIC_API_KEY` 주입). 모델 `claude-opus-4-8`, 적응형 사고(`ThinkingConfigAdaptive`), effort는 요약=`low`/`medium`, Q&A=`high`.

- **일일 요약 → Batch API**: 지연 민감하지 않은 배치 작업이므로 Message Batches API로 하루치 기사를 일괄 요약하면 **토큰 비용 50% 절감**. 매일 정해진 시각 배치 제출→폴링→저장.
- **Q&A → 스트리밍**: 대화형이므로 `client.messages().createStreaming(...)`으로 토큰 스트리밍(타임아웃 방지·UX). 기사 본문을 컨텍스트로 주고 질문에 답.
- **프롬프트 캐싱**: 시스템 프롬프트 + 공통 기사 코퍼스에 `cache_control`을 걸어 반복 호출 비용 절감(캐시 프리픽스는 안정, 질문만 뒤에).
- **비용 관리**: Opus 4.8 = 입력 $5 / 출력 $25 per 1M. 배치 50% 할인 + 캐싱으로 일일 요약 비용을 억제. Free Tier 하드웨어와 무관(호출은 백엔드→Anthropic API).

### 인프라 델타 (구체적 — 이 계획의 핵심 산출물)
1. **백엔드 CNP egress 추가 필수**: 현재 backend CiliumNetworkPolicy는 DNS·kafka·redis·ATP(FQDN)만 허용. **`api.anthropic.com:443`으로의 egress를 `toFQDNs`로 추가**하지 않으면 AI 호출이 default-deny에 드롭된다. (ATP FQDN 규칙과 동일 패턴.)
2. **`ANTHROPIC_API_KEY`**: OCI Vault → ExternalSecret → 백엔드 env. [secret-rotation.md](../runbooks/secret-rotation.md) #7에 이미 인벤토리됨.
3. **CSP는 수정 불요**: 프런트는 자기 백엔드(`/api`)만 호출하고 AI 호출은 백엔드에서 나가므로 `connect-src 'self' https://api.ai-auto.kro.kr`로 충분.
4. **Redis / Kafka 재도입**(P3에서, ARCHITECTURE_EVOLUTION §3.3 구조로): 오퍼레이터 Kustomization과 인스턴스 CR Kustomization을 **분리 + dependsOn 체이닝**해서만 재도입(Phase 1의 CRD/CR 동일-Kustomization 교착 재발 방지). 노드 증설(§3.1) 후에.

### 아키텍처
```
RSS 소스 ──(스케줄러)──> 수집·정규화 ──> ATP(기사 저장) ──> Batch 요약 ──> 피드 API ──> React 피드
                                          │                                    └─> Q&A(스트리밍) ──> React
                                          └─(속보 판정)─> Kafka 토픽 ──> 알림 컨슈머 ──> 이메일/인앱
                              Redis: 기사 dedup·rate limit·요약 캐시
```

### 결정 포인트 — 알림 전달
- Slack은 유료라 보류(BACKLOG 0절 결정). **[권장] 인앱 피드 + 이메일 다이제스트**. 이메일은 SMTP 자격증명 필요([secret-rotation.md](../runbooks/secret-rotation.md) #8). 웹 푸시는 P3 선택.

### 단계
- **P1 (Kafka 불요)**: RSS 수집 스케줄러 + ATP 저장 + **Batch API 일일 요약** + 인앱 피드. 이것만으로 "매일 관심 뉴스 요약"이 동작.
- **P2**: 기사 Q&A 스트리밍. 백엔드 CNP egress(api.anthropic.com) 추가. 프롬프트 캐싱.
- **P3 (Phase 3 연계)**: 속보 실시간 판정 → **Kafka 이벤트** → 알림 컨슈머 → 이메일/푸시. Redis 캐시·dedup. **이 단계가 Kafka/Redis 재도입의 정당한 소비 코드**다 — 인프라만 먼저 올리지 않고 이 컨슈머와 같은 PR로 배포.

---

## 교차 관심사

- **보안 게이트**: 신규 엔드포인트·의존성은 Trivy·Semgrep·SBOM·cosign을 그대로 통과. Dependabot이 spring-kafka·anthropic-java 등 새 의존성의 갱신 PR을 자동 생성.
- **테스트**(BACKLOG 개발 P1 실현): redeem 코드 검증 단위 테스트, RSS 파서 테스트, AI 클라이언트는 계약 테스트(모킹)로. CI가 이미 테스트를 실행하므로 작성 즉시 게이트가 된다.
- **배포**: 기존 CI→OCIR→cosign→GitOps→Flagger 카나리 경로 그대로. AI 호출 실패는 앱 기동을 막지 않게(circuit breaker + graceful degrade) 설계 — ATP 패턴과 동일 철학.
- **관측성**: AI 호출 지연·토큰·비용을 Prometheus 메트릭으로 노출(카나리 분석에도 활용 가능).

## 착수 권고 순서
1. 기능1 P1(인증형 이력서) — ATP만 쓰고 인프라 델타 없음, 가장 빠른 실배포 도메인 기능.
2. 기능2 P1(RSS + Batch 일일 요약 + 인앱 피드) — Kafka 불요, `ANTHROPIC_API_KEY`(Vault) + 백엔드 egress(P2에서) 준비.
3. 이후 P2/P3는 노드 증설·Kafka 재도입과 함께.
