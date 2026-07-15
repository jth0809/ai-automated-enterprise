# Deterministic manifest verifier for the tracker collection egress policy.
# Asserts: every active source_domain host appears exactly once as an exact
# matchName, no unexpected external FQDN exists, HTTP (port 80) never appears,
# the tracker stays disabled in the PR-1 state, extractor bounds are present,
# and no secret value leaks into the manifests.
# PowerShell 5.1 compatible. Exit 0 = pass, exit 1 = fail with reasons.

param(
    [string]$NetworkPolicy = (Join-Path $PSScriptRoot '..\network-policy.yaml'),
    [string]$Deployment = (Join-Path $PSScriptRoot '..\deployment.yaml')
)

$failures = @()

# The active source_domain deployment set (V3+V4 seeds): 12 feed hosts plus
# the extra body hosts science.nasa.gov, arxiv.org, export.arxiv.org,
# arstechnica.com. Keep in sync with the Flyway seeds.
$expected = @(
    'www.nasa.gov', 'science.nasa.gov', 'www.esa.int', 'global.jaxa.jp',
    'rss.arxiv.org', 'arxiv.org', 'export.arxiv.org',
    'spacenews.com', 'www.nasaspaceflight.com', 'spaceflightnow.com',
    'www.planetary.org', 'phys.org', 'www.space.com',
    'feeds.arstechnica.com', 'arstechnica.com', 'www.universetoday.com'
)

# Non-source-registry egress FQDNs that legitimately live in the same policy.
# LL2 is a Layer B metric API, not an article/source_domain feed.
$ll2Host = 'll.thespacedevs.com'
$allowedOther = @(
    'news.google.com', 'api.anthropic.com',
    'adb.ap-osaka-1.oraclecloud.com', $ll2Host
)

$yaml = Get-Content -Raw $NetworkPolicy

foreach ($hostName in $expected) {
    $pattern = '(?m)^\s*-\s*matchName:\s*' + [regex]::Escape($hostName) + '\s*$'
    $count = [regex]::Matches($yaml, $pattern).Count
    if ($count -ne 1) {
        $failures += "network-policy: expected exactly one matchName for '$hostName', found $count"
    }
}

$ll2Pattern = '(?m)^\s*-\s*matchName:\s*' + [regex]::Escape($ll2Host) + '\s*$'
$ll2Count = [regex]::Matches($yaml, $ll2Pattern).Count
if ($ll2Count -ne 1) {
    $failures += "network-policy: expected exactly one LL2 matchName for '$ll2Host', found $ll2Count"
}
$ll2HttpsRule = '(?ms)^\s{4}-\s*toFQDNs:\s*\r?\n'
$ll2HttpsRule += '\s{8}-\s*matchName:\s*' + [regex]::Escape($ll2Host) + '\s*\r?\n'
$ll2HttpsRule += '\s{6}toPorts:\s*\r?\n\s{8}-\s*ports:\s*\r?\n'
$ll2HttpsRule += '\s{12}-\s*port:\s*"443"\s*\r?\n\s{14}protocol:\s*TCP\s*$'
if ($yaml -notmatch $ll2HttpsRule) {
    $failures += 'network-policy: LL2 exact host must be bound only to TCP 443'
}

$allHosts = [regex]::Matches($yaml, '(?m)^\s*-\s*matchName:\s*(\S+)\s*$') |
    ForEach-Object { $_.Groups[1].Value }
foreach ($found in $allHosts) {
    if (($expected -notcontains $found) -and ($allowedOther -notcontains $found)) {
        $failures += "network-policy: unexpected egress host '$found'"
    }
}

if ($yaml -match '"80"') {
    $failures += 'network-policy: plain HTTP port 80 must never be allowed'
}

$deploy = Get-Content -Raw $Deployment

