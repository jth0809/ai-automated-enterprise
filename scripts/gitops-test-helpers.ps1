Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Read-RepoFile {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "[MISSING] $RelativePath"
    }
    Get-Content -Raw -Encoding UTF8 -LiteralPath $path
}

function Build-Kustomization {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "[MISSING] $RelativePath"
    }
    $rendered = & kubectl kustomize $path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("[KUSTOMIZE] " + $RelativePath + [Environment]::NewLine + ($rendered -join [Environment]::NewLine))
    }
    $rendered -join [Environment]::NewLine
}

function Assert-Matches {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Because
    )
    if ($Actual -notmatch $Pattern) {
        throw "[ASSERT] $Because (pattern: $Pattern)"
    }
}

function Assert-NoMatches {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Because
    )
    if ($Actual -match $Pattern) {
        throw "[ASSERT] $Because (forbidden pattern: $Pattern)"
    }
}
