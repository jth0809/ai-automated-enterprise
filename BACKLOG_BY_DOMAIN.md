# 파트별 현황과 백로그 (Backlog by Domain)

혼재된 관심사를 6개 파트로 분리해, 각 파트의 **검증된 현재 상태 → 격차 → 할 일(우선순위)** 을 기록한다. 1인 운영이라도 "지금 어느 모자를 쓰고 판단하는가"를 구분하기 위한 문서다. 우선순위: P1(지금), P2(다음 분기), P3(Phase 3 크레딧 확보 이후 — [ARCHITECTURE_EVOLUTION.md](ARCHITECTURE_EVOLUTION.md) 연계).

기준 시점: 2026-07-08.

---

## 0. 알림/가시성 (Alerting & Notification) — 이 문서의 발단, 모든 파트의 공통 기반

### 원칙: "거의 모든 로그를 슬랙으로"는 안티패턴이다
전 로그를 알림 채널로 보내면 일주일 안에 채널을 뮤트하게 되고, 진짜 장애를 놓친다(alert fatigue). 세 계층으로 분리한다:

| 계층 | 성격 | 도구 | 목적지 |
|---|---|---|---|
| **알림(Alert)** | 사람의 행동이 필요한 이벤트만 | Flux notification-controller, Alertmanager, GitHub 알림 | Slack / 이메일 |
| **로그(Log)** | 전량 보관, 필요할 때 조회 | Loki (P3, 미구축) | Grafana 조회 |
| **메트릭(Metric)** | 추세·임계값 | Prometheus (가동 중) | Grafana + Alertmanager 룰 |

### 이벤트 소스는 3곳이고, GitHub Actions는 그중 1곳만 커버한다
- **CI/저장소 이벤트** (스캔 실패, 빌드 실패, Dependabot, ZAP 이슈) → GitHub 영역.
- **GitOps/배포 이벤트** (Kustomization/HelmRelease 실패, 카나리 롤백) → **Flux notification-controller — 이미 배포되어 있고 전용 NetworkPolicy(`allow-webhooks`)까지 준비된 상태에서 Provider/Alert CR만 미구성.** 새 시스템 도입 없이 활성화만 하면 된다.
- **런타임 이벤트** (파드 크래시루프, 노드 자원, 인증서 만료 임박) → Alertmanager — kube-prometheus-stack에 **의도적으로 비활성**(`enabled: false`) 상태. CPU 예산(2.5절 운영 참조) 안에서 최소 구성으로 활성화 가능.

### 할 일
- [ ] **P1 — GitHub↔Slack 공식 앱 연동** (코드 0줄): 워크플로 실패·이슈(ZAP 리포트 포함)·Dependabot을 채널 구독. `/github subscribe jth0809/ai-automated-enterprise workflows issues`
- [ ] **P1 — Flux 알림 활성화**: Slack Incoming Webhook을 OCI Vault → ExternalSecret으로 주입, `Provider`(slack) + `Alert`(모든 Kustomization/HelmRelease, severity: error) CR을 `gitops/infrastructure/` 신규 레이어로 추가. 배포 실패가 10분 주기 재시도 뒤에 조용히 묻히는 현재 상태를 끝낸다.
- [ ] **P2 — Flagger 카나리 알림**: `AlertProvider`(slack) CR + Canary `analysis.alerts` — 롤백 발생 시 즉시 통지(이번 주 "no values found" 롤백 2회는 수동 관찰로만 발견했다).
- [ ] **P2 — Alertmanager 활성화 + 최소 룰셋**: ① `KubePodCrashLooping` ② 노드 CPU requests > 95% ③ `certmanager_certificate_expiration_timestamp_seconds` < 21일(kro.kr/ZeroSSL 갱신 실패 조기 감지) ④ Flagger 카나리 Failed. 이메일 라우팅 포함.
- [ ] **P3 — Loki**: "모든 로그"의 올바른 종착지. 알림이 아니라 조회로 소비한다.

---

## 1. 보안 (Security)

### 현재 상태 (검증됨)
공급망: Trivy 게이트(HIGH/CRITICAL 차단) + Semgrep + Gitleaks(전 히스토리 clean) + CycloneDX SBOM + cosign 서명. 네트워크: CNP default-deny(ATP egress는 FQDN 한정), ambient mTLS. 엣지: ZeroSSL HTTPS 자동화, 301 리다이렉트, 보안 헤더 9종(ZAP FAIL 0/PASS 65). 주간 DAST.

