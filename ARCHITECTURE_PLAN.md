# AI Automated Enterprise Architecture & Development Plan

This document serves as the master blueprint for the Zero-Trust DevSecOps, GitOps, and Microservices Architecture (MSA) implemented in this repository.

## 1. Core Principles

- Zero-Trust Security: No implicit trust within the network. Every request between microservices must be authenticated and encrypted using mutual TLS (mTLS) via Istio Ambient Mesh and Cilium.
- GitOps as the Source of Truth: The Kubernetes cluster state is strictly managed by FluxCD pulling from the `gitops/` directory. No manual `kubectl apply` is permitted.
- DevSecOps First: Security validations (SAST, DAST, container scanning, IaC scanning) are embedded directly into GitHub Actions CI workflows. Secrets are never stored in Git.
- Microservices & Async Messaging: Services are decoupled using Spring Boot. Inter-service communication relies on Kafka for asynchronous event-driven patterns, and Redis for distributed caching.

## 2. Technology Stack & Limits (Oracle Always Free)

| Domain | Technology / Limit |
| :--- | :--- |
| Frontend | React (Micro-Frontend ready) |
| Backend | Spring Boot (Java/Kotlin) |
| Database | Oracle Autonomous Transaction Processing (ATP) - Max 1 OCPU, 20GB |
| Messaging / Cache | Kafka (Strimzi Operator), Redis (K8s Operator) |
| Infrastructure (IaC) | Terraform (Oracle Cloud / OCI), Ansible |
| Container Orchestration | Oracle Kubernetes Engine (OKE) or K3s |
| Compute Limits | Max 2 OCPU / 12GB RAM (ARM A1), 2 AMD Micro VMs, 200GB Storage |
| GitOps / CD | FluxCD |
| CI / Security Scans | GitHub Actions, Trivy (image + IaC misconfig, successor of tfsec), Semgrep SAST, Gitleaks, OWASP ZAP DAST, CycloneDX SBOM, cosign signing |
| Service Mesh / CNI | Istio Ambient Mesh, Cilium |
| Ingress / HTTPS | Kubernetes Gateway API, cert-manager |
| Secret Management | OCI Vault + External Secrets Operator |
| Progressive Delivery | Flagger / Argo Rollouts (Canary, A/B Testing) |
| Observability | Prometheus, Grafana, Loki |

## 3. Directory Structure Map

```text
/ai-automated-enterprise
├── .github/
│   └── workflows/
│       └── security-scans/    # CI Pipelines & Security Scans
├── apps/                      # Application Source Code
│   ├── backend/springboot-app/
│   └── frontend/react-app/
├── gitops/                    # FluxCD Declarative Manifests
│   ├── apps/                  # Deployments, Services, Progressive Delivery
│   │   ├── backend-springboot/
│   │   │   └── progressive-delivery/
│   │   └── frontend-react/
│   ├── clusters/production/   # Root Sync Point
│   ├── databases/             # Stateful workloads in K8s
│   │   ├── kafka/             # Strimzi Kafka Operator
│   │   └── redis/             # Redis Operator
│   ├── infrastructure/        # Core K8s Addons
│   │   ├── cert-manager/      # HTTPS certificates
│   │   ├── cilium/            # CNI and Network Policies
│   │   ├── external-secrets/  # Secure secret injection from OCI Vault
│   │   ├── istio-ambient/     # L7 Mesh and mTLS
│   │   ├── observability/     # Metrics and Logging
│   │   └── storage/           # Storage classes and PVCs
│   └── security/
│       └── external-secrets/
└── infrastructure/            # Terraform & Ansible IaC
    ├── ansible/oke-nodes-setup/
    └── terraform/
        ├── oci-autonomous-db/ # Oracle Autonomous DB (ATP)
        ├── oci-network/       # VCN & Subnets
        └── oci-oke/           # OKE Cluster Provisioning
```

## 4. Phased Development Plan

### Phase 1: Infrastructure Provisioning (Terraform)
- Configure Oracle Cloud (OCI) provider.
- Provision Virtual Cloud Network (VCN) and subnets (`oci-network`).
- Provision Oracle Kubernetes cluster adhering strictly to the 2 OCPU / 12GB RAM limit (`oci-oke`).
- Provision Autonomous Database (ATP) (`oci-autonomous-db`).

### Phase 2: GitOps & Security Bootstrapping
- Install FluxCD in the cluster pointing to `gitops/clusters/production`.
- Deploy Cilium as the CNI for network policies.
- Deploy cert-manager and external-secrets to secure communication and credentials.

### Phase 3: Zero-Trust Mesh & Data Plane
- Deploy Istio Ambient Mesh for sidecar-less mTLS.
- Deploy Kafka (via Strimzi) and Redis into the cluster for async processing and caching.
- Establish observability stack (Prometheus/Grafana).

### Phase 4: Application CI/CD
- Write GitHub Actions workflows to build, scan (Trivy), and push React and Spring Boot images to OCI Registry.
- Generate a CycloneDX SBOM for every image that passes the vulnerability gate and publish it as a run artifact (supply-chain transparency, EO 14028).
- Auto-update GitOps manifests with new image tags.

### Phase 5: Progressive Delivery & MSA
- Deploy applications via `gitops/apps/`.
- Implement Canary and A/B Testing using Flagger/Argo Rollouts for safe production releases.
- Ensure Spring Boot services communicate asynchronously via Kafka where applicable.
