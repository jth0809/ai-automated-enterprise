. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$notifications = Build-Kustomization "gitops/infrastructure/notifications"
$fluxBootstrap = Build-Kustomization "gitops/clusters/production/flux-system"
$infrastructure = Read-RepoFile "gitops/clusters/production/infrastructure.yaml"
$egressPolicy = (Get-RenderedResource $fluxBootstrap "CiliumNetworkPolicy") -replace "`r`n", "`n"
$apiEgressPolicy = [regex]::Match(
    $egressPolicy,
    '(?ms)^  - toEntities:\n.*?(?=^  - to(?:Endpoints|Entities|FQDNs):|\z)'
).Value
$notificationSource = @(
    Read-RepoFile "gitops/infrastructure/notifications/kustomization.yaml"
    Read-RepoFile "gitops/infrastructure/notifications/externalsecret.yaml"
    Read-RepoFile "gitops/infrastructure/notifications/provider.yaml"
    Read-RepoFile "gitops/infrastructure/notifications/alert.yaml"
) -join [Environment]::NewLine
$discordWebhookUrlPattern = '(?i)https?://(?:www\.)?discord(?:app)?\.com/api/webhooks/[^\s''"<>]+'

Assert-Matches $notifications "(?m)^kind: ExternalSecret\r?$" "Flux Discord credential must come from ExternalSecret"
Assert-Matches $notifications "(?m)^\s+key: DISCORD_ALERTS_WEBHOOK_URL\r?$" "Flux must use the approved OCI Vault key"
Assert-Matches $notifications "(?m)^kind: Provider\r?$" "Flux Discord Provider must exist"
Assert-Matches $notifications "(?m)^\s+type: discord\r?$" "Flux Provider must use Discord"
Assert-Matches $notifications "(?m)^kind: Alert\r?$" "Flux Alert must exist"
Assert-Matches $notifications "(?m)^\s+eventSeverity: error\r?$" "Only actionable Flux errors should notify"
Assert-Matches $notifications "(?ms)eventSources:.*?kind: Kustomization.*?kind: HelmRelease" "Both reconciliation kinds must be covered"
Assert-NoMatches $notifications $discordWebhookUrlPattern "Rendered notifications must not contain a Discord webhook URL"
Assert-NoMatches $notificationSource $discordWebhookUrlPattern "Notification source must not contain a Discord webhook URL"
Assert-Matches $egressPolicy "(?m)^  endpointSelector:\r?$" "notification-controller Cilium policy must define endpointSelector"
Assert-Matches $egressPolicy "(?m)^    matchLabels:\r?$" "notification-controller endpointSelector must use matchLabels"
Assert-Matches $egressPolicy "(?m)^      app: notification-controller\r?$" "notification-controller Cilium policy must select app=notification-controller"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- toEndpoints:\r?$" 1 "egress must have exactly one DNS endpoint rule family"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- toEntities:\r?$" 1 "egress must have exactly one Kubernetes API entity rule family"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- toFQDNs:\r?$" 1 "egress must have exactly one Discord FQDN rule family"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- to(?:Endpoints|Entities|FQDNs):\r?$" 3 "egress must contain exactly the intended rule families"
Assert-NoMatches $egressPolicy "(?m)^[ \t]+- to(?!Endpoints:|Entities:|FQDNs:|Ports:)[A-Za-z]+:\r?$" "egress must not contain another destination rule family"
if ($apiEgressPolicy.Length -eq 0) {
    throw "[ASSERT] notification-controller needs a Kubernetes API egress block"
}
Assert-MatchCount $egressPolicy "(?m)^[ \t]+k8s-app: kube-dns\r?$" 1 "DNS egress must target kube-dns"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+k8s:io\.kubernetes\.pod\.namespace: kube-system\r?$" 1 "DNS egress must target kube-system"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- kube-apiserver\r?$" 1 "API egress must target kube-apiserver"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+- matchName: discord\.com\r?$" 1 "Discord egress must target discord.com"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+toPorts:\r?$" 3 "each egress rule family must define one port rule"
Assert-MatchCount $egressPolicy '(?m)^[ \t]+- port: "53"\r?$' 2 "DNS egress must expose UDP and TCP port 53 only"
Assert-MatchCount $apiEgressPolicy '(?m)^      - port: "443"$' 1 "notification API egress must preserve the Service port"
Assert-MatchCount $apiEgressPolicy '(?m)^      - port: "6443"$' 1 "notification API egress must allow the observed OKE backend port"
Assert-MatchCount $apiEgressPolicy '(?m)^        protocol: TCP$' 2 "notification API egress must contain only two TCP ports"
Assert-MatchCount $egressPolicy '(?m)^      - port: "443"$' 2 "API Service and Discord egress must expose TCP port 443"
Assert-MatchCount $egressPolicy '(?m)^      - port: "6443"$' 1 "API backend egress must expose TCP port 6443 once"
Assert-MatchCount $egressPolicy '(?m)^\s+- port: "[^"]+"$' 5 "egress must contain exactly five intended port entries"
Assert-MatchCount $egressPolicy "(?m)^[ \t]+protocol: UDP\r?$" 1 "DNS egress must contain exactly one UDP port"
Assert-MatchCount $egressPolicy "(?m)^\s+protocol: TCP$" 4 "egress must contain exactly four TCP ports"
Assert-Matches $fluxBootstrap "(?ms)kind: NetworkPolicy.*?name: allow-egress.*?podSelector:\s+matchExpressions:.*?operator: NotIn.*?notification-controller" "notification-controller must leave broad egress"
Assert-Matches $infrastructure "(?m)^\s+name: infra-notifications\r?$" "notifications need a Flux Kustomization"
Assert-Matches $infrastructure "(?ms)name: infra-notifications.*?dependsOn:.*?name: infra-flagger.*?name: security-external-secrets" "notifications must wait for prerequisites"

Write-Host "Flux notification contracts passed"
