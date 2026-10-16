<#
.SYNOPSIS
  Loads fictional demo publishers and articles into the local development
  database, using the real admin API and feed ingestion.

.DESCRIPTION
  1. Serves the sample feeds in scripts/dev-data/feeds on a local port.
  2. Starts the backend (dev profile) with a bootstrap dev administrator.
  3. Through the admin API: creates four demo publishers (en, hi, ur, ar)
     with different rights, activates them, adds their feeds and runs each
     feed once. Feeds are then disabled, because the sample server stops.
  4. Creates a verified dev reader account.
  5. Stops the backend and the feed server.

  Safe to run again: existing publishers and accounts are reused.
  Development only. Credentials are written to data/dev-accounts.txt
  (git-ignored). All content is fictional and uses the reserved .invalid
  domain.

.EXAMPLE
  .\scripts\dev-data\seed-dev.ps1
#>
param(
    [string]$BackendUrl = 'http://127.0.0.1:8081',
    [int]$FeedPort = 8099
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dataDir = Join-Path $repo 'data'
$credFile = Join-Path $dataDir 'dev-accounts.txt'
$outboxDir = Join-Path $repo '.mail-outbox'
$feedDir = Join-Path $PSScriptRoot 'feeds'
$logFile = Join-Path $env:TEMP 'newsportal-seed-backend.log'

function New-Secret {
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $bytes = New-Object byte[] 20
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

foreach ($port in @(([uri]$BackendUrl).Port, $FeedPort)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop the backend (or whatever uses the port) and run this script again."
    }
}

# --- dev credentials ---
New-Item -ItemType Directory -Force $dataDir | Out-Null
if (Test-Path $credFile) {
    $creds = Get-Content $credFile | Where-Object { $_ -match '=' } | ConvertFrom-StringData
} else {
    $creds = @{
        AdminEmail     = 'dev-admin@example.org'
        AdminPassword  = New-Secret
        ReaderEmail    = 'dev-reader@example.org'
        ReaderPassword = New-Secret
    }
    $lines = @('# Local development accounts created by scripts/dev-data/seed-dev.ps1. Do not use elsewhere.')
    foreach ($k in 'AdminEmail', 'AdminPassword', 'ReaderEmail', 'ReaderPassword') { $lines += "$k=$($creds[$k])" }
    Set-Content -Path $credFile -Value $lines -Encoding ascii
}

$publishers = Get-Content (Join-Path $PSScriptRoot 'publishers.json') -Raw -Encoding UTF8 | ConvertFrom-Json

# --- sample feed server (plain TCP, no admin rights or URL reservations needed) ---
$feedServer = Start-Job -ArgumentList $feedDir, $FeedPort -ScriptBlock {
    param($dir, $port)
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $port)
    $listener.Start()
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
            $requestLine = $reader.ReadLine()
            while ($true) { $line = $reader.ReadLine(); if ([string]::IsNullOrEmpty($line)) { break } }
            $name = [System.IO.Path]::GetFileName((($requestLine -split ' ')[1] -split '\?')[0])
            $file = Join-Path $dir $name
            if ($name -match '^[a-z]{2}\.xml$' -and (Test-Path $file)) {
                $body = [System.IO.File]::ReadAllBytes($file); $status = '200 OK'; $type = 'application/rss+xml; charset=utf-8'
            } else {
                $body = [System.Text.Encoding]::ASCII.GetBytes('not found'); $status = '404 Not Found'; $type = 'text/plain'
            }
            $head = [System.Text.Encoding]::ASCII.GetBytes(
                "HTTP/1.1 $status`r`nContent-Type: $type`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($head, 0, $head.Length)
            $stream.Write($body, 0, $body.Length)
            $stream.Flush()
        } catch {
            # Ignore a broken client; keep serving.
        } finally {
            $client.Close()
        }
    }
}

