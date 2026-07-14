[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$tool = Join-Path $PSScriptRoot 'Get-SourceFingerprint.ps1'

if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
    throw "Fingerprint tool does not exist: $tool"
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-ExpectedFailure {
    param(
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)][string]$MessageFragment
    )
    $failed = $false
    try {
        & $Action
    }
    catch {
        $failed = $true
        if (-not $_.Exception.Message.Contains($MessageFragment)) {
            throw "Expected failure containing '$MessageFragment', got '$($_.Exception.Message)'"
        }
    }
    if (-not $failed) {
        throw "Expected failure containing '$MessageFragment', but the command succeeded"
    }
}

$portProbe = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
)
$portProbe.Start()
$port = ([System.Net.IPEndPoint]$portProbe.LocalEndpoint).Port
$portProbe.Stop()

$payloadText = 'reference-payload!'
$payload = [System.Text.Encoding]::UTF8.GetBytes($payloadText)
Assert-True ($payload.Length -eq 18) 'Test payload must remain exactly 18 bytes'

$server = Start-Job -ScriptBlock {
    param([int]$Port, [byte[]]$Payload)

    $ErrorActionPreference = 'Stop'
    $listener = [System.Net.HttpListener]::new()
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    Write-Output 'READY'

    try {
        while ($listener.IsListening) {
            $context = $listener.GetContext()
            $path = $context.Request.Url.AbsolutePath
            try {
                switch ($path) {
                    '/source' {
                        $context.Response.StatusCode = 200
                        $context.Response.ContentType = 'text/plain; charset=utf-8'
                        $context.Response.ContentLength64 = $Payload.Length
                        $context.Response.Headers['ETag'] = '"fixture-v1"'
                        $context.Response.Headers['Last-Modified'] = 'Mon, 13 Jul 2026 00:00:00 GMT'
                        $context.Response.OutputStream.Write($Payload, 0, $Payload.Length)
                    }
                    '/redirect-1' {
                        $context.Response.StatusCode = 302
                        $context.Response.RedirectLocation = "/redirect-2"
                    }
                    '/redirect-2' {
                        $context.Response.StatusCode = 302
                        $context.Response.RedirectLocation = "/redirect-3"
                    }
                    '/redirect-3' {
                        $context.Response.StatusCode = 302
                        $context.Response.RedirectLocation = "/redirect-4"
                    }
                    '/redirect-4' {
                        $context.Response.StatusCode = 302
                        $context.Response.RedirectLocation = "/source"
                    }
                    '/cross-host' {
                        $context.Response.StatusCode = 302
                        $context.Response.RedirectLocation = "http://localhost:$Port/source"
                    }
                    '/large' {
                        $large = [byte[]]::new(5MB + 1)
                        $context.Response.StatusCode = 200
                        $context.Response.ContentType = 'application/octet-stream'
                        $context.Response.ContentLength64 = $large.Length
                        $context.Response.OutputStream.Write($large, 0, $large.Length)
                    }
                    default {
                        $context.Response.StatusCode = 404
                    }
                }
            }
            catch [System.IO.IOException] {
                # Expected when the client aborts the deliberately oversized response.
            }
            finally {
                $context.Response.Close()
            }
        }
    }
    finally {
        $listener.Close()
    }
} -ArgumentList $port, $payload

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("tracker-fingerprint-test-" + [guid]::NewGuid())
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
[void](New-Item -ItemType Directory -Path $tempRoot)
$env:TEMP = $tempRoot
$env:TMP = $tempRoot

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 100 -and -not $ready; $attempt++) {
        $ready = @((Receive-Job -Job $server -Keep -ErrorAction SilentlyContinue)) -contains 'READY'
        if (-not $ready) {
            if ($server.State -in @('Failed', 'Stopped', 'Completed')) {
                throw "Loopback server stopped before readiness: $($server.State)"
            }
            Start-Sleep -Milliseconds 50
        }
    }
    Assert-True $ready 'Loopback server did not become ready'

    $sourceUri = "http://127.0.0.1:$port/source"
    $jsonText = ((& $tool -Uri $sourceUri -AllowLoopbackHttpForTests) | Out-String).Trim()
    $metadata = $jsonText | ConvertFrom-Json
    $expectedHasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $expectedHash = -join @(
            $expectedHasher.ComputeHash($payload) | ForEach-Object { $_.ToString('x2') }
        )
    }
    finally {
        $expectedHasher.Dispose()
    }

    Assert-True ($metadata.url -eq $sourceUri) 'Final URL mismatch'
    Assert-True ($metadata.byteCount -eq 18) 'Byte count mismatch'
    Assert-True ($metadata.contentSha256 -eq $expectedHash) 'SHA-256 mismatch'
    Assert-True ($metadata.contentType -eq 'text/plain') 'Content-Type mismatch'
    Assert-True (-not $jsonText.Contains($payloadText)) 'Response body leaked into tool output'

    Assert-ExpectedFailure {
        & $tool -Uri $sourceUri
    } 'HTTPS is required'

    Assert-ExpectedFailure {
        & $tool -Uri 'http://example.com/source' -AllowLoopbackHttpForTests
    } 'HTTPS is required'

    Assert-ExpectedFailure {
        & $tool -Uri "http://127.0.0.1:$port/redirect-1" -AllowLoopbackHttpForTests
    } 'Redirect limit exceeded'

    Assert-ExpectedFailure {
        & $tool -Uri "http://127.0.0.1:$port/cross-host" -AllowLoopbackHttpForTests
    } 'Redirect host change is prohibited'

    Assert-ExpectedFailure {
        & $tool -Uri "http://127.0.0.1:$port/large" -AllowLoopbackHttpForTests
    } 'Response exceeds 5 MiB'

    $tempFiles = @(Get-ChildItem -LiteralPath $tempRoot -Force -Recurse -File)
    Assert-True ($tempFiles.Count -eq 0) 'Fingerprint tool wrote response data to a temporary file'

    Write-Output 'Source fingerprint tests: PASS'
}
finally {
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
    Stop-Job -Job $server -ErrorAction SilentlyContinue
    Remove-Job -Job $server -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
