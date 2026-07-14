[CmdletBinding()]
param(
    [string]$MavenPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backendRoot = Join-Path $repoRoot 'apps\backend\springboot-app'
$corpusPath = Join-Path $backendRoot 'src\main\resources\tracker\historical-candidates-v1.jsonl'
$catalogPath = Join-Path $backendRoot 'src\main\resources\tracker\historical-source-catalog-v1.json'
$localRepository = Join-Path $HOME '.m2\repository'

function Resolve-MavenPath {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (-not (Test-Path -LiteralPath $ExplicitPath -PathType Leaf)) {
            throw "Maven executable does not exist: $ExplicitPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    if (-not [string]::IsNullOrWhiteSpace($env:TRACKER_MAVEN)) {
        if (-not (Test-Path -LiteralPath $env:TRACKER_MAVEN -PathType Leaf)) {
            throw "TRACKER_MAVEN does not exist: $env:TRACKER_MAVEN"
        }
        return (Resolve-Path -LiteralPath $env:TRACKER_MAVEN).Path
    }

    $command = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command) {
        return $command.Source
    }

    $wrapperCache = Join-Path $HOME '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $wrapperCache -PathType Container) {
        $cached = Get-ChildItem -LiteralPath $wrapperCache -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($null -ne $cached) {
            return $cached.FullName
        }
    }

    $wrapper = Join-Path $backendRoot 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        return $wrapper
    }
    throw 'Cannot locate Maven. Pass -MavenPath or set TRACKER_MAVEN.'
}

$maven = Resolve-MavenPath -ExplicitPath $MavenPath
Push-Location $backendRoot
try {
    & $maven -o "-Dmaven.repo.local=$localRepository" test '-Dtest=HistoricalCorpusValidatorTest,HistoricalProductionCorpusTest'
    if ($LASTEXITCODE -ne 0) {
        throw "Historical corpus Maven validation failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$records = @()
$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $corpusPath -Encoding UTF8) {
    $lineNumber++
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    try {
        $records += $line | ConvertFrom-Json
    }
    catch {
        throw "Cannot parse corpus line $lineNumber after Java validation"
    }
}

$catalogRaw = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8
$catalogParsed = $catalogRaw | ConvertFrom-Json
$catalogCount = if ($null -eq $catalogParsed) { 0 } else { @($catalogParsed).Count }
$ready = @($records | Where-Object discoveryStatus -eq 'READY_FOR_MAPPING').Count
$rejected = @($records | Where-Object discoveryStatus -eq 'REJECTED').Count
$discovered = @($records | Where-Object discoveryStatus -eq 'DISCOVERED').Count

$report = [ordered]@{
    total = $records.Count
    ready = $ready
    discovered = $discovered
    rejected = $rejected
    sourceCatalog = $catalogCount
    errors = 0
}
$report | ConvertTo-Json -Compress

if ($records.Count -lt 180 -or $records.Count -gt 250) {
    Write-Warning "Research target not met: expected 180-250 candidates, found $($records.Count)."
}
