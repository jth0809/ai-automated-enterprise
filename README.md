# AI Automated Enterprise

**https://ai-auto.kro.kr**

Oracle Cloud **Always Free Tier**(ARM A1 2 OCPU / 12GB) 위에 Zero-Trust DevSecOps · GitOps · 프로그레시브 딜리버리 파이프라인 전체를 구축한 레퍼런스 프로젝트입니다. `main` 브랜치에 코드를 푸시하면 CI가 이미지를 빌드·스캔·서명하고, FluxCD가 클러스터를 동기화하며, Flagger가 카나리 분석을 거쳐 자동으로 프로덕션에 승격합니다.

> 상세 설계 문서: [ARCHITECTURE_PLAN.md](ARCHITECTURE_PLAN.md) ([한국어](ARCHITECTURE_PLAN_KO.md)) · 필수 자격증명: [REQUIRED_SECRETS.md](REQUIRED_SECRETS.md)

## 아키텍처 개요

```text
사용자 ── https://ai-auto.kro.kr  (HTTP:80은 301 리다이렉트, ACME 챌린지만 예외)
  │
  ▼
[OCI Load Balancer 64.110.118.19]
  ▼
[Istio Gateway (Gateway API, istio-ingress ns)] ← cert-manager가 Let's Encrypt 인증서 자동 발급/갱신
  │  HTTPRoute (hostname 기반, HTTPS 리스너에만 연결)
  ├─ ai-auto.kro.kr     → frontend-react (nginx, React SPA)
  │                         └─ /api → backend apex Service로 리버스 프록시
  └─ api.ai-auto.kro.kr → backend-springboot primary/canary (Flagger가 가중치 제어)
                            └─ Oracle ATP (클러스터 외부, TLS 1521)

데이터플레인: Cilium CNI (+CiliumNetworkPolicy 제로트러스트) + Istio Ambient Mesh (ztunnel HBONE mTLS, 사이드카 없음)
컨트롤플레인: FluxCD ← gitops/clusters/production (Git이 유일한 진실의 원천)
```

**배포 흐름**: `apps/**` 푸시 → GitHub Actions(빌드→Trivy→OCIR 푸시→cosign 서명→GitOps 매니페스트 태그 자동 커밋) → FluxCD 동기화 → Flagger가 카나리 파드에 트래픽 10%→50% 단계 주입, Prometheus 메트릭(성공률·p99 지연)으로 검증 → 통과 시 primary로 승격, 실패 시 자동 롤백.

## 기술 스택

| 영역 | 기술 (배포 확인된 버전) |
| :--- | :--- |
| 프런트엔드 | React 19 + TypeScript + Vite, unprivileged nginx (8080) |
| 백엔드 | Spring Boot (Java 21, Maven), Actuator 헬스 프로브 |
| 데이터베이스 | Oracle Autonomous Transaction Processing (ATP, 클러스터 외부) |
| IaC | Terraform (oci-network / oci-oke / oci-autonomous-db / oci-vault), Ansible (노드 flannel 정리) |
| 오케스트레이션 | OKE (Kubernetes v1.36, ARM A1 노드 2대) |
| GitOps | FluxCD (source/kustomize/helm/notification 컨트롤러) |
| CI / 보안 | GitHub Actions, Trivy 이미지·IaC 스캔(HIGH/CRITICAL 게이트), Semgrep SAST, Gitleaks 시크릿 스캔, OWASP ZAP DAST(주간+수동), CycloneDX SBOM(run 아티팩트, 90일 보존), cosign keyless 서명(OIDC) |
| CNI / 정책 | Cilium v1.17 (CiliumNetworkPolicy 기반 default-deny 제로트러스트) |
| 서비스 메시 | Istio Ambient v1.26 (ztunnel, 사이드카리스 mTLS) |
| 인그레스 / TLS | Kubernetes Gateway API v1.3 + Istio Gateway, cert-manager + Let's Encrypt(ACME HTTP-01 자동 발급·갱신), HTTP→HTTPS 301 |
| 시크릿 | OCI Vault + External Secrets Operator (ClusterSecretStore `oci-vault`) |
| 프로그레시브 딜리버리 | Flagger (gatewayapi:v1 프로바이더) + flagger-loadtester |
| 관측성 / 운영 알림 | kube-prometheus-stack (Prometheus 보존 1d/2GB, Grafana, Alertmanager 1 replica) + Flux/Flagger/Alertmanager → Discord (OCI Vault 기반) |
| 메시징/캐시 | ~~Kafka(Strimzi), Redis~~ — **Free Tier 자원 한계로 의도적 비활성** (아래 참고) |

## 저장소 구조

