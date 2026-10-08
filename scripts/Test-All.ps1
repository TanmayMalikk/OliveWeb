param(
    [switch]$WithDeviceTests,
    [switch]$SkipFirebaseRules,
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$completedChecks = [System.Collections.Generic.List[string]]::new()
$androidJava = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path -LiteralPath $androidJava) {
    $env:JAVA_HOME = $androidJava
    $env:Path = "$androidJava\bin;$env:Path"
}

# Firebase's bundled Storage rules runtime still calls APIs that produce
# terminal-deprecation warnings on Java 25. Use Java 21 for the emulator when
# it is available, while leaving Android/Gradle on Android Studio's JDK.
$firebaseJava = $env:OLIVE_FIREBASE_JAVA_HOME
if (-not $firebaseJava) {
    $firebaseJavaBase = Join-Path $env:LOCALAPPDATA "OliveTools\MicrosoftJDK21"
    $firebaseJava = Get-ChildItem -LiteralPath $firebaseJavaBase -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe") } |
        Select-Object -First 1 -ExpandProperty FullName
}
if ($firebaseJava -and -not (Test-Path -LiteralPath (Join-Path $firebaseJava "bin\java.exe"))) {
    Write-Warning "OLIVE_FIREBASE_JAVA_HOME does not contain bin\java.exe; Firebase tests will use the current JDK."
    $firebaseJava = $null
}

function Invoke-Checked {
    param([string]$Label,[scriptblock]$Command)
    Write-Host "`n== $Label ==" -ForegroundColor Green
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE." }
    $completedChecks.Add($Label)
}

Set-Location -LiteralPath $projectRoot
Invoke-Checked "Android unit tests, debug APK, UI-test APK, and lint" {
    & ".\gradlew.bat" testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
}
@(
    "$projectRoot\app\build\outputs\apk\debug\app-debug.apk",
    "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    "$projectRoot\app\build\reports\lint-results-debug.html"
).ForEach({ if (-not (Test-Path -LiteralPath $_)) { throw "Required test artifact was not created: $_" } })
$completedChecks.Add("Required Android artifacts created")

Push-Location -LiteralPath "$projectRoot\functions"
try {
    if (-not (Test-Path -LiteralPath "node_modules")) { Invoke-Checked "Install Cloud Functions dependencies" { & npm.cmd ci } }
    Invoke-Checked "Cloud Functions unit tests" { & npm.cmd test }
    Invoke-Checked "Cloud Functions dependency audit" { & npm.cmd audit --omit=dev }
} finally { Pop-Location }

if (-not $SkipFirebaseRules) {
    Push-Location -LiteralPath "$projectRoot\firebase"
    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:Path
    try {
        if ($firebaseJava) {
            $env:JAVA_HOME = $firebaseJava
            $env:Path = "$firebaseJava\bin;$previousPath"
            Write-Host "Using Java 21 for Firebase emulator tests: $firebaseJava" -ForegroundColor Cyan
        }
        if (-not (Test-Path -LiteralPath "node_modules")) { Invoke-Checked "Install Firebase test dependencies" { & npm.cmd ci } }
        Invoke-Checked "Firestore and Storage rules tests" { & npm.cmd test }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
        $env:Path = $previousPath
        Pop-Location
    }
}

if ($WithDeviceTests) {
    Set-Location -LiteralPath $projectRoot
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb)) { $adb = "adb.exe" }
    $readyDevices = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $matches[1] }
    })
    if ($readyDevices.Count -eq 0) { throw "No unlocked Android device or running emulator is available." }
    if ($DeviceSerial) {
        if ($DeviceSerial -notin $readyDevices) { throw "Device '$DeviceSerial' is not available. Run 'adb devices' to see valid serials." }
        $selectedDevice = $DeviceSerial
    } else {
        $selectedDevice = @($readyDevices | Where-Object { $_ -like 'emulator-*' } | Select-Object -First 1)[0]
        if (-not $selectedDevice) { $selectedDevice = $readyDevices[0] }
    }
    Write-Host "Using Android device: $selectedDevice" -ForegroundColor Cyan
    Invoke-Checked "Build Android UI tests" { & ".\gradlew.bat" -PoliveLocalTest=true assembleDebug assembleDebugAndroidTest }
    Invoke-Checked "Install Olive test build" { & $adb -s $selectedDevice install -r "$projectRoot\app\build\outputs\apk\debug\app-debug.apk" }
    Invoke-Checked "Install Olive UI tests" { & $adb -s $selectedDevice install -r -t "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" }
    & $adb -s $selectedDevice shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
    Write-Host "`n== Android device UI tests ==" -ForegroundColor Green
    $instrumentationOutput = & $adb -s $selectedDevice shell am instrument -w -r -e clearPackageData true com.olive.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $instrumentationOutput | ForEach-Object { Write-Host $_ }
    $deviceResult = $instrumentationOutput -join "`n"
    # The suite keeps growing, so accept any successful non-empty test run instead of a hardcoded count.
    if ($LASTEXITCODE -ne 0 -or $deviceResult -notmatch 'OK \(([1-9][0-9]*) tests?\)' -or $deviceResult -match 'FAILURES!!!') {
        throw "Android device UI tests failed on $selectedDevice."
    }
    $deviceTestCount=[regex]::Match($deviceResult,'OK \(([1-9][0-9]*) tests?\)').Groups[1].Value
    $completedChecks.Add("Android device UI tests on $selectedDevice ($deviceTestCount tests)")
}

Write-Host "`nAll requested Olive tests passed ($($completedChecks.Count) checks):" -ForegroundColor Green
$completedChecks | ForEach-Object { Write-Host "  PASS  $_" -ForegroundColor Green }
