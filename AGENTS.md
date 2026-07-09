# Master Routing and Global Constitution

## AI Automated Enterprise Core Principles

All AI agents working on this repository MUST strictly adhere to the following architectural and security principles at all times:

1. **Zero-Trust Security**: 
   - There is NO implicit trust inside the network.
   - All inter-service communication must be authenticated and encrypted via mutual TLS (mTLS) through Istio Ambient Mesh and Cilium.
   - Any new outbound network connections (egress) must be explicitly whitelisted using CiliumNetworkPolicy (CNP) with `toFQDNs` or specific IPs. Default behavior is `default-deny`.

2. **GitOps as the Single Source of Truth**:
   - The state of the Kubernetes cluster is strictly and declaratively managed by FluxCD via the `gitops/` directory.
   - Manual `kubectl apply` commands or imperative state changes are STRICTLY FORBIDDEN in production. All infrastructure and application changes must be made via Git commits.

3. **DevSecOps First**:
   - Security scanning (SAST via Semgrep, DAST via ZAP, Container Scanning via Trivy, Secrets scanning via Gitleaks) is embedded in the CI/CD pipeline.
   - NEVER commit plain-text secrets to the repository. All secrets must be stored in OCI Vault and injected via External Secrets Operator.

4. **Resource Consciousness (Constraints)**:
   - The infrastructure runs under extreme resource constraints. CPU requests and memory limits MUST be tightly controlled and justified. Avoid deploying resource-heavy components without prior scaling approval.

---

Empower engineers through automation and paved paths.
Maintain strict boundaries between infrastructure deployment and application logic.
Every process must prioritize fast iteration loops backed by automated safety nets.
Security is non-negotiable and must be enforced at every layer without slowing down development.
