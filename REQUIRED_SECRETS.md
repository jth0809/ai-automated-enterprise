# 필수 시크릿 및 API 키 체크리스트 (Required Secrets Checklist)

본 아키텍처(Oracle Cloud, GitOps, MSA)를 실제로 구동하기 위해 사전에 준비하고 등록해야 하는 자격 증명(Credentials) 목록입니다. 보안 원칙에 따라 이 값들은 절대 Git에 커밋되어서는 안 되며, 환경 변수나 보안 저장소(Vault)를 통해 주입되어야 합니다.

## 1. 인프라 프로비저닝 (Terraform ➔ Oracle Cloud)
Terraform이 OCI(Oracle Cloud Infrastructure) 리소스를 생성하기 위해 로컬 환경 또는 CI/CD 서버에 구성되어야 하는 값들입니다.
- [ ] **OCI Tenancy OCID**: 클라우드 테넌트 고유 ID
- [ ] **OCI User OCID**: 인프라 배포 권한을 가진 사용자 ID
- [ ] **OCI API Key Fingerprint**: 등록된 공개 키의 지문
- [ ] **OCI Private Key (`.pem`)**: API 호출을 위한 개인 키 파일
- [ ] **OCI Region**: 예) `ap-seoul-1`

## 2. GitHub Actions (CI 파이프라인)
GitHub 저장소의 `Settings > Secrets and variables > Actions`에 등록해야 하는 값들입니다. (이름은 `.github/workflows/ci-*.yaml`이 참조하는 실제 시크릿 이름과 일치해야 합니다.)
- [ ] **OCI_REGION**: OCIR 리전 식별자. 예) `ap-osaka-1` (terraform.tfvars와 동일해야 함)
- [ ] **OCI_TENANCY_NAMESPACE**: OCIR 이미지 경로에 쓰이는 테넌시 오브젝트 스토리지 네임스페이스
- [ ] **OCI_CLI_USER**: OCIR 로그인 사용자 (예: `oracleidentitycloudservice/user@example.com`)
- [ ] **OCIR_AUTH_TOKEN**: OCIR 도커 로그인용 Auth Token (OCI 콘솔에서 발급)
- [ ] **TRIVY_API_KEY** (선택 사항): 취약점 스캐너 등 외부 보안 SaaS를 연동할 경우 필요한 토큰

## 3. GitOps 및 Kubernetes (FluxCD & 클러스터 내부)
Kubernetes 클러스터 내부에 주입되거나 OCI Vault에 저장되어야 하는 값들입니다.
- [ ] **GitHub Deploy Key 또는 PAT**: FluxCD가 이 저장소(`gitops/` 폴더)의 변경 사항을 읽고 쓰기 위해 필요한 권한 (읽기 전용 권한 권장)
- [ ] **Image Pull Secret**: K8s가 OCI 비공개 레지스트리(OCIR)에서 이미지를 다운로드하기 위한 자격 증명
- [ ] **OCI Vault 자격 증명**: `external-secrets` 오퍼레이터가 OCI Vault에 접근하여 비밀번호를 가져오기 위한 권한 (IAM Role/Instance Principal 방식 권장)
- [ ] **DNS Provider API Key**: `cert-manager`가 Let's Encrypt를 통해 HTTPS 인증서(DNS-01 챌린지)를 자동 발급받기 위한 도메인 제공자(예: Cloudflare, Route53, OCI DNS)의 토큰

## 4. OCI Vault에 등록해야 하는 시크릿 (external-secrets가 참조)
`gitops/apps/**`와 `gitops/infrastructure/**`의 ExternalSecret 매니페스트가 아래 이름(Vault Secret Name)으로 조회합니다. 각 공급자에서 발급한 값을 등록하며, Terraform이 자동 생성하는 ATP 항목은 해당 설명을 따르십시오.
- [x] **backend-atp-jdbc-url**: ATP JDBC 접속 문자열 (One-way TLS, 포트 1521) — Terraform `oci-vault` 모듈이 자동 생성
- [x] **backend-atp-username** / **backend-atp-password**: 현재 ADMIN 임시 사용 (Terraform 자동 생성). 앱 전용 스키마 계정 생성 후 시크릿 **값만** 교체할 것 (키 이름 유지)
- [ ] **ocir-dockerconfigjson**: `terraform.tfvars`에 `ocir_username`, `ocir_auth_token` 설정 후 `terraform apply` 시 자동 생성 (미설정 시 생략됨 → 이미지 풀 불가)
- [ ] **DISCORD_ALERTS_WEBHOOK_URL**: Discord 운영 채널의 **incoming Webhook URL** 전체. Bot API 키나 Bot token이 아니다. OCI Vault에 base URL만 저장하며 `/github` 접미사는 붙이지 않는다. Flux, Flagger, Alertmanager의 네임스페이스별 ExternalSecret이 이 동일한 원격 키를 참조한다.
- (예약) **backend-kafka-username** / **backend-kafka-password**: Kafka 리스너에 SASL 활성화 시 등록하고 ExternalSecret에 항목 재추가

---

> **Zero-Trust 원칙 가이드**
> 위 목록의 어떤 값도 하드코딩되어서는 안 됩니다. K8s 내부에서 쓰이는 비밀번호(DB 비밀번호 등)는 **반드시 OCI Vault에 저장**한 후, `external-secrets` 오퍼레이터를 통해 클러스터로 안전하게 동기화(Sync)하는 방식을 사용해야 합니다.
