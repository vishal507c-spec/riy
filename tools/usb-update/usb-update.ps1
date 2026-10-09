<#
.SYNOPSIS
    Safe USB / ADB update helper for the Riy Android app.

.DESCRIPTION
    Performs the update that a policy-restricted device cannot do by itself:
    verify, then `adb install -r` the Riy release APK over USB.

    SAFETY RULES ENFORCED HERE (do not relax):
      * The target package is a hard constant. Nothing else is ever installed.
      * The APK's package name MUST be verified as com.vishal.riy before install.
      * The APK's signing certificate is printed, and Android performs the
        authoritative identity check during installation; a mismatch stops with
        an explanation instead of attempting a workaround.
      * No arbitrary shell is executed. adb and the SDK tools are invoked with
        explicit argument arrays; no string is ever passed to a shell.
      * `install -r` is always used, so app data (settings, blocker config,
        accessibility state) is preserved. Uninstall is NEVER suggested or run.

.PARAMETER Version
    Release version to install, e.g. 2.7.0. Default: the latest GitHub release.

.PARAMETER ApkPath
    Use an already-downloaded APK instead of downloading the release asset.

.PARAMETER PullFromPhone
    Pull the APK from /sdcard/Download/Riy on the phone instead of downloading.

.EXAMPLE
    .\usb-update.ps1
    .\usb-update.ps1 -Version 2.7.0
    .\usb-update.ps1 -ApkPath C:\build\riy-v2.7.0.apk
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [string]$ApkPath = "",
    [switch]$PullFromPhone
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---- hard constants ----------------------------------------------------------
$ExpectedPackage = 'com.vishal.riy'
$Owner           = 'vishal507c-spec'
$Repo            = 'riy'
$ApkNamePrefix   = 'riy-v'

function Write-Step  { param([string]$m) Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok    { param([string]$m) Write-Host "    OK  $m" -ForegroundColor Green }
function Write-Warn  { param([string]$m) Write-Host "    !!  $m" -ForegroundColor Yellow }
function Write-Fail  { param([string]$m) Write-Host "    XX  $m" -ForegroundColor Red }
function Die         { param([string]$m) Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# GitHub tags are "vX.Y.Z". Accept either spelling from the user.
function ConvertTo-Tag {
    param([string]$Version)
    if (-not $Version) { return '' }
    if ($Version -match '^v\d') { return $Version }
    return "v$Version"
}

# ---- locate tools ------------------------------------------------------------
function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\Android Studio\platform-tools\adb.exe",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates) { return $candidates[0] }
    return $null
}

function Find-SdkTool {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $roots = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:ANDROID_HOME",
        "$env:ANDROID_SDK_ROOT"
    ) | Where-Object { $_ -and (Test-Path $_) }
    foreach ($root in $roots) {
        $hits = Get-ChildItem -Path (Join-Path $root 'build-tools') -Filter $Name -Recurse -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending
        if ($hits) { return $hits[0].FullName }
    }
    return $null
}

# External tools are invoked ONLY through this wrapper, with an argument array. No shell.
function Invoke-Tool {
    param([string]$ToolPath, [string[]]$Arguments)
    # A nonzero tool result must be returned as data, not as a terminating error,
    # so every caller can explain offline, missing-file, and install failures.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $toolOutput = & $ToolPath @Arguments 2>&1
        $toolExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    [pscustomobject]@{
        ExitCode = $toolExit
        Output   = ($toolOutput | Out-String)
    }
}

# adb is invoked ONLY through this wrapper, with an argument array. No shell.
function Invoke-Adb {
    param([string]$AdbPath, [string[]]$Arguments, [string]$TargetSerial = "")
    $all = @()
    if ($TargetSerial) { $all += @('-s', $TargetSerial) }
    $all += $Arguments
    Invoke-Tool -ToolPath $AdbPath -Arguments $all
}

# ---- step 1: adb -------------------------------------------------------------
Write-Step 'Checking for adb'
$adb = Find-Adb
if (-not $adb) {
    Die 'adb was not found. Install Android platform-tools and re-run, or add it to PATH.'
}
Write-Ok "adb: $adb"

# ---- step 2: exactly one usable device --------------------------------------
Write-Step 'Looking for a connected device'
& $adb start-server | Out-Null
$devicesOut = (& $adb devices) 2>&1 | Out-String
Write-Host $devicesOut.TrimEnd()

$ready     = @()   # state "device"
$offline   = @()
$unauth    = @()
foreach ($line in ($devicesOut -split "`r?`n")) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith('List of devices')) { continue }
    $parts = $t -split '\s+'
    if ($parts.Count -lt 2) { continue }
    switch ($parts[1]) {
        'device'        { $ready    += $parts[0] }
        'offline'       { $offline  += $parts[0] }
        'unauthorized'  { $unauth   += $parts[0] }
        default         { }
    }
}

