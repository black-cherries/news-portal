# Shared helpers for the publisher scripts: sign in to the admin API with a
# session cookie and send the CSRF header. Dot-source this file.

$script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Get-CsrfToken([string]$BackendUrl) {
    $c = $script:Session.Cookies.GetCookies($BackendUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' -and $_.Value }
    if (-not $c) {
        $null = Invoke-WebRequest "$BackendUrl/api/v1/auth/csrf" -WebSession $script:Session -UseBasicParsing
        $c = $script:Session.Cookies.GetCookies($BackendUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' -and $_.Value }
    }
    return $c.Value
}

function Invoke-Api([string]$BackendUrl, [string]$Method, [string]$Path, $Body = $null) {
    $params = @{
        Uri             = "$BackendUrl/api/v1$Path"
        Method          = $Method
        WebSession      = $script:Session
        UseBasicParsing = $true
        Headers         = @{ Accept = 'application/json' }
    }
    if ($Method -ne 'GET') {
        $params.Headers['X-XSRF-TOKEN'] = Get-CsrfToken $BackendUrl
        $json = if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    $response = Invoke-WebRequest @params
    if ($response.Content) {
        # Decode as UTF-8 whatever PowerShell guessed, so Hindi, Urdu and Arabic survive.
        $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        return $text | ConvertFrom-Json
    }
}

function Connect-Staff([string]$BackendUrl, [string]$Email, [string]$Password, [string]$RepoRoot) {
    if (-not $Email) {
        $credFile = Join-Path $RepoRoot 'data\dev-accounts.txt'
        if (-not (Test-Path $credFile)) {
            throw "No -Email/-Password given and $credFile not found. Pass the staff account to use."
        }
        $creds = Get-Content $credFile | Where-Object { $_ -match '=' } | ConvertFrom-StringData
        $Email = $creds.AdminEmail
        $Password = $creds.AdminPassword
    }
    try {
        Invoke-WebRequest "$BackendUrl/api/v1/health" -UseBasicParsing -TimeoutSec 5 | Out-Null
    } catch {
        throw "The backend is not reachable at $BackendUrl. Start it first."
    }
    $null = Invoke-Api $BackendUrl 'POST' '/auth/login' @{ email = $Email; password = $Password }
    return $Email
}

function Read-Candidates([string]$Path) {
    # ForEach-Object unrolls the array (PowerShell 5.1 returns it as one object).
    Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json | ForEach-Object { $_ }
}
