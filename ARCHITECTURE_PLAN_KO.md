# AI 자동화 엔터프라이즈 아키텍처 및 개발 계획

이 문서는 본 저장소에 구현된 제로 트러스트(Zero-Trust) DevSecOps, GitOps 및 마이크로서비스 아키텍처(MSA)를 위한 마스터 청사진 역할을 합니다.

## 1. 핵심 원칙

- 제로 트러스트 보안 (Zero-Trust Security): 네트워크 내부에 암묵적인 신뢰는 존재하지 않습니다. 마이크로서비스 간의 모든 요청은 Istio Ambient Mesh와 Cilium을 통한 상호 TLS(mTLS)로 인증되고 암호화되어야 합니다.
- 단일 진실 공급원으로서의 GitOps: Kubernetes 클러스터의 상태는 `gitops/` 디렉터리를 참조하는 FluxCD에 의해 엄격하게 선언적으로 관리됩니다. 수동으로 `kubectl apply`를 실행하는 것은 금지됩니다.
- DevSecOps 우선 (DevSecOps First): 보안 검증(SAST, DAST, 컨테이너 스캐닝, IaC 스캐닝)은 GitHub Actions CI 워크플로우에 직접 내장됩니다. 비밀값(Secrets)은 어떠한 경우에도 Git에 저장되지 않습니다.
- 마이크로서비스 및 비동기 메시징: 서비스는 Spring Boot를 사용하여 느슨하게 결합(Decoupling)됩니다. 서비스 간 통신은 비동기 이벤트 기반 패턴을 위해 Kafka에 의존하고, 분산 캐싱을 위해 Redis를 활용합니다.

## 2. 기술 스택 및 한도 (Oracle Always Free)

| 도메인 | 기술 및 한도 |
| :--- | :--- |
| 프론트엔드 (Frontend) | React (마이크로 프론트엔드(MFE) 아키텍처 호환) |
| 백엔드 (Backend) | Spring Boot (Java/Kotlin) |
| 데이터베이스 (Database) | Oracle Autonomous Transaction Processing (ATP) - 최대 1 OCPU, 20GB |
| 메시징 / 캐시 (Messaging / Cache) | Kafka (Strimzi Operator), Redis (K8s Operator) |
| 인프라 프로비저닝 (IaC) | Terraform (Oracle Cloud / OCI), Ansible |
| 컨테이너 오케스트레이션 | Oracle Kubernetes Engine (OKE) 또는 K3s |
| 컴퓨트 한도 (Compute Limits) | 최대 2 OCPU / 12GB RAM (ARM A1), AMD 마이크로 VM 2개, 스토리지 200GB |
| GitOps / 지속적 배포 (CD) | FluxCD |
| CI / 보안 스캔 | GitHub Actions, Trivy(이미지 + IaC 미스컨피그, tfsec 후속), Semgrep SAST, Gitleaks, OWASP ZAP DAST, CycloneDX SBOM, cosign 서명 |
| 서비스 메쉬 / CNI | Istio Ambient Mesh, Cilium |
| 인그레스 / HTTPS | Kubernetes Gateway API, cert-manager |
| 비밀값 관리 (Secret Management) | OCI Vault + External Secrets Operator |
| 점진적 배포 (Progressive Delivery) | Flagger / Argo Rollouts (카나리 배포, A/B 테스트) |
| 관측성 (Observability) | Prometheus, Grafana, Loki |

## 3. 폴더 구조도 (Directory Structure Map)

```text
/ai-automated-enterprise
├── .github/
│   └── workflows/
│       └── security-scans/    # CI 파이프라인 및 보안 스캔
├── apps/                      # 애플리케이션 소스 코드
│   ├── backend/springboot-app/
│   └── frontend/react-app/
├── gitops/                    # FluxCD 선언적 매니페스트
│   ├── apps/                  # 배포, 서비스, 점진적 배포 설정
│   │   ├── backend-springboot/
│   │   │   └── progressive-delivery/
│   │   └── frontend-react/
│   ├── clusters/production/   # 루트 동기화 포인트
│   ├── databases/             # K8s 내부 상태 유지(Stateful) 워크로드
│   │   ├── kafka/             # Strimzi Kafka Operator
│   │   └── redis/             # Redis Operator
│   ├── infrastructure/        # K8s 핵심 애드온
│   │   ├── cert-manager/      # HTTPS 인증서 발급 관리
│   │   ├── cilium/            # CNI 및 네트워크 정책
│   │   ├── external-secrets/  # OCI Vault로부터 안전한 시크릿 주입
│   │   ├── istio-ambient/     # L7 메쉬 및 mTLS 통신
│   │   ├── observability/     # 메트릭 모니터링 및 중앙 집중 로깅
│   │   └── storage/           # 스토리지 설정
│   └── security/
│       └── external-secrets/
└── infrastructure/            # Terraform & Ansible 인프라 코드 (IaC)
    ├── ansible/oke-nodes-setup/
    └── terraform/
        ├── oci-autonomous-db/ # Oracle Autonomous DB (ATP)
        ├── oci-network/       # VCN 및 서브넷 프로비저닝
        └── oci-oke/           # OKE 클러스터 프로비저닝
```

## 4. 단계별 개발 계획

### 1단계: 인프라 프로비저닝 (Terraform)
- Oracle Cloud (OCI) 프로바이더 구성.
- 가상 클라우드 네트워크(VCN) 및 서브넷 프로비저닝 (`oci-network`).
- 2 OCPU / 12GB RAM 한도를 엄격히 준수하여 Oracle Kubernetes 클러스터 프로비저닝 (`oci-oke`).
- Oracle Autonomous Database (ATP) 프로비저닝 (`oci-autonomous-db`).

### 2단계: GitOps 및 보안 부트스트래핑
- 클러스터에 FluxCD를 설치하고 `gitops/clusters/production` 디렉터리와 연동.
- L3/L4 수준의 네트워크 정책 제어를 위한 CNI로 Cilium 배포.
- 안전한 HTTPS 통신을 위한 cert-manager 및 OCI Vault와 통신할 external-secrets 배포.

### 3단계: 제로 트러스트 메쉬 및 데이터 플레인
- 사이드카 없이(Sidecar-less) 경량화된 mTLS 통신을 보장하는 Istio Ambient Mesh 배포.
- 비동기 통신과 캐싱을 담당할 Kafka(Strimzi)와 Redis를 클러스터에 배포.
- 시스템 전반을 모니터링하기 위한 관측성(Observability) 스택(Prometheus/Grafana) 구축.

### 4단계: 애플리케이션 CI/CD
- React 및 Spring Boot 이미지를 빌드, 취약점 스캔(Trivy), OCI 컨테이너 레지스트리에 푸시하기 위한 GitHub Actions 워크플로우 구성.
- 취약점 게이트를 통과한 모든 이미지에 대해 CycloneDX SBOM을 생성하고 run 아티팩트로 발행 (공급망 투명성, EO 14028).
- 새로운 빌드 이미지가 푸시되면 GitOps 매니페스트의 이미지 태그를 자동 업데이트하도록 구성.

### 5단계: 점진적 배포 및 MSA 연동
- `gitops/apps/` 경로를 통해 마이크로서비스 애플리케이션 배포.
- 안전한 프로덕션 릴리즈를 위해 Flagger 또는 Argo Rollouts를 활용한 카나리(Canary) 및 A/B 테스트 구현.
- 분산 시스템 환경에서 Spring Boot 서비스들이 Kafka를 통해 비동기적으로 이벤트를 주고받도록 통신 구현.