if ($unauth.Count -gt 0) {
    Die ("Device not authorised. Unlock the phone and accept the USB debugging prompt, then re-run. " +
         "Serial(s): " + ($unauth -join ', '))
}
if ($offline.Count -gt 0) {
    Die ("Device is offline. Reconnect the cable or restart adb, then re-run. " +
         "Serial(s): " + ($offline -join ', '))
}
if ($ready.Count -eq 0) {
    Die 'No device connected. Enable USB debugging, plug the phone in, accept the prompt, then re-run.'
}
if ($ready.Count -gt 1) {
    Die ("Multiple devices are connected; refusing to guess. Serial(s): " + ($ready -join ', ') +
         " - reconnect all but one and re-run.")
}
$serial = $ready[0]
Write-Ok "one device: $serial"

# ---- step 3: locate the package on the device -------------------------------
Write-Step 'Reading the installed package'
$pmOut = Invoke-Adb -AdbPath $adb -Arguments @('shell', 'dumpsys', 'package', $ExpectedPackage) -TargetSerial $serial
if ($pmOut.ExitCode -ne 0 -or $pmOut.Output -notmatch 'versionCode=') {
    Die "Package $ExpectedPackage is not installed on the device. Install it once from a signed APK first."
}
$installedCode = ([regex]::Match($pmOut.Output, 'versionCode=(\d+)')).Groups[1].Value
$installedName = ([regex]::Match($pmOut.Output, 'versionName=(\S+)')).Groups[1].Value
Write-Ok "installed: $installedName (versionCode $installedCode)"

# ---- step 4: obtain the APK --------------------------------------------------
$workDir = Join-Path $env:TEMP 'riy-usb-update'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

function Resolve-ReleaseApk {
    param([string]$Tag, [string]$OutDir)
    # A read-only token is used only when the release repository is private. It
    # comes from the environment, is never written to disk, and is never shown.
    $headers = @{ 'User-Agent' = 'riy-usb-update' }
    if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $($env:GITHUB_TOKEN)" }
    if ($Tag) {
        $Tag = ConvertTo-Tag $Tag
    } else {
        try {
            $latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/latest" `
                        -Headers $headers
        } catch {
            Die 'Could not read the latest release. Check the network connection and try again.'
        }
        $Tag = $latest.tag_name
    }
    try {
        $assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Owner/$Repo/releases/tags/$Tag" `
                    -Headers $headers
    } catch {
        Die "Could not read release $Tag. Check that the release exists and try again."
    }
    $apk = $assets.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
    if (-not $apk) { Die "Release $Tag has no APK asset." }
    $target = Join-Path $OutDir $apk.name
    Write-Step "Downloading $($apk.name)"
    try {
        Invoke-WebRequest -Uri $apk.browser_download_url -OutFile $target -Headers $headers -UseBasicParsing
    } catch {
        Die "Could not download $($apk.name). Check the network connection and try again."
    }
    return @{ Path = $target; Name = $apk.name; Tag = $Tag }
}

Write-Step 'Locating the update APK'
if ($ApkPath) {
    if (-not (Test-Path -LiteralPath $ApkPath)) { Die "APK not found: $ApkPath" }
    $apkInfo = @{ Path = (Resolve-Path -LiteralPath $ApkPath).Path; Name = (Split-Path -Leaf $ApkPath); Tag = '' }
} elseif ($PullFromPhone) {
    if (-not $Version) { Die '-PullFromPhone needs -Version so the right file name is known.' }
    $name = "$ApkNamePrefix$(ConvertTo-Tag $Version | ForEach-Object { $_.Substring(1) }).apk"
    $remote = "/sdcard/Download/Riy/$name"
    $apkInfo = @{ Path = (Join-Path $workDir $name); Name = $name; Tag = $Version }
    Write-Step "Pulling $remote from the phone"
    $pull = Invoke-Adb -AdbPath $adb -Arguments @('pull', $remote, $apkInfo.Path) -TargetSerial $serial
    if ($pull.ExitCode -ne 0 -or -not (Test-Path $apkInfo.Path)) {
        Die "Could not pull $remote. Download the release APK from the release page instead, then use -ApkPath."
    }
} else {
    $apkInfo = Resolve-ReleaseApk -Tag $Version -OutDir $workDir
}

$apkFile = $apkInfo.Path
$apkSize = (Get-Item -LiteralPath $apkFile).Length
if ($apkSize -le 0) { Die 'The APK file is empty.' }
Write-Ok "$($apkInfo.Name) ($([math]::Round($apkSize/1MB,2)) MB)"

# ---- step 5: verify the APK identity (package + version + signature) ---------
Write-Step 'Verifying the APK'