if ($deploy -notmatch '(?s)name:\s*TRACKER_ENABLED\s*\r?\n\s*value:\s*"false"') {
    $failures += 'deployment: TRACKER_ENABLED must remain "false" in the PR-1 state'
}
foreach ($envName in @('TRACKER_EXTRACT_CRON', 'TRACKER_EXTRACT_BATCH_SIZE', 'TRACKER_FLUKE_ENABLED')) {
    if ($deploy -notmatch ('name:\s*' + [regex]::Escape($envName))) {
        $failures += "deployment: missing extractor configuration env '$envName'"
    }
}
if ($deploy -notmatch '(?s)name:\s*TRACKER_FLUKE_ENABLED\s*\r?\n\s*value:\s*"false"') {
    $failures += 'deployment: TRACKER_FLUKE_ENABLED must default to "false"'
}
foreach ($envName in @('TRACKER_LL2_ENABLED', 'TRACKER_LL2_CRON', 'TRACKER_LL2_MAX_PAGES')) {
    if ($deploy -notmatch ('name:\s*' + [regex]::Escape($envName))) {
        $failures += "deployment: missing bounded LL2 configuration env '$envName'"
    }
}
if ($deploy -notmatch '(?s)name:\s*TRACKER_LL2_ENABLED\s*\r?\n\s*value:\s*"false"') {
    $failures += 'deployment: TRACKER_LL2_ENABLED must default to "false"'
}
if ($deploy -notmatch '(?s)name:\s*TRACKER_LL2_MAX_PAGES\s*\r?\n\s*value:\s*"10"') {
    $failures += 'deployment: TRACKER_LL2_MAX_PAGES must stay bounded at 10'
}
if ($deploy -notmatch '(?s)name:\s*TRACKER_LL2_CRON\s*\r?\n\s*value:\s*"0 17 3 8 \* \*"') {
    $failures += 'deployment: TRACKER_LL2_CRON must remain the monthly UTC schedule'
}

# WP3.3 reuses the immutable local reference resource and the already-approved
# LL2 path. Its jobs must ship dark, with versioned scenario values explicit in
# GitOps and no transport-specific egress or secret.
$transportDefaults = @{
    TRACKER_TRANSPORT_ECONOMICS_ENABLED = 'false'
    TRACKER_TRANSPORT_PROJECTION_CRON = '0 47 3 8 * *'
    TRACKER_COHERENCE_CRON = '0 0 3 1 */3 *'
    TRACKER_TRANSPORT_TARGET_USD_PER_KG = '200'
    TRACKER_TRANSPORT_TARGET_EASY_USD_PER_KG = '500'
    TRACKER_TRANSPORT_TARGET_HARD_USD_PER_KG = '100'
}
foreach ($entry in $transportDefaults.GetEnumerator()) {
    $namePattern = '(?m)^\s*-\s*name:\s*' + [regex]::Escape($entry.Key) + '\s*$'
    $count = [regex]::Matches($deploy, $namePattern).Count
    if ($count -ne 1) {
        $failures += "deployment: expected exactly one '$($entry.Key)', found $count"
        continue
    }
    $pairPattern = '(?s)-\s*name:\s*' + [regex]::Escape($entry.Key)
    $pairPattern += '\s*\r?\n\s*value:\s*"' + [regex]::Escape($entry.Value) + '"'
    if ($deploy -notmatch $pairPattern) {
        $failures += "deployment: '$($entry.Key)' must equal '$($entry.Value)'"
    }
}

foreach ($file in @($NetworkPolicy, $Deployment)) {
    $text = Get-Content -Raw $file
    if ($text -match 'sk-ant-|x-api-key\s*:\s*\S|(?i)token\s*:\s*[A-Za-z0-9+/]{16,}') {
        $failures += "$(Split-Path -Leaf $file): possible secret material committed"
    }
    if ($text -match 'name:\s*TRACKER_FEEDS') {
        $failures += "$(Split-Path -Leaf $file): TRACKER_FEEDS belongs in the vault-backed secret, not a manifest env value"
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}
Write-Output 'OK: tracker egress policy and safe defaults verified'
exit 0
