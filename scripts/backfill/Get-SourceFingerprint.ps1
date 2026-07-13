[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$Uri,
    [switch]$AllowLoopbackHttpForTests
)

$ErrorActionPreference = 'Stop'
$maxBytes = 5MB
$maxRedirects = 3

function Test-AllowedScheme {
    param([Parameter(Mandatory)][uri]$Candidate)

    if ($Candidate.Scheme -eq 'https') {
        return $true
    }
    return $AllowLoopbackHttpForTests -and
        $Candidate.Scheme -eq 'http' -and
        $Candidate.IsLoopback
}

function Assert-SafeUri {
    param([Parameter(Mandatory)][uri]$Candidate)

    if (-not (Test-AllowedScheme -Candidate $Candidate)) {
        throw 'HTTPS is required'
    }
    if ([string]::IsNullOrWhiteSpace($Candidate.DnsSafeHost)) {
        throw 'URI host is required'
    }
    if ($null -ne $Candidate.UserInfo -and $Candidate.UserInfo.Length -gt 0) {
        throw 'URI credentials are prohibited'
    }
}

Assert-SafeUri -Candidate $Uri
$originHost = $Uri.DnsSafeHost.ToLowerInvariant()
$originPort = $Uri.Port
$current = $Uri
$redirects = 0

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
$response = $null
$stream = $null
$hasher = $null

try {
    while ($true) {
        Assert-SafeUri -Candidate $current
        if ($current.DnsSafeHost.ToLowerInvariant() -ne $originHost -or
            $current.Port -ne $originPort) {
            throw 'Redirect host change is prohibited'
        }

        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            $current
        )
        $request.Headers.UserAgent.ParseAdd('tracker-reference-fingerprint/1.0')
        try {
            $response = $client.SendAsync(
                $request,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
            ).GetAwaiter().GetResult()
        }
        finally {
            $request.Dispose()
        }

        $status = [int]$response.StatusCode
        if ($status -ge 300 -and $status -lt 400) {
            if ($redirects -ge $maxRedirects) {
                throw 'Redirect limit exceeded'
            }
            $location = $response.Headers.Location
            if ($null -eq $location) {
                throw 'Redirect response has no Location header'
            }
            $next = if ($location.IsAbsoluteUri) {
                $location
            }
            else {
                [uri]::new($current, $location)
            }
            Assert-SafeUri -Candidate $next
            if ($next.DnsSafeHost.ToLowerInvariant() -ne $originHost -or
                $next.Port -ne $originPort) {
                throw 'Redirect host change is prohibited'
            }
            $response.Dispose()
            $response = $null
            $current = $next
            $redirects++
            continue
        }

        if ($status -lt 200 -or $status -ge 300) {
            throw "Unexpected HTTP status $status"
        }
        break
    }

    $contentLength = $response.Content.Headers.ContentLength
    if ($null -ne $contentLength -and $contentLength -gt $maxBytes) {
        throw 'Response exceeds 5 MiB'
    }

    $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    $buffer = New-Object byte[] 65536
    [long]$byteCount = 0

    while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
        $byteCount += $read
        if ($byteCount -gt $maxBytes) {
            throw 'Response exceeds 5 MiB'
        }
        [void]$hasher.TransformBlock($buffer, 0, $read, $buffer, 0)
    }
    [void]$hasher.TransformFinalBlock((New-Object byte[] 0), 0, 0)
    $hash = -join @($hasher.Hash | ForEach-Object { $_.ToString('x2') })

    $contentType = $null
    if ($null -ne $response.Content.Headers.ContentType) {
        $contentType = $response.Content.Headers.ContentType.MediaType
    }
    $etag = $null
    if ($null -ne $response.Headers.ETag) {
        $etag = $response.Headers.ETag.ToString()
    }
    $lastModified = $null
    if ($null -ne $response.Content.Headers.LastModified) {
        $lastModified = $response.Content.Headers.LastModified.UtcDateTime.ToString('R')
    }

    [ordered]@{
        url = $current.AbsoluteUri
        accessedOn = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
        contentSha256 = $hash
        byteCount = $byteCount
        contentType = $contentType
        etag = $etag
        lastModified = $lastModified
    } | ConvertTo-Json -Compress
}
finally {
    if ($null -ne $hasher) {
        $hasher.Dispose()
    }
    if ($null -ne $stream) {
        $stream.Dispose()
    }
    if ($null -ne $response) {
        $response.Dispose()
    }
    $client.Dispose()
    $handler.Dispose()
}
