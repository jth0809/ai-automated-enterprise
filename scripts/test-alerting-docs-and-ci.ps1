. (Join-Path $PSScriptRoot "gitops-test-helpers.ps1")

$workflow = Read-RepoFile ".github/workflows/security-scan-iac.yaml"
$requiredSecrets = Read-RepoFile "REQUIRED_SECRETS.md"
$backlog = Read-RepoFile "BACKLOG_BY_DOMAIN.md"
$runbook = Read-RepoFile "docs/runbooks/discord-alerting.md"
$design = Read-RepoFile "docs/superpowers/specs/2026-07-10-discord-alerting-design.md"

Assert-Matches $workflow "pwsh -NoProfile -File scripts/test-gitops-alerting\.ps1" "IaC CI must execute alert contracts"
Assert-MatchCount $workflow 'scripts/\*\*\.ps1' 2 "PowerShell contract changes must trigger push and pull-request CI"
$contractPaths = @(
    ".github/workflows/security-scan-iac.yaml",
    "docs/runbooks/discord-alerting.md",
    "docs/superpowers/specs/2026-07-10-discord-alerting-design.md",
    "REQUIRED_SECRETS.md",
    "BACKLOG_BY_DOMAIN.md"
)
foreach ($contractPath in $contractPaths) {
    Assert-MatchCount $workflow ([regex]::Escape($contractPath)) 2 "$contractPath changes must trigger push and pull-request CI"
}
Assert-Matches $requiredSecrets "DISCORD_ALERTS_WEBHOOK_URL" "operators need the exact Vault key"
Assert-Matches $requiredSecrets "(?is)DISCORD_ALERTS_WEBHOOK_URL.*incoming Webhook.*Bot" "the credential type must not be confused with a Bot token"
Assert-Matches $requiredSecrets 'gitops/infrastructure/\*\*' "the Vault inventory scope must include infrastructure ExternalSecrets"
Assert-Matches $runbook "DISCORD_ALERTS_WEBHOOK_URL" "runbook must document the Vault key"
Assert-Matches $runbook "(?is)Vault.*base URL.*?/github" "Vault must contain the base URL without GitHub's suffix"
Assert-Matches $runbook "application/json" "GitHub Webhook content type must be explicit"
Assert-Matches $runbook "(?is)check_run.*check_suite" "GitHub integration must select Discord-supported check events"
Assert-Matches $runbook "(?is)workflow_run.*dependabot_alert.*not supported" "runbook must disclose unsupported GitHub events"
$selectedEventList = [regex]::Match($runbook, '(?ms)^4\..*?(?=^`workflow_run`)').Value
if ($selectedEventList.Length -eq 0) {
    throw "[ASSERT] GitHub supported-event selection list must be present"
}
Assert-Matches $selectedEventList '(?m)^\s+- `check_run`, `check_suite`' "check events must remain selected"
Assert-Matches $selectedEventList '(?m)^\s+- `issues`, `issue_comment`' "issue events must remain selected"
Assert-Matches $selectedEventList '(?m)^\s+- `pull_request`, `pull_request_review`, `pull_request_review_comment`' "pull-request events must remain selected"
Assert-Matches $selectedEventList '(?m)^\s+- `release`' "release events must remain selected"
Assert-NoMatches $selectedEventList 'workflow_run|dependabot_alert' "unsupported events must not be selected"
Assert-Matches $runbook "flux-system/flux-discord-webhook" "runbook must verify the Flux ExternalSecret"
Assert-Matches $runbook "backend/flagger-discord-webhook" "runbook must verify the Flagger ExternalSecret"
Assert-Matches $runbook "monitoring/alertmanager-discord-webhook" "runbook must verify the Alertmanager ExternalSecret"
Assert-Matches $runbook "(?is)rotation.*Vault" "runbook must document safe rotation"
Assert-Matches $backlog "(?is)GitOps implementation complete.*Vault provisioning.*runtime delivery verification pending" "implemented alerting must remain operationally pending"
Assert-Matches $backlog "(?is)GitHub.*Discord.*external setting.*pending" "GitHub to Discord must stay pending"
Assert-Matches $backlog "(?is)dependabot_alert.*deferred" "unsupported direct Dependabot alerts must remain deferred"
Assert-Matches $backlog "(?is)SMTP.*deferred|email.*deferred" "email routing must remain deferred"
Assert-Matches $design "(?is)GitOps implementation complete.*runtime delivery verification pending" "design status must distinguish code from runtime verification"

$trackedText = $workflow + $requiredSecrets + $backlog + $runbook + $design
Assert-NoMatches $trackedText "https://discord\.com/api/webhooks/[0-9]" "No real Webhook URL may be tracked"

Write-Host "Alerting documentation and CI contracts passed"