### 격차와 할 일
- [ ] **P1 — 브랜치 보호**: `main` 직푸시가 현재 관행이다. Branch protection + required checks(보안 스캔 4종)를 걸면 게이트 우회가 구조적으로 불가능해진다. 단, CI의 GitOps 태그 자동 커밋(`[skip ci]`)이 푸시 가능하도록 봇 예외 또는 PAT 정리가 선행 조건.
- [ ] **P2 — 시크릿 로테이션 runbook**: OCIR 토큰, ZeroSSL EAB(클러스터 재구축 시 재발급 필요 — Git 밖 유일 자산), Slack webhook(신설 예정). 각각 만료·유출 시 절차를 README 운영 표에 문서화.
- [ ] **P2 — ZAP full scan 검토**: 현 baseline은 수동적 스캔. active scan은 프로덕션 대상 불가 → 카나리 가중치 0%의 canary 서비스를 대상으로 하거나 P3 스테이징에서.
- [ ] **P3 — Kyverno `verifyImages`**: 서명 검증 공백 폐쇄 + `cosign attest`로 SBOM 첨부 (Evolution §3.2).
- [ ] **P3 — 도메인 이전 검토**: kro.kr은 공유 도메인 — 발급 문제는 ZeroSSL로 해소했으나 도메인 자체의 신뢰도·통제권 한계는 남는다.

---

## 2. 개발 (Development)

### 현재 상태 (정직하게)
백엔드는 사실상 스켈레톤이다: 컨트롤러/테스트 각 1개(StatusController), Kafka/Redis 코드 부재, `application.yml`은 datasource뿐. 프런트도 Vite 기본 구조 + 테스트 부재(`npm test --if-present`가 조용히 통과). **파이프라인 성숙도(상)와 애플리케이션 성숙도(하)의 불균형이 이 프로젝트의 현재 모습이다.**

### 할 일
- [ ] **P1 — `/api` 경로 계약 정리**: nginx가 `/api` 프리픽스를 그대로 백엔드에 전달하는데 백엔드에 해당 컨텍스트가 없어 404가 난다(실측). `server.servlet.context-path=/api`로 맞추거나 nginx에서 프리픽스를 벗기는 것 중 하나로 확정하고 OpenAPI 스펙을 저장소에 커밋.
- [ ] **P1 — 테스트 실체화**: 백엔드 서비스 계층 단위 테스트 + Testcontainers 통합 테스트, 프런트 vitest 도입. CI가 이미 테스트를 실행하므로 작성 즉시 게이트가 된다(.github/AGENTS.md의 "robust test coverage" 원칙이 현재는 공약이다).
- [ ] **P2 — 실제 도메인 기능 구현**: 아키텍처가 지탱할 대상이 필요하다.
- [ ] **P3 — Kafka/Redis 코드 우선 원칙**: 인프라 재도입(Evolution §3.3)은 소비할 코드(리스너/프로듀서/캐시 계층)와 같은 PR로만.

---

## 3. 운영 (Operations / SRE)

### 현재 상태 (검증됨)
노드 2대 CPU requests 85~91% — **노드 1대 손실 시 전체 워크로드 재스케줄 불가 가능성이 높다(HA 아님).** 관측은 메트릭만(보존 1d/2GB), 로그 중앙화·알림 없음. ATP는 관리형(자동 백업은 OCI 제공). 이번 주 트러블슈팅 지식은 README 운영 표와 세션 기록에 문서화됨.

### 할 일
- [ ] **P1 — 알림 활성화** (0절 P1/P2와 동일 — 운영 파트의 최우선 결핍은 "장애를 사람이 모른다"이다).
- [ ] **P1 — 용량 상한 문서화**: 신규 워크로드 추가 시 CPU requests 예산표(노드별 가용 여유)를 README에 명시 — 이번 주 Pending 사태의 재발 방지는 스케줄링이 아니라 예산 관리다.
- [ ] **P2 — cert 갱신 감시**: ZeroSSL 자동 갱신(만료 2026-10-05)의 첫 사이클을 Alertmanager 룰로 감시. 첫 갱신 성공 확인 전까지는 미검증 경로다.
- [ ] **P2 — node drain 훈련**: 현 2노드에서 어디까지 견디는지 실측(아마 실패할 것이고, 그 실측치가 P3 증설의 근거 데이터가 된다).
- [ ] **P3 — HA**: 노드 증설 + PDB + topologySpreadConstraints (Evolution §3.1), Prometheus 보존 확대, Loki.

---