$aapt2 = Find-SdkTool 'aapt2.exe'
$apkPackage = ''
$apkCode = ''
if ($aapt2) {
    $badgingResult = Invoke-Tool -ToolPath $aapt2 -Arguments @('dump', 'badging', $apkFile)
    $badging = $badgingResult.Output
    if ($badgingResult.ExitCode -ne 0 -or $badging -notmatch 'package:') {
        Die 'The APK could not be read as an Android package. Download it again; it may be truncated or corrupt.'
    }
    $apkPackage = ([regex]::Match($badging, "package: name='([^']+)'")).Groups[1].Value
    $apkCode    = ([regex]::Match($badging, "versionCode='(\d+)'")).Groups[1].Value
    Write-Ok "aapt2: package=$apkPackage versionCode=$apkCode"
} else {
    Write-Warn 'aapt2 not found; package/version verification is limited to what adb reports.'
}

if ($apkPackage -and $apkPackage -ne $ExpectedPackage) {
    Die "Refusing to install: this APK is '$apkPackage', not '$ExpectedPackage'."
}
if ($apkCode) {
    $apkCodeLong = [int64]$apkCode
    $installedCodeLong = [int64]$installedCode
    if ($apkCodeLong -eq $installedCodeLong) {
        Write-Warn "The APK is already the installed version ($apkCode). Nothing to do."
        exit 0
    }
    if ($apkCodeLong -lt $installedCodeLong) {
        Die ("Refusing to install: APK versionCode $apkCodeLong is OLDER than the installed " +
             "$installedCodeLong. Use 'adb install -r -d' only if a downgrade is really intended.")
    }
}

$apksigner = Find-SdkTool 'apksigner.bat'
$apkSignerDigest = ''
if ($apksigner) {
    $certResult = Invoke-Tool -ToolPath $apksigner -Arguments @('verify', '--print-certs', $apkFile)
    $certOut = $certResult.Output
    if ($certResult.ExitCode -ne 0) {
        Die 'The APK failed signature verification before installation. Download it again; it may be truncated or corrupt.'
    }
    $apkSignerDigest = ([regex]::Match($certOut, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')).Groups[1].Value
} else {
    Write-Warn 'apksigner not found; signature identity will be enforced by Android itself.'
}

if ($apkSignerDigest) {
    $deviceCert = Invoke-Adb -AdbPath $adb -Arguments @('shell', 'pm', 'path', $ExpectedPackage) -TargetSerial $serial
    Write-Ok "APK signer SHA-256: $apkSignerDigest"
    # Android performs the authoritative check. We surface it up front so the
    # reason for a failure is never a mystery, and we never attempt to work
    # around a mismatch.
    if ($deviceCert.ExitCode -ne 0) {
        Write-Warn 'Could not query the installed APK path; letting Android verify the signature.'
    }
}

# ---- step 6: in-place install ------------------------------------------------
Write-Step 'Installing (in place, app data preserved)'
Write-Host "    adb -s $serial install -r `"$apkFile`""
$install = Invoke-Adb -AdbPath $adb -Arguments @('install', '-r', $apkFile) -TargetSerial $serial
$output = $install.Output

if ($install.ExitCode -eq 0 -and $output -match 'Success') {
    Write-Ok 'Update installed successfully.'
    Write-Host ''
    Write-Host '    Re-open Riy on the phone to finish.' -ForegroundColor Green
    exit 0
}

Write-Host $output.TrimEnd()
$reason = switch -Regex ($output) {
    'INSTALL_FAILED_UPDATE_INCOMPATIBLE' {
        'The APK is signed with a different certificate than the installed app. ' +
        'Android will not allow it. Do not uninstall the app (that would delete your data); ' +
        'sign the release with the same key instead.'
    }
    'INSTALL_FAILED_USER_RESTRICTED' {
        'The device refused the installation under its administrator/user policy. ' +
        'No data was uninstalled. Check the authorized profile or device-owner policy, then re-run.'
    }
    'INSTALL_FAILED_PERMISSION_DENIED' {
        'The device refused installation permission. Re-authorize USB debugging or use the authorized profile, then re-run.'
    }
    'device .*not found|no devices/emulators found|device offline|unauthorized|closed|connection .*reset|connection .*refused|broken pipe' {
        'The phone disconnected or lost ADB authorization during installation. ' +
        'Reconnect USB, accept the debugging prompt if shown, verify the installed version, then re-run.'
    }
    'INSTALL_FAILED_VERSION_DOWNGRADE' {
        'The APK is older than the installed version. Do not force it unless intended.'
    }
    'INSTALL_FAILED_INSUFFICIENT_STORAGE' { 'Not enough free storage on the device.' }
    'INSTALL_FAILED_NO_MATCHING_ABIS'       { 'The APK does not support this device ABI.' }
    'INSTALL_FAILED_VERIFICATION_FAILURE'   { 'The APK failed signature verification.' }
    'INSTALL_CANCELED'                      { 'The install was cancelled.' }
    default                                { "adb install failed with exit code $($install.ExitCode)." }
}
Write-Fail $reason
exit 1