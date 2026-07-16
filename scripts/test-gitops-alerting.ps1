Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$tests = @(
    "test-flux-notifications.ps1",
    "test-flagger-alerting.ps1",
    "test-runtime-alerting.ps1",
    "test-alerting-docs-and-ci.ps1"
)

foreach ($test in $tests) {
    & (Join-Path $PSScriptRoot $test)
}

Write-Host "All GitOps alerting contracts passed"