## 4. DevSecOps / 플랫폼 (Pipeline & Platform)

### 현재 상태 (검증됨)
푸시→빌드→게이트→SBOM→서명→GitOps 커밋→Flux→카나리→promotion 전 구간이 사람 개입 없이 동작함을 반복 실측. 보안 워크플로 5종 그린.

### 할 일
- [ ] **P1 — 파이프라인 알림 연결** (0절과 동일): 특히 주간 스케줄 실행(ZAP)은 실패해도 아무도 모른다.
- [ ] **P2 — 의존성 자동 갱신**: Renovate(또는 Dependabot updates)로 베이스 이미지·GitHub Actions·Helm 차트 버전 PR 자동화. Spring Boot 3.5.3→3.5.16 같은 수동 대응을 상시화하는 대신 게이트가 검증하는 자동 PR로.
- [ ] **P2 — GitOps 태그 커밋 경합 완화**: CI의 `git push` 재시도 루프가 이번 주 두 번 rebase 경합을 만들었다. 백엔드/프런트 동시 빌드 시 실패 여지 — 재시도 간격에 지터 추가 또는 큐잉 검토.
- [ ] **P3 — 스테이징 환경**: 현재는 카나리가 사실상 스테이징 역할. active DAST·부하 테스트를 위한 격리 환경은 크레딧 확보 후.

---

## 5. 테스트/신뢰성 엔지니어링 (Load · Failure · Chaos)

### 전제가 되는 실측 제약 — 계획은 여기서 출발한다
- **LB 대역폭 10 Mbps 고정**(gateway 어노테이션으로 free tier에 핀): 외부 경유 부하는 이 상한에 먼저 걸린다. 상한 이상의 앱 부하는 클러스터 내부에서 생성해야 한다.
- **백엔드 primary 1 레플리카**: 파드 킬 = 재기동까지 다운타임이 **설계상 정상**이다. 이를 "실패"로 오독하지 않도록 각 실험에 기대 결과를 명시한다.
- **ATP Always Free**: 동시 세션 제한(hikari pool max 5로 이미 방어) — DB 경유 부하는 처리량이 아니라 큐잉 지연으로 나타난다.
- **카오스 오퍼레이터(Chaos Mesh/Litmus)는 상주 비용(≥100m CPU)** 때문에 현 예산에서 배제 — P3 전까지는 kubectl 기반 수동 실험으로 충분하다.
- **알림(0절)이 켜지기 전의 카오스는 반쪽이다**: 실험의 절반은 "시스템이 견디는가", 나머지 절반은 "우리가 감지하는가"(MTTD)다.

### 5.1 부하 테스트 (Load / Performance)
도구: in-cluster는 이미 배포된 flagger-loadtester(hey), 본격 테스트는 **k6**(임계값 검증·리포트) — K8s Job(내부 경로) 또는 GitHub Actions(외부 경로, `workflow_dispatch`).

- [ ] **P1 — 베이스라인 측정**: 현재 상태의 p50/p99·최대 RPS를 경로별로 기록 — ① 외부→`https://ai-auto.kro.kr/`(정적, LB+TLS 포함) ② 외부→`/api` 프록시 경유 ③ 내부→backend apex 직행(게이트웨이 제외분 분리). **이 수치 없이는 P3 증설 규모 산정과 "성능 저하" 판단 모두 불가능하다.**
- [ ] **P2 — 스트레스 테스트**: RPS를 단계 상승시켜 포화점 실측 — 예상 병목 후보는 LB 10Mbps, JVM(메모리 limit 768Mi — OOM 여부가 관전 포인트), ATP 세션 순. 결과를 용량 예산표(운영 P1)에 기록.
- [ ] **P2 — 소크 테스트**: 저강도(카나리 수준) 부하 30~60분 유지 — 메모리 누수·커넥션 누수 검출. Grafana로 추세 관찰.
- [ ] **P3 — 카나리 분석 강화**: 베이스라인 확보 후 Canary `metrics`의 latency 임계값(현재 p99 500ms)을 실측 기반으로 조정하고, 부하 프로파일(`hey -q 5 -c 2`)을 실트래픽 모사 수준으로 상향.

### 5.2 장애 테스트 (Failure / Resilience Drills)
각 실험은 "가설 → 실행 → 기대 결과 → 실측"의 4열 표로 기록한다(별도 `docs/drills/` 축적 권장).

