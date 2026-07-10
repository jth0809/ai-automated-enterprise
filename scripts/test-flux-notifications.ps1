. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$notifications = Build-Kustomization "gitops/infrastructure/notifications"
$fluxBootstrap = Build-Kustomization "gitops/clusters/production/flux-system"
$infrastructure = Read-RepoFile "gitops/clusters/production/infrastructure.yaml"

Assert-Matches $notifications "(?m)^kind: ExternalSecret\r?$" "Flux Discord credential must come from ExternalSecret"
Assert-Matches $notifications "(?m)^\s+key: DISCORD_ALERTS_WEBHOOK_URL\r?$" "Flux must use the approved OCI Vault key"
Assert-Matches $notifications "(?m)^kind: Provider\r?$" "Flux Discord Provider must exist"
Assert-Matches $notifications "(?m)^\s+type: discord\r?$" "Flux Provider must use Discord"
Assert-Matches $notifications "(?m)^kind: Alert\r?$" "Flux Alert must exist"
Assert-Matches $notifications "(?m)^\s+eventSeverity: error\r?$" "Only actionable Flux errors should notify"
Assert-Matches $notifications "(?ms)eventSources:.*?kind: Kustomization.*?kind: HelmRelease" "Both reconciliation kinds must be covered"
Assert-Matches $fluxBootstrap "(?m)^kind: CiliumNetworkPolicy\r?$" "notification-controller needs a Cilium policy"
Assert-Matches $fluxBootstrap "(?m)^\s+- matchName: discord\.com\r?$" "Discord egress must be FQDN-scoped"
Assert-Matches $fluxBootstrap "(?m)^\s+- kube-apiserver\r?$" "notification-controller needs Kubernetes API"
Assert-Matches $fluxBootstrap "(?ms)kind: NetworkPolicy.*?name: allow-egress.*?podSelector:\s+matchExpressions:.*?operator: NotIn.*?notification-controller" "notification-controller must leave broad egress"
Assert-Matches $infrastructure "(?m)^\s+name: infra-notifications\r?$" "notifications need a Flux Kustomization"
Assert-Matches $infrastructure "(?ms)name: infra-notifications.*?dependsOn:.*?name: infra-flagger.*?name: security-external-secrets" "notifications must wait for prerequisites"

Write-Host "Flux notification contracts passed"
