<#
.SYNOPSIS
    Regenerates app/src/main/assets/blocklist.txt from a reputable public
    adult-domain list (StevenBlack hosts, "porn-only" variant).

.DESCRIPTION
    StevenBlack's hosts project is the most widely deployed open blocklist used
    by DNS-level blockers (AdGuard, Blocky, dnsmasq setups). Its "porn-only"
    alternate is the curated adult-only subset, so it does not drag in the
    general ad/tracker domains that would risk breaking legitimate browsing.

    The generated asset is a plain "one domain per line" file, which is the
    format Blocklist.parseRules() already consumes.

    Run from the repository root:
        powershell -ExecutionPolicy Bypass -File tools/blocklist/refresh-blocklist.ps1

    After regenerating, run the JVM suite (it asserts the shipped asset's size,
    provenance comments and false-positive guarantees):
        ./gradlew :app:testDebugUnitTest
#>
[CmdletBinding()]
param(
    [string]$SourceUrl = 'https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts',
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not populated while param defaults are evaluated, so the
# default output path is resolved here instead.
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $OutputPath = Join-Path $scriptDir '..\..\app\src\main\assets\blocklist.txt'
}

$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
if (-not (Test-Path -LiteralPath (Split-Path -Parent $OutputPath))) {
    throw "Output directory does not exist: $(Split-Path -Parent $OutputPath)"
}

Write-Host "Downloading $SourceUrl"
$ProgressPreference = 'SilentlyContinue'
$raw = (Invoke-WebRequest -Uri $SourceUrl -UseBasicParsing -TimeoutSec 120).Content -split "`n"

# hosts-file lines look like: "0.0.0.0 domain" / "127.0.0.1 domain"
# Keep only the last field, drop comments/blanks and the local-only names that
# every hosts file carries (they would break loopback if ever sinkholed).
$skip = @('localhost', 'broadcasthost', 'local', 'localhost.localdomain')

$domains = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in $raw) {
    $entry = $line.Trim()
    if ($entry.Length -eq 0 -or $entry.StartsWith('#')) { continue }
    $entryHost = ($entry -split '\s+')[-1].ToLowerInvariant()
    if ($entryHost.Length -eq 0) { continue }
    if ($entryHost -match '^\d+\.\d+\.\d+\.\d+$') { continue }   # bare IP entries
    if ($skip -contains $entryHost) { continue }
    if ($entryHost -notmatch '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') { continue }
    $null = $domains.Add($entryHost)
}

# The hand-curated seed below was RIY's original list. Everything in it is also
# covered by the upstream list or by the code-level heuristics, but keeping the
# explicit rules documents intent and keeps behaviour stable if upstream changes.
$seed = @(
    'pornhub.com', 'xvideos.com', 'xvideos2.com', 'xvideos3.com', 'xnxx.com',
    'xhamster.com', 'redtube.com', 'chaturbate.com', 'stripchat.com',
    'onlyfans.com', 'fansly.com', 'hanime.tv', 'e-hentai.org', 'redgifs.com'
)
foreach ($d in $seed) { $null = $domains.Add($d) }

$sorted = @($domains) | Sort-Object

$header = @(
    '# RIY adult-domain blocklist — GENERATED, DO NOT EDIT BY HAND.'
    '#'
    '# Regenerate with:  tools/blocklist/refresh-blocklist.ps1'
    '# Source:           StevenBlack hosts, "porn-only" alternate'
    '#                    https://github.com/StevenBlack/hosts'
    '#'
    '# Format: one domain per line. A rule matches the domain itself and every'
    '# subdomain of it (a.b.example.com and example.com are both blocked).'
    '# Lines starting with "#" are comments. Matching is case-insensitive.'
    '#'
    '# In addition to these explicit rules the matcher blocks, at code level:'
    '#   * the dedicated adult TLDs .xxx .adult .porn .sex .sexy'
    '#   * any host CONTAINING an adult-only brand substring (porn, hentai, xnxx,'
    '#     xvideos, onlyfans, ...) — no common innocent domain contains these'
    '#   * WHOLE-LABEL tokens (porn/sex/xxx/adult/hentai/nude/nsfw/...) inside the'
    '#     registrable part only, so sussex.ac.uk, adultswim.com and nsfwjs.com'
    '#     stay reachable'
    ''
)

$body = $header + $sorted
[System.IO.File]::WriteAllLines($OutputPath, $body, [System.Text.UTF8Encoding]::new($false))

$bytes = (Get-Item -LiteralPath $OutputPath).Length
Write-Host ("Wrote {0} domains ({1:N0} bytes) to {2}" -f $sorted.Count, $bytes, $OutputPath)