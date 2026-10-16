<#
.SYNOPSIS
  Adds real news publishers from candidates.json as PROPOSED publishers, with
  their RSS feeds. Nothing is fetched or shown until a person approves them.

.DESCRIPTION
  Rights first (SRS FR-005, FR-007): a public RSS feed is not proof of
  permission. This script only prepares the review. For each publisher, read
  its terms, then record the permissions in the staff console (Publishers ->
  the publisher -> Record decision) and activate it, or use
  approve-publishers.ps1.

  Needs the backend running. Signs in with the dev administrator from
  data/dev-accounts.txt, or with -Email/-Password (a SOURCE_MANAGER account).
  Safe to run again: existing publishers and feeds are skipped.

.EXAMPLE
  .\scripts\publishers\import-candidates.ps1
#>
param(
    [string]$BackendUrl = 'http://127.0.0.1:8081',
    [string]$Email,
    [string]$Password
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'api.ps1')

$who = Connect-Staff $BackendUrl $Email $Password $repo
Write-Host "Signed in as $who"

$existing = @{}
Invoke-Api $BackendUrl 'GET' '/admin/sources' | ForEach-Object { $_ } | ForEach-Object { $existing[$_.code] = $_ }

$added = 0
$feedsAdded = 0
foreach ($c in Read-Candidates (Join-Path $PSScriptRoot 'candidates.json')) {
    $source = $existing[$c.code]
    if (-not $source) {
        $summary = if ($c.termsUrl) { 'Candidate: review the terms at the link before recording any permission.' }
                   else { 'Candidate: find and review the publisher''s terms for RSS use before recording any permission.' }
        $source = Invoke-Api $BackendUrl 'POST' '/admin/sources' @{
            code            = $c.code
            name            = $c.name
            homepageUrl     = $c.homepageUrl
            primaryLanguage = $c.primaryLanguage
            termsUrl        = $c.termsUrl
            termsSummary    = $summary
        }
        $added++
        Write-Host "  added    $($c.code)  ($($c.name))"
    } else {
        Write-Host "  exists   $($c.code)  [$($source.status)]"
    }
    $known = @($source.feeds | ForEach-Object { $_.feedUrl })
    foreach ($url in $c.feeds) {
        if ($known -notcontains $url) {
            $null = Invoke-Api $BackendUrl 'POST' "/admin/sources/$($source.id)/feeds" @{ feedUrl = $url; refreshIntervalMinutes = 30 }
            $feedsAdded++
        }
    }
}

Write-Host ""
Write-Host "Added $added publisher(s) and $feedsAdded feed(s). They are PROPOSED: nothing is fetched yet."
Write-Host "Next: review each publisher's terms, then approve in the staff console (/admin/sources),"
Write-Host "or run approve-publishers.ps1 for the ones you have reviewed."