```text
├── .github/workflows/       # ci-backend / ci-frontend / security-scan-{iac,sast,secrets,dast}
├── apps/
│   ├── backend/springboot-app/   # Spring Boot 소스 + Dockerfile
│   └── frontend/react-app/       # React(Vite) 소스 + nginx.conf + Dockerfile
├── gitops/
│   ├── clusters/production/  # Flux 루트 싱크 포인트 (infrastructure→security→apps 의존성 체인)
│   ├── infrastructure/       # cilium, cert-manager, external-secrets, gateway-api,
│   │                         #   istio-ambient(+cilium-hostprobes), observability, flagger
│   ├── security/             # ClusterSecretStore (OCI Vault 연동)
│   ├── apps/                 # Deployment/Service/HTTPRoute/CNP/Canary/MetricTemplate
│   └── databases/            # kafka/, redis/ — 현재 미배포(참조 제거됨), 향후 재사용용 보관
└── infrastructure/
    ├── terraform/            # VCN, OKE, ATP, Vault 프로비저닝
    └── ansible/oke-nodes-setup/  # 노드 flannel 잔재 정리
```

각 디렉터리의 작업 규칙은 해당 위치의 `AGENTS.md`를 참고하세요.

## 처음부터 구축하기

### 0. 사전 준비
- OCI 계정(Always Free), 로컬 도구: `terraform`, `kubectl`, `flux` CLI, OCI CLI(`install.ps1`)
- [REQUIRED_SECRETS.md](REQUIRED_SECRETS.md)의 체크리스트대로 자격증명 준비

### 1. 인프라 프로비저닝
```bash
cd infrastructure/terraform
# terraform.tfvars에 테넌시/리전/키 설정
terraform init && terraform apply     # VCN + OKE + ATP + Vault 생성, kubeconfig 출력
```

### 2. 노드 사전 정리 (CNI 교체 준비)
```bash
cd infrastructure/ansible/oke-nodes-setup
# README.md 참고: OKE 기본 flannel 제거 후 Cilium이 CNI를 인수
```

### 3. GitOps 부트스트랩
```bash
flux bootstrap github \
  --owner=<github-owner> --repository=ai-automated-enterprise \
  --branch=main --path=gitops/clusters/production
```
이후 모든 것은 Flux가 의존성 순서대로 설치합니다: Cilium → cert-manager → external-secrets → Gateway API CRD → Istio Ambient(+게이트웨이) → 관측성 → Flagger → 앱.

### 4. GitHub Actions 시크릿 등록
`OCI_REGION`, `OCI_TENANCY_NAMESPACE`, `OCI_CLI_USER`, `OCIR_AUTH_TOKEN` — 상세는 [REQUIRED_SECRETS.md](REQUIRED_SECRETS.md).

## 사용 방법

### 배포 (평상시 워크플로)
1. `apps/backend/springboot-app/**` 또는 `apps/frontend/react-app/**` 수정 후 `main`에 푸시.
2. CI가 이미지를 `<region>.ocir.io/<namespace>/<app>:<sha12>`로 푸시하고 `gitops/apps/*/deployment.yaml`의 태그를 자동 커밋(`[skip ci]`).
3. Flux(기본 10분 주기, GitRepository는 1분)가 반영 → 백엔드는 Flagger 카나리(10%→50%, 분당 10%p, 성공률≥99% & p99≤500ms), 프런트엔드는 즉시 롤아웃.

즉시 동기화가 필요하면(로컬에 flux CLI가 없을 때):
```bash
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
kubectl annotate --overwrite gitrepository -n flux-system flux-system reconcile.fluxcd.io/requestedAt=$TS
kubectl annotate --overwrite kustomization -n flux-system flux-system reconcile.fluxcd.io/requestedAt=$TS
```

### 상태 확인
```bash
kubectl get kustomizations -n flux-system     # GitOps 동기화 상태
kubectl get canary -n backend                 # 카나리 진행 상황 (Progressing/Succeeded/Failed)
kubectl get pods -n frontend -n backend
kubectl get certificate -n istio-ingress      # TLS 인증서 발급/갱신 상태
kubectl -n monitoring get alertmanagerconfig platform-alertmanager
kubectl -n monitoring get alertmanager kube-prometheus-stack-alertmanager
kubectl -n monitoring get pods -l alertmanager=kube-prometheus-stack-alertmanager
curl https://ai-auto.kro.kr/                  # 프런트엔드 (HTTP 200)
curl -I http://ai-auto.kro.kr/                # 301 → https 리다이렉트 확인
```

### 운영 알림

Flux 조정 오류와 Flagger 카나리 실패는 각 Discord Provider가 전달하고, Prometheus 경보는 전역 `AlertmanagerConfig/platform-alertmanager`를 거쳐 같은 운영 채널로 전달한다. Discord incoming Webhook은 저장소에 기록하지 않고 OCI Vault의 `DISCORD_ALERTS_WEBHOOK_URL`에서 External Secrets Operator가 동기화한다.

GitOps 구성과 정적 계약은 구현되어 있지만 실제 이벤트의 Discord 수신은 `runtime delivery verification pending` 상태다. 프로덕션 장애를 인위적으로 만들지 말고 자연 발생한 비프로덕션 또는 실제 조치 가능 이벤트로 검증한다. 프로비저닝·상태 확인·교체 절차는 [Discord 알림 운영 Runbook](docs/runbooks/discord-alerting.md)을 따른다.

### Grafana 대시보드
```bash
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
# http://localhost:3000
```

