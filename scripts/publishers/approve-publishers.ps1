<#
.SYNOPSIS
  Records YOUR rights decision for publishers you have reviewed, and
  activates them so their feeds are fetched.

.DESCRIPTION
  This records a legal decision in your name: the signed-in staff account is
  stored as the reviewer, with your notes, in the review history and audit
  log (SRS FR-003, FR-076). Run it only after reading each publisher's terms.
  -IReviewedTheTerms is required to make that explicit.

  -Uses picks the permissions to record as PERMITTED (default: metadata only,
  i.e. headline, summary and link). Others: full-text-display, storage,
  translation, image, narration, video-embed. Grant only what the terms allow.

.EXAMPLE
  .\scripts\publishers\approve-publishers.ps1 -Codes bbc-news,bbc-hindi -Uses metadata,image `
      -Notes "Read BBC RSS terms on 2026-10-16: headlines and links with attribution, non-commercial pilot." -IReviewedTheTerms
#>
param(
    [Parameter(Mandatory)] [string[]]$Codes,
    [string[]]$Uses = @('metadata'),
    [Parameter(Mandatory)] [string]$Notes,
    [switch]$IReviewedTheTerms,
    [string]$BackendUrl = 'http://127.0.0.1:8081',
    [string]$Email,
    [string]$Password
)

$ErrorActionPreference = 'Stop'
if (-not $IReviewedTheTerms) {
    throw 'Add -IReviewedTheTerms to confirm you have read these publishers'' terms. This records your decision.'
}
if ($Notes.Trim().Length -lt 10) { throw '-Notes must say what you reviewed (at least 10 characters).' }
if ($Uses -notcontains 'metadata') { throw 'A publisher needs the metadata permission before it can be activated.' }

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'api.ps1')
$who = Connect-Staff $BackendUrl $Email $Password $repo
Write-Host "Recording decisions as $who"

$sources = @{}
Invoke-Api $BackendUrl 'GET' '/admin/sources' | ForEach-Object { $_ } | ForEach-Object { $sources[$_.code] = $_ }

foreach ($code in $Codes) {
    $s = $sources[$code]
    if (-not $s) { Write-Warning "No publisher with code '$code'; skipped."; continue }
    foreach ($use in $Uses) {
        $null = Invoke-Api $BackendUrl 'PUT' "/admin/sources/$($s.id)/rights/$use" @{
            status      = 'PERMITTED'
            notes       = $Notes
            evidenceUrl = $s.termsUrl
        }
    }
    if ($s.status -eq 'PROPOSED') {
        $null = Invoke-Api $BackendUrl 'POST' "/admin/sources/$($s.id)/activate" @{ notes = $Notes }
    }
    Write-Host "  $code : $($Uses -join ', ') permitted; active"
}
Write-Host ""
Write-Host "Feeds are fetched within a minute or two; check Staff console -> Source health."
