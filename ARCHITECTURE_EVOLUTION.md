# AI Automated Enterprise: Architecture Evolution Record & Scaling Roadmap

본 문서는 두 부분으로 구성된다. **Phase 1–2는 기록(record)** 으로, 실제 발생한 장애·근본 원인·수정을 커밋 해시와 실측 수치로 뒷받침한다. **Phase 3은 계획(plan)** 으로, 아직 검증되지 않은 미래 작업이며 각 단계에 전제 조건과 완료 기준을 명시한다.

기준 시점: 2026-07-08. 상세 설계는 [ARCHITECTURE_PLAN.md](ARCHITECTURE_PLAN.md), 현재 사용법은 [README.md](README.md) 참조.

---

## Phase 1: 초기 설계와 실제 구현의 괴리

**목표:** Zero-Trust, GitOps, DevSecOps가 내재화된 클라우드 네이티브 아키텍처 (OCI Always Free: A1 노드 2대, 노드당 할당 가능 CPU 840m).

설계대로 구현된 것: Terraform/Ansible IaC, FluxCD GitOps(의존성 체인 직렬화), Cilium CNI + Istio Ambient mTLS, Gateway API 인그레스, OCI Vault + External Secrets, Flagger 카나리, Prometheus/Grafana, CI 이미지 빌드·서명·GitOps 태그 자동 갱신.

설계와 달랐던 것(정직한 기록):

- **Kafka/Redis는 매니페스트만 존재했고 코드에는 구현된 적이 없다.** 백엔드 `application.yml`에는 Kafka/Redis 설정 자체가 없으며, "비동기 이벤트 기반 MSA"는 설계 문서상의 목표였지 도달한 상태가 아니다.
- 배치 구조 자체에 결함이 있었다(아래 사건 1). Kafka/Redis가 "자원 때문에 제거"되기 이전에, **애초에 한 번도 정상 배포된 적이 없다.** OOM은 발생한 장애가 아니라 배포를 보류한 근거였던 예측 리스크다.
- Loki, A/B 테스트, cosign 서명의 클러스터 측 검증은 미구현으로 남아 있다.

---

## Phase 2: 무료 티어 운영 기록 (2026-07-05 ~ 07-08, 실측 기반)

### 2.1 장애와 근본 원인 — 사건 기록