### 카나리 강제 재실행
실패한 카나리는 "새 리비전"이 없으면 재시도하지 않습니다. 파드 템플릿 어노테이션을 갱신해 트리거하세요:
```bash
kubectl patch deploy -n backend backend-springboot --type=merge \
  -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"flagger.app/revalidated-at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}}}}}"
```

## CPU 예산 (Always Free 2노드의 제1 관리 대상)

노드당 할당 가능 CPU는 **840m**뿐이며, 이 클러스터 장애 이력의 상당수가 requests 포화에서 왔다. 규칙:

- 새 워크로드 추가 전 `kubectl describe nodes | grep -A5 "Allocated resources"`로 여유 확인. **노드당 requests 합계 800m 초과 금지**(카나리 분석 시 backend 25m×2가 동시에 뜰 여유 필수).
- 인프라 컴포넌트는 HelmRelease values(또는 flux-system `patches:`)로 requests를 명시적으로 줄여서 배포한다 — 기본값(100m대)을 그대로 두면 두세 개로 예산이 끝난다.
- **주의: OKE가 `kube-flannel-ds`를 주기적으로 재생성한다**(실측 2026-07-07 — 노드당 100m 잠식, 카나리 스케줄 실패 유발). Cilium이 CNI이므로 flannel은 불필요: `kubectl -n kube-system delete daemonset kube-flannel-ds` ([절차 문서](infrastructure/ansible/oke-nodes-setup/README.md)). 재발 감지는 Alertmanager 노드 예산 룰(백로그 0절)로.

## 운영 노하우 (실전에서 검증된 함정들)

| 증상 | 원인과 해법 |
| :--- | :--- |
| 메시 편입 파드가 probe 타임아웃으로 무한 재시작 | istio-cni가 kubelet probe를 `169.254.7.127`로 SNAT → Cilium이 `world`로 분류해 default-deny에 드롭. `gitops/infrastructure/istio-ambient/cilium-hostprobes.yaml`(CCNP)이 해결책이므로 **절대 제거 금지** |
| Flagger primary가 CNP에 매칭 안 됨 | Flagger는 primary 파드 라벨을 `app=<name>-primary`로 재작성. CNP는 `matchExpressions`로 두 라벨 모두 매칭해야 함 |
| 카나리가 "no values found"로 롤백 | ① 5xx가 0건이면 성공률 분자가 빈 벡터 → 쿼리에 `or on() vector(0)` 필요 ② 메트릭 `interval`은 rate() 창이므로 30s 스크레이프 기준 최소 2m |
| 파드가 `Insufficient cpu`로 Pending | 노드당 할당 가능 CPU가 840m뿐. 앱 requests는 최소(백엔드 25m, CPU limit 없음 — JVM 버스트 허용)로, 카나리 분석 중 primary+canary 동시 기동을 감안해 산정 |
| Kustomization이 dry-run 실패로 교착 | CRD와 해당 CR을 같은 Kustomization에 넣지 말 것(오퍼레이터/인스턴스 분리 + `dependsOn` 체이닝) |

## 계획(ARCHITECTURE_PLAN.md) 대비 현황

| 항목 | 상태 |
| :--- | :--- |
| Phase 1 인프라 (VCN/OKE/ATP + Vault) | ✅ 완료 (+계획에 없던 oci-vault 모듈 추가) |
| Phase 2 GitOps/보안 부트스트랩 | ✅ 완료 |
| Phase 3 메시 + 관측성 | ✅ 완료 (Loki는 미구축 — Prometheus/Grafana만) |
| Phase 3 Kafka/Redis | ⏸️ **의도적 보류** — Free Tier 2 OCPU에서 Strimzi+Redis 오퍼레이터는 OOM 유발, 앱 코드도 아직 미사용. 매니페스트는 `gitops/databases/`에 보관 |
| Phase 4 CI/CD | ✅ 완료 (빌드→Trivy 게이트→SBOM 생성→OCIR→cosign→GitOps 자동 커밋). IaC 스캔(Trivy config), SAST(Semgrep), 시크릿 스캔(Gitleaks), DAST(OWASP ZAP baseline, 주간+수동), CycloneDX SBOM 아티팩트 가동. 남은 항목: 클러스터 측 cosign 서명 검증(admission) |
| Phase 5 프로그레시브 딜리버리 | ✅ 카나리 완주(promotion) 검증 완료. A/B 테스트는 미구현 |
| 운영 알림 (Discord) | 🛠️ Flux·Flagger·Alertmanager GitOps 구성 완료. Alertmanager는 Vault Secret을 참조하는 전역 `AlertmanagerConfig` 사용. 실제 Discord 전달은 `runtime delivery verification pending` |
| Zero-Trust (mTLS + default-deny CNP) | ✅ 앱 네임스페이스 적용 완료. ATP egress도 `world`가 아닌 리전 ADB FQDN(`adb.ap-osaka-1.oraclecloud.com`)으로 한정 |
| HTTPS / 실도메인 | ✅ `ai-auto.kro.kr` + `api.ai-auto.kro.kr` — Let's Encrypt 자동 발급(ClusterIssuer `letsencrypt-prod`, Gateway HTTP-01), HTTP는 301 리다이렉트 |
