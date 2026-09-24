param(
    [string]$AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Join-Path $AndroidSdk 'build-tools'
$signer = Get-ChildItem -LiteralPath $buildTools -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $signer) { throw 'Install Android SDK build-tools before packaging.' }
Push-Location $projectRoot
try {
    & ./gradlew.bat assembleRelease testDebugUnitTest lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release checks failed.' }
    $apk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Signed release APK was not produced.' }
    & $signer verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $output = Join-Path $projectRoot 'distribution'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $destination = Join-Path $output 'Olive-release.apk'
    Copy-Item -LiteralPath $apk -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $output 'Olive-release.apk.sha256') -Value "$hash  Olive-release.apk" -Encoding ascii
    Write-Output "Release package ready: $destination"
} finally { Pop-Location }