| # | 증상 (실측) | 근본 원인 (증거) | 해결 (커밋) |
|---|---|---|---|
| 1 | `db-kafka`/`db-redis` Kustomization 44시간 `False` → `dependsOn`으로 백엔드 배포 전체 차단 → 프런트 nginx가 upstream DNS 실패로 크래시 | 오퍼레이터 HelmRelease(CRD 설치자)와 그 CRD를 쓰는 CR을 **같은 Kustomization에 배치**. kustomize-controller는 전체 리소스를 서버사이드 dry-run으로 검증한 뒤에야 적용하므로, CRD 부재 → CR 검증 실패 → 오퍼레이터도 영원히 미적용되는 교착 | Kafka/Redis 배포 참조 제거, `6faacbc` (PR #1). 매니페스트는 `gitops/databases/`에 보존 |
| 2 | 백엔드 파드 10시간+ Pending (`0/2 nodes: Insufficient cpu`), OKE 데몬셋 파드도 수일 Pending | CPU **requests** 포화: 노드별 840m 중 840m(100%)/770m(91%) 예약(실사용률 아님). 백엔드 150m이 어디에도 미적재 | flannel-ds 제거(저장소 문서화 절차), 백엔드 150m→25m(`fe18e8e`; 카나리 분석 중 primary+canary 동시 기동 전제), notification-controller 100m→25m·prometheus 100m→50m(PR #2). 결과: 노드 85~91%로 완화, Pending 전량 해소 |
| 3 | 프런트 파드 2일 9시간 동안 **750회 재시작**. probe가 간헐이 아닌 전수 타임아웃 | `cilium monitor` 실측: istio-cni가 kubelet probe를 `169.254.7.127`로 SNAT → Cilium이 `world` 아이덴티티로 분류 → CNP default-deny가 드롭(`action deny ... 169.254.7.127 -> pod:8080`). Istio 공식 문서에 명시된 ambient+Cilium 전제 조건 미적용이 원인 | CCNP `allow-ambient-hostprobes` (`enableDefaultDeny: false`로 순수 추가형), `fe18e8e`. 우회가 아니라 문서화된 표준 구성 |
| 4 | 백엔드 Ready 후에도 nginx `/api` 프록시 타임아웃, primary 파드가 정책 미보호 | Flagger가 primary 파드 라벨을 `app=<name>-primary`로 재작성하는데 두 CNP 모두 `app=<name>`만 매칭(모니터 실측: egress deny → :15008). 주석의 가정("primary도 같은 라벨")이 틀렸었음 | 두 CNP를 `matchExpressions`로 양쪽 라벨 매칭, `292e040` |
| 5 | 건강한 카나리가 "no values found"로 5회 누적 후 자동 롤백 (2회 재현) | (a) 5xx가 0건이면 성공률 분자가 빈 벡터 → 전체 식이 무값 (b) `rate[1m]` 창이 30초 스크레이프와 맞물려 간헐적으로 샘플 <2 | (a) `or on() vector(0)` `e97bd72` (b) 메트릭 창 2m `35a2a07`. 이후 완주·promotion 실측 확인 |
| 6 | Flagger가 실패 후 재시도하지 않음 / primary 스펙 미갱신 | 실패한 실행이 `lastAppliedSpec`을 소진해 "새 리비전" 트리거 부재. promotion 전에는 primary 스펙을 갱신하지 않는 설계 | 파드 템플릿 어노테이션 갱신으로 재실행 트리거(운영 절차로 문서화), primary는 수렴값으로 1회 패치 후 promotion으로 Git과 일치 |

### 2.2 DevSecOps: 게이트 구축, 그리고 게이트가 실제로 잡아낸 것

이전 상태: Trivy `exit-code: 0`(리포트만), `security-scans/` 디렉터리는 **비어 있었고** GitHub는 워크플로 하위 디렉터리를 읽지 않으므로 애초에 동작 불가능한 구조였다.

- 게이트 전환: Trivy 이미지 스캔 HIGH/CRITICAL 차단(`15c568b`), 액션 버전 핀(`1144b81` — 최초 핀은 `v` 접두사 누락으로 3개 워크플로가 즉시 실패했고, 이 실패 자체가 태그 검증의 근거가 됨).
- 신규: IaC 스캔(Trivy config), SAST(Semgrep p/ci), 시크릿 스캔(Gitleaks 전체 히스토리), DAST(OWASP ZAP baseline 주간+수동).
- **게이트가 차단한 실제 취약점:** ① Spring Boot 3.5.3의 관리 의존성 — tomcat-embed-core CVE-2026-41293(CRITICAL) 외 HIGH 다수 → parent 3.5.16 범프(`79abb50`) ② 베이스 이미지 동봉 `pebble` 바이너리의 Go 의존성 HIGH 7건 → 미사용 바이너리 자체를 제거(`c2705a7`) ③ IaC CRITICAL 9건은 전부 Flux 생성 파일의 KSV-0041(Flux의 Secret 관리 권한)로, GitOps 오퍼레이터의 본질적 권한이므로 사유를 명시하고 해당 벤더 파일만 게이트 제외(`b5b5c4b`). 작성 매니페스트는 0건.
- 수정된 무결점 이미지가 게이트→서명→GitOps 커밋→카나리(10→50%)→promotion까지 **사람 개입 없이 자동 배포**되는 것을 실측 확인.
- **SBOM(공급망 투명성, EO 14028):** 게이트를 통과한 이미지에 한해 CycloneDX SBOM을 생성, run 아티팩트로 발행(`6025575`). 실측: `sbom-backend-springboot` 18.2KB / `sbom-frontend-react` 8.5KB, 보존 90일. 이로써 출하 이미지마다 서명(무결성)+게이트(알려진 취약점 차단)+SBOM(구성 요소 목록) 3요소가 남는다.

### 2.3 HTTPS: 실패 이력을 포함한 정확한 전환 기록

1. cert-manager Gateway API 연동으로 Let's Encrypt HTTP-01 구성(`99543f7`): ClusterIssuer는 CRD 교착(사건 1의 교훈)을 피해 `istio-ambient/` 레이어에 배치. HTTP:80 리스너는 301 리다이렉트+ACME 전용으로 잠금(챌린지는 Exact 경로 우선순위로 통과).
2. **발급 실패:** `kro.kr`은 Public Suffix List에 없는 공유 무료 도메인이라 전 사용자가 LE의 등록도메인당 주 50장 쿼터를 공유 → `429 rateLimited`. 대응으로 두 리스너를 단일 SAN 인증서로 통합(`c3ef236`).
3. **ZeroSSL 전환(`55bba5f`):** ACME 경로는 도메인당 쿼터 없음(무료 90일 무제한). EAB 등록 9초, 발급 완료 약 1분. HMAC은 Git 밖 Secret(`cert-manager/zerossl-eab-hmac`)으로만 존재 — **클러스터 재구축 시 수동 재생성 필요.** `letsencrypt-prod`는 폴백 유지(동일 SAN 세트 갱신은 LE 쿼터 면제 규칙이 있으므로 유효한 대안). 참고: Buypass는 2025-10 발급 종료로 대안에서 제외됨.

### 2.4 브라우저 보안 헤더와 DAST 결과 (정확한 수치)

ZAP baseline 1차 스캔이 지적한 8개 헤더 계열 경고에 대해 nginx에 9종 헤더 적용(`5b4fca8`): HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy, COOP/COEP/CORP, CSP(빌드 산출물에 인라인 스크립트가 없음을 확인하고 `script-src 'self'`에서 `unsafe-inline` 제외). nginx `add_header` 상속 규칙 때문에 `/assets/` 블록에 전체 중복 선언(ZAP이 해당 경로를 개별 지적했던 원인).

재스캔 실측: **FAIL 0 / PASS 65 / WARN 5.** "완벽"이 아니라 잔여 WARN 5건은 전부 저위험 정보성이며 근거를 갖고 수용한 상태다 — 특히 `style-src 'unsafe-inline'`은 React 인라인 스타일 속성을 위한 **의도된 트레이드오프**(제거하려면 nonce/hash 빌드 파이프라인 필요), Sec-Fetch-Dest는 서버가 아닌 브라우저 요청 헤더에 대한 스캐너 노이즈다.

### 2.5 현재 상태 스냅샷과 알려진 부채

검증된 상태(2026-07-08): `https://ai-auto.kro.kr`/`https://api.ai-auto.kro.kr` 정상(ZeroSSL SAN 1장, 10-05 만료·자동 갱신), Flux Kustomization 12개 전부 Ready, 카나리 파이프라인 완주 실증, 보안 워크플로 5종 그린, 노드 CPU requests 85~91%.

남은 부채(객관적 목록): ① cosign 서명이 클러스터에서 검증되지 않음(서명만 하고 소비 안 함) ② 로그 중앙화 부재(Loki 미구축, 메트릭만 존재) ③ Kafka/Redis는 코드·인프라 모두 부재 ④ kro.kr 의존 — ZeroSSL로 발급 문제는 해소했으나 도메인 신뢰도·통제권 한계는 남음 ⑤ 노드 2대: 노드 1대 손실 시 CPU 예산상 전체 워크로드 재스케줄 불가 가능성이 높음(HA 아님) ⑥ ZeroSSL EAB Secret은 GitOps 밖 수동 자산.

---

## Phase 3: 확장 로드맵 (계획 — 미검증, 크레딧 확보 전제)

각 단계는 "전제 조건 → 작업 → 완료 기준(측정 가능) → 리스크" 순으로 기술한다. 순서는 의존성 순이다.

### Step 3.1: High Availability (노드 증설)
- **전제:** 크레딧 확보, Terraform `oci-oke` 노드풀 변수화.
- **작업:** 워커 3~4대로 증설(유료 shape 혼합). PodDisruptionBudget과 `topologySpreadConstraints`를 앱 매니페스트에 추가 — 노드 수만 늘리는 것은 HA가 아니다.
- **완료 기준:** 임의 노드 1대 `drain` 중 공개 URL 5xx 0건, 전 파드 재스케줄 성공.
- **리스크/한계:** LB와 리전은 여전히 단일. OKE 컨트롤플레인은 관리형이므로 범위 외.

### Step 3.2: 런타임 보안·관측성 (Kyverno, Loki)
- **전제:** Step 3.1로 CPU/메모리 여유 확보(현 2노드 예산에서는 admission webhook 상주 비용이 부담).
- **작업:** Kyverno `verifyImages`로 OCIR 이미지의 cosign 키리스 서명 강제(현재 서명은 생성만 되고 검증되지 않는 공백을 폐쇄) — `failurePolicy` 및 flux/시스템 네임스페이스 예외를 명시적으로 설계. 이때 SBOM도 `cosign attest --type cyclonedx`로 이미지에 서명 첨부해, 아티팩트 90일 보존을 레지스트리 수명 전체로 확장하고 SBOM 자체의 위변조 검증을 가능하게 하는 것을 함께 검토. Loki + promtail(또는 Alloy)로 로그 중앙화, 보존 기간은 스토리지 예산으로 상한.
- **완료 기준:** 미서명 이미지 배포 시도가 admission에서 거부되는 것을 실측. Grafana에서 백엔드 에러 로그 조회 가능.
- **리스크:** admission 장애 시 배포 전면 차단 가능성(fail-open/close 정책 결정 필요), Loki 메모리 사용량.

### Step 3.3: 이벤트 기반 MSA (Kafka/Redis 재도입)
- **전제:** Step 3.1 완료. **사건 1의 재발 방지 구조 필수:** 오퍼레이터 Kustomization과 인스턴스(CR) Kustomization을 분리하고 `dependsOn`으로 체이닝 — 보존된 `gitops/databases/` 매니페스트를 이 구조로 재편해서만 재도입한다.
- **작업:** Strimzi(KRaft, 소형 프로파일)·Redis 오퍼레이터 배포 → **백엔드 코드 작업이 본체**: spring-kafka 의존성/리스너/프로듀서, Redis 캐시 설정, health group 재설계, 백엔드 CNP egress는 이미 kafka/redis 네임스페이스 규칙 보유. Flagger 분석에 컨슈머 랙 등 비HTTP 지표 추가 검토.
- **완료 기준:** 이벤트 발행→소비 E2E 흐름이 카나리 분석을 통과해 promotion. 부하 시 OOM 이벤트 0건.
- **리스크:** Kafka는 이 아키텍처에서 가장 큰 메모리 소비자가 된다. 코드 부재 상태에서 인프라만 먼저 올리는 것(Phase 1의 실수 반복)을 금지 — 소비할 코드와 함께 배포한다.

### Exit Strategy (크레딧 만료 대비)
GitOps 원칙에 맞는 롤백은 "주석 처리"가 아니라 **git revert**다: Phase 3 리소스는 단계별 커밋으로 추가하므로 역순 revert → Flux `prune: true`가 클러스터에서 자동 회수한다.
1. 만료 D-3: Step 3.3 → 3.2 순으로 revert(상태 저장 워크로드 먼저 정리).
2. Terraform `node_count` 원복 → 유료 노드 파기.
3. 완료 기준: Phase 2 스냅샷(2.5절) 상태로 복귀 — 전 Kustomization Ready, 공개 URL 정상, 노드 2대 CPU requests < 95%.
4. 주의: ZeroSSL 인증서·EAB Secret은 노드 축소와 무관하게 유지된다. 유일한 수동 자산은 EAB Secret이며 클러스터 자체를 재생성하는 경우에만 재발급이 필요하다.