- [ ] **P1 — 불량 이미지 롤백 드릴**: 의도적으로 5xx를 반환하는 이미지를 배포해 Flagger가 **메트릭 기반 자동 롤백**하는지 E2E 검증. 메트릭 결함으로 인한 롤백은 2회 실측했지만, "나쁜 앱"을 정확히 걸러내는 정상 롤백은 아직 미검증이다. 위험도 낮음(카나리 트래픽 최대 50%, 자동 복귀).
- [ ] **P1 — GitOps 드리프트 복원**: Flux 관리 리소스(예: frontend Service)를 수동 삭제 → 다음 reconcile에서 자동 복원되는지 확인. 위험도 최저, GitOps 신뢰의 기본 증명.
- [ ] **P2 — DB 격리 드릴**: 백엔드 CNP의 ATP egress 규칙을 임시 제거(Git 커밋으로) → 앱이 설계대로 기동 유지(`initialization-fail-timeout: -1`, liveness 그룹 프로브) + `/api/status` DOWN 보고를 확인 → revert. 알림(0절) 가동 후에는 감지 시간도 함께 측정.
- [ ] **P2 — node drain 실측** (운영 P2와 동일 항목): 기대 결과는 "부분 실패"다 — 어떤 파드가 못 뜨는지가 P3 증설의 근거 데이터.
- [ ] **P2 — 메시 데이터플레인 재시작**: ztunnel/cilium-agent 파드 재시작 중 트래픽 지속성 확인(ambient 모드의 실질 무중단 여부 실측).
- [ ] **P3 — 전체 재구축 드릴(DR)**: terraform 재프로비저닝 + flux bootstrap + out-of-band 시크릿 재생성(ZeroSSL EAB 포함) → RTO 실측. 이 드릴 전까지 "GitOps라서 복구 가능"은 가설이다.

### 5.3 카오스 엔지니어링 (Chaos)
원칙: 정상상태 가설 정의 → 최소 폭발반경 → 중단 조건 명시. 스테이징이 없으므로 **P3 전에는 위 5.2의 저위험 드릴이 카오스의 역할을 대신한다.**

- [ ] **P2 — 월간 게임데이(수동 카오스)**: 알림 가동 후 시작. 회당 1개 실험(파드 킬, ATP 지연 주입 — `kubectl debug` + `tc netem`, coredns 1레플리카 축소 등)을 뽑아 MTTD/MTTR을 기록. 산출물은 실험 로그가 아니라 **탐지 공백 목록**(알림 룰 백로그로 환류).
- [ ] **P3 — Chaos Mesh 도입**: 노드 증설로 예산 확보 후. 선언적 실험(NetworkChaos/PodChaos/StressChaos)을 GitOps로 관리하고, 카나리 분석 중 카오스를 주입하는 "카오스+프로그레시브 딜리버리" 조합까지.
- [ ] **P3 — 존/노드 장애 자동화**: HA 구성(PDB/topologySpread) 완료 후에만 의미가 있다 — 현 2노드에서는 결과가 자명(부분 다운)해 실험 가치가 없다.

### 5.4 이미 다른 파트에 있는 테스트 (중복 방지 상호참조)
단위·통합 테스트 → 개발 P1 | DAST full scan → 보안 P2 | cert 첫 갱신 감시 → 운영 P2 | ATP 백업 복구 드릴 → 5.2 DR 드릴에 포함.

---

## 부록: 파트 간 의존 순서

```
0.알림(P1) ──> 3.운영 P2(cert 감시)  : 알림 채널 없이는 감시 룰이 무의미
0.알림(P1) ──> 5.테스트 P2(게임데이) : 감지(MTTD) 측정이 카오스의 절반
1.보안 P1(브랜치 보호) ──> 4.플랫폼 P2(Renovate) : 자동 PR은 required checks 전제
2.개발 P1(/api 계약) ──> 2.개발 P2(도메인 기능) ──> P3 Kafka 재도입
3.운영 P2(drain 실측) + 5.테스트 P1(부하 베이스라인) ──> P3 HA 증설 규모 산정
5.테스트 P1(롤백 드릴) ──> P3 카나리 분석 강화
```

첫 착수 권고: **0절 P1 두 항목**(GitHub-Slack 앱, Flux Provider/Alert). 둘 다 반나절 이내 작업이고, 이후 모든 파트의 실패가 보이기 시작한다. 알림과 독립적으로 바로 가능한 것은 **5절 P1 세 항목**(부하 베이스라인, 롤백 드릴, 드리프트 복원) — 모두 저위험이며 P3 계획의 근거 데이터를 만든다.
