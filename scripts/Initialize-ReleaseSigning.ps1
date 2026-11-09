param(
    [string]$KeyStorePath = "$env:USERPROFILE\Documents\OliveRelease\olive-release.jks",
    [string]$KeyAlias = "olive-release"
)

$ErrorActionPreference = "Stop"

# This setup runs once so every future APK is signed by the same private release key.
$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolCandidates = @(
    if ($keytoolCommand) { $keytoolCommand.Source }
    if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\keytool.exe" }
    Join-Path $env:ProgramFiles "Android\Android Studio\jbr\bin\keytool.exe"
    Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr\bin\keytool.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
$keytool = $keytoolCandidates | Select-Object -First 1
if (-not $keytool) {
    throw "keytool was not found. Install a JDK or Android Studio, then run this script again."
}
$keyDirectory = Split-Path -Parent $KeyStorePath
New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null

if (Test-Path -LiteralPath $KeyStorePath) {
    throw "A keystore already exists at $KeyStorePath. It was not replaced."
}

Write-Host "Choose and safely record the keystore password when keytool asks for it."
& $keytool -genkeypair -v -keystore $KeyStorePath -alias $KeyAlias -keyalg RSA -keysize 4096 -validity 10000
if ($LASTEXITCODE -ne 0) {
    throw "keytool did not create the release key."
}

# Passwords stay out of command history while this ignored local signing file is created.
$storePassword = Read-Host "Enter the keystore password again" -AsSecureString
$keyPassword = Read-Host "Enter the key password, or the same password if you reused it" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePassword)
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPassword)
try {
    $storePlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $keyPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    $escapedPath = $KeyStorePath.Replace("\", "/")
    $properties = @(
        "storeFile=$escapedPath"
        "storePassword=$storePlain"
        "keyAlias=$KeyAlias"
        "keyPassword=$keyPlain"
    )
    $propertiesPath = Join-Path $PSScriptRoot "..\keystore.properties"
    [IO.File]::WriteAllLines($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
    Remove-Variable storePlain, keyPlain -ErrorAction SilentlyContinue
}

Write-Host "Created the release key and ignored keystore.properties file. Back up the key and password now."