# --- API helper: session cookie + CSRF header ---
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Get-CsrfToken {
    $c = $session.Cookies.GetCookies($BackendUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' -and $_.Value }
    if (-not $c) {
        $null = Invoke-WebRequest "$BackendUrl/api/v1/auth/csrf" -WebSession $session -UseBasicParsing
        $c = $session.Cookies.GetCookies($BackendUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' -and $_.Value }
    }
    return $c.Value
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null) {
    $params = @{
        Uri             = "$BackendUrl/api/v1$Path"
        Method          = $Method
        WebSession      = $session
        UseBasicParsing = $true
        Headers         = @{ Accept = 'application/json' }
    }
    if ($Method -ne 'GET') {
        $params.Headers['X-XSRF-TOKEN'] = Get-CsrfToken
        $json = if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    $response = Invoke-WebRequest @params
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}

function Get-StatusCode($err) {
    if ($err.Exception.Response) { return [int]$err.Exception.Response.StatusCode }
    return 0
}

function Wait-ForToken([string]$email) {
    for ($i = 0; $i -lt 30; $i++) {
        $files = Get-ChildItem $outboxDir -Filter *.txt -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
        foreach ($f in $files) {
            $text = Get-Content $f.FullName -Raw
            if ($text -match "To: $([regex]::Escape($email))" -and $text -match 'verify-email#token=([A-Za-z0-9_-]+)') {
                return $Matches[1]
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "No verification email for $email found in $outboxDir"
}

$backend = $null
try {
    # --- start backend with the bootstrap admin (only this process sees the variables) ---
    Write-Host 'Starting backend...'
    $env:NEWSPORTAL_BOOTSTRAP_ADMIN_EMAIL = $creds.AdminEmail
    $env:NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD = $creds.AdminPassword
    $env:INGESTION_SCHEDULER_ENABLED = 'false'
    $backend = Start-Process (Join-Path $repo 'backend\mvnw.cmd') -WorkingDirectory (Join-Path $repo 'backend') `
        -ArgumentList '-B', '-q', 'spring-boot:run' -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" `
        -PassThru -WindowStyle Hidden
    Remove-Item Env:\NEWSPORTAL_BOOTSTRAP_ADMIN_EMAIL, Env:\NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD, Env:\INGESTION_SCHEDULER_ENABLED

    $up = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 2
        try { $null = Invoke-WebRequest "$BackendUrl/api/v1/health" -UseBasicParsing; $up = $true; break } catch { }
        if ($backend.HasExited) { break }
    }
    if (-not $up) { throw "Backend did not start. See $logFile" }

    # --- admin: needs SOURCE_MANAGER to manage publishers ---
    $admin = Invoke-Api POST '/auth/login' @{ email = $creds.AdminEmail; password = $creds.AdminPassword }
    if ($admin.roles -notcontains 'SOURCE_MANAGER') {
        $null = Invoke-Api PUT "/admin/staff/$($admin.id)/roles" @{ roles = @('SOURCE_MANAGER', 'SYSTEM_ADMIN') }
    }

    # Windows PowerShell returns a JSON array as one object; unroll it into items.
    $existing = @(Invoke-Api GET '/admin/sources' | ForEach-Object { $_ })
    foreach ($p in $publishers) {
        $source = $existing | Where-Object { $_.code -eq $p.code } | Select-Object -First 1
        if (-not $source) {
            $source = Invoke-Api POST '/admin/sources' @{
                code            = $p.code
                name            = $p.name
                homepageUrl     = "https://$($p.language).demo.invalid/"
                primaryLanguage = $p.language
                termsUrl        = "https://$($p.language).demo.invalid/terms"
                termsSummary    = "Fictional demo publisher for local testing. $($p.comment)"
            }
        }
        foreach ($use in $p.uses) {
            $current = $source.rights | Where-Object { $_.use -eq $use }
            if (-not $current -or $current.status -ne 'PERMITTED') {
                $source = Invoke-Api PUT "/admin/sources/$($source.id)/rights/$use" @{
                    status        = 'PERMITTED'
                    notes         = 'Demo data: fictional publisher, use allowed for local testing only.'
                    nextReviewDue = '2027-12-31'
                }
            }
        }
        if ($source.status -eq 'PROPOSED') {
            $source = Invoke-Api POST "/admin/sources/$($source.id)/activate" @{ notes = 'Demo publisher for local development.' }
        }

        $feedUrl = "http://127.0.0.1:$FeedPort/$($p.feed)"
        $feed = $source.feeds | Where-Object { $_.feedUrl -eq $feedUrl } | Select-Object -First 1
        if (-not $feed) {
            $feed = Invoke-Api POST "/admin/sources/$($source.id)/feeds" @{ feedUrl = $feedUrl; refreshIntervalMinutes = 60 }
        } elseif (-not $feed.enabled) {
            $feed = Invoke-Api PATCH "/admin/feeds/$($feed.id)" @{ enabled = $true }
        }
        $job = Invoke-Api POST "/admin/feeds/$($feed.id)/run"
        Write-Host ("{0}: {1} (new {2}, updated {3}, unchanged {4}, quarantined {5})" -f $p.code, $job.outcome,
            $job.itemsCreated, $job.itemsUpdated, $job.itemsUnchanged, $job.itemsQuarantined)
        # The sample server stops after this script, so stop scheduled fetches.
        $null = Invoke-Api PATCH "/admin/feeds/$($feed.id)" @{ enabled = $false }
    }
    $null = Invoke-Api POST '/auth/logout'

    # --- verified reader account ---
    try {
        $null = Invoke-Api POST '/auth/login' @{ email = $creds.ReaderEmail; password = $creds.ReaderPassword }
        Write-Host 'Dev reader account already exists.'
    } catch {
        $code = Get-StatusCode $_
        if ($code -eq 401) {
            $null = Invoke-Api POST '/auth/register' @{
                email = $creds.ReaderEmail; password = $creds.ReaderPassword; ageConfirmed = $true; uiLanguage = 'en'
            }
        } elseif ($code -eq 403) {
            $null = Invoke-Api POST '/auth/resend-verification' @{ email = $creds.ReaderEmail }
        } else {
            throw
        }
        $token = Wait-ForToken $creds.ReaderEmail
        $null = Invoke-Api POST '/auth/verify-email' @{ token = $token }
        Write-Host 'Created and verified the dev reader account.'
    }
    $null = Invoke-Api POST '/auth/logout'

    Write-Host ''
    Write-Host "Done. Accounts are in $credFile"
    Write-Host 'Start the app:  cd backend; .\mvnw.cmd spring-boot:run    and    cd frontend; npm run dev'
    Write-Host 'Then open http://127.0.0.1:5173'
} finally {
    Get-CimInstance Win32_Process -Filter "CommandLine like '%NewsPortalBackendApplication%' or CommandLine like '%spring-boot:run%'" |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($backend) { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
    Stop-Job $feedServer -ErrorAction SilentlyContinue
    Remove-Job $feedServer -Force -ErrorAction SilentlyContinue
}
