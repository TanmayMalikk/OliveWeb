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
    & ".\gradlew.bat" testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug checkArchitecture
}
@(
    "$projectRoot\app\build\outputs\apk\debug\app-debug.apk",
    "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    "$projectRoot\app\build\reports\lint-results-debug.html"
).ForEach({ if (-not (Test-Path -LiteralPath $_)) { throw "Required test artifact was not created: $_" } })
$completedChecks.Add("Required Android artifacts created")

Push-Location -LiteralPath "$projectRoot\web"
try {
    if (-not (Test-Path -LiteralPath "node_modules")) { Invoke-Checked "Install web dependencies" { & npm.cmd ci } }
    Invoke-Checked "Web unit tests" { & npm.cmd test }
    Invoke-Checked "Web production build" { & npm.cmd run build }
    Invoke-Checked "Web browser and accessibility tests" { & npm.cmd run test:e2e }
} finally { Pop-Location }

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
        if (-not $selectedDevice) {
            throw "Android device tests require a disposable emulator. No running emulator was found."
        }
    }
    $isEmulator = ((& $adb -s $selectedDevice shell getprop ro.kernel.qemu 2>&1) -join "`n").Trim()
    if ($isEmulator -ne '1') {
        throw "Android device tests are blocked on physical phones. Select a disposable emulator."
    }
    Write-Host "Using Android device: $selectedDevice" -ForegroundColor Cyan
    Invoke-Checked "Build Android UI tests" { & ".\gradlew.bat" -PoliveLocalTest=true assembleDebug assembleDebugAndroidTest }
    # Remove saved Android tasks as well as app data. Otherwise a previous isolated Compose
    # test can be restored as the starting activity for the next instrumentation run.
    & $adb -s $selectedDevice uninstall com.olive.app.test 2>&1 | Out-Null
    & $adb -s $selectedDevice uninstall com.olive.app 2>&1 | Out-Null
    Invoke-Checked "Install Olive test build" { & $adb -s $selectedDevice install -r "$projectRoot\app\build\outputs\apk\debug\app-debug.apk" }
    Invoke-Checked "Install Olive UI tests" { & $adb -s $selectedDevice install -r -t "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" }
    & $adb -s $selectedDevice shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
    $userState = (& $adb -s $selectedDevice shell dumpsys user 2>&1) -join "`n"
    if ($userState -notmatch 'State: RUNNING_UNLOCKED') {
        throw "The Android test user is locked. Unlock the emulator once with PIN 246810, then rerun the command."
    }
    function Assert-DeviceTestPin {
        $verification = (& $adb -s $selectedDevice shell locksettings verify --old 246810 2>&1) -join "`n"
        if ($verification -match 'user has no password') {
            throw "Device-lock UI tests require a disposable emulator that is already secured with test PIN 246810. The test runner will not create or change a device PIN."
        } elseif ($verification -notmatch 'Lock credential verified successfully') {
            throw "The Android device must use the disposable test PIN 246810: $verification"
        }
    }
    function Ensure-DeviceAwake {
        # Every UI method gets its own instrumentation process. A long suite can let the emulator
        # sleep between methods, which pauses the activity before Compose registers its hierarchy.
        & $adb -s $selectedDevice shell svc power stayon true | Out-Null
        & $adb -s $selectedDevice shell input keyevent KEYCODE_WAKEUP | Out-Null
        & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
        $policy = (& $adb -s $selectedDevice shell dumpsys window policy 2>&1) -join "`n"
        if ($policy -match 'showing=true') {
            & $adb -s $selectedDevice shell input swipe 540 1900 540 500 250 | Out-Null
            & $adb -s $selectedDevice shell input text 246810 | Out-Null
            & $adb -s $selectedDevice shell input keyevent KEYCODE_ENTER | Out-Null
            & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
        }
        $power = (& $adb -s $selectedDevice shell dumpsys power 2>&1) -join "`n"
        $policy = (& $adb -s $selectedDevice shell dumpsys window policy 2>&1) -join "`n"
        if ($power -notmatch 'mWakefulness=Awake' -or $policy -match 'showing=true') {
            throw "The Android test device could not be woken and unlocked with PIN 246810."
        }
    }
    Write-Host "`n== Android device UI tests ==" -ForegroundColor Green
    # Android 17 can crash its activity transaction handler after several Compose activity
    # replacements in one instrumentation process. Isolating methods removes shared activity
    # state and tells developers exactly which workflow needs attention.
    $deviceTestCases = Get-ChildItem -LiteralPath "$projectRoot\app\src\androidTest\java" -Recurse -Filter "*Test.kt" |
        ForEach-Object {
            $source = Get-Content -LiteralPath $_.FullName -Raw
            $packageMatch = [regex]::Match($source, '(?m)^package\s+([\w.]+)')
            $classMatch = [regex]::Match($source, '(?m)^class\s+(\w+Test)\b')
            if ($packageMatch.Success -and $classMatch.Success) {
                [regex]::Matches($source, '@Test\s+fun\s+(\w+)\s*\(') | ForEach-Object {
                    "$($packageMatch.Groups[1].Value).$($classMatch.Groups[1].Value)#$($_.Groups[1].Value)"
                }
            }
        } | Sort-Object
    if ($deviceTestCases.Count -eq 0) { throw "No Android device test methods were found." }
    $deviceTestCount = 0
    foreach ($testCase in $deviceTestCases) {
        Write-Host "  RUN   $testCase" -ForegroundColor Cyan
        $attempt = 0
        do {
            $attempt++
            & $adb -s $selectedDevice shell am force-stop com.olive.app | Out-Null
            & $adb -s $selectedDevice shell am force-stop com.olive.app.test | Out-Null
            Assert-DeviceTestPin
            Ensure-DeviceAwake
            $instrumentationOutput = & $adb -s $selectedDevice shell am instrument -w -r -e clearPackageData true -e class $testCase com.olive.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
            $instrumentationOutput | ForEach-Object { Write-Host $_ }
            $deviceResult = $instrumentationOutput -join "`n"
            $countMatch = [regex]::Match($deviceResult, 'OK \(([1-9][0-9]*) tests?\)')
            $passed = $LASTEXITCODE -eq 0 -and $countMatch.Success -and $deviceResult -notmatch 'FAILURES!!!'
            $frameworkCrash = $deviceResult -match 'shortMsg=Process crashed|Activity client record must not be null'
            if (-not $passed -and $attempt -eq 1 -and $frameworkCrash) {
                Write-Warning "Android framework crashed during $testCase; retrying once in a clean process."
            }
        } while (-not $passed -and $attempt -lt 2 -and $frameworkCrash)
        if (-not $passed) {
            throw "Android device UI test $testCase failed on $selectedDevice."
        }
        $deviceTestCount += [int]$countMatch.Groups[1].Value
    }
    $completedChecks.Add("Android device UI tests on $selectedDevice ($deviceTestCount tests)")
}

Write-Host "`nAll requested Olive tests passed ($($completedChecks.Count) checks):" -ForegroundColor Green
$completedChecks | ForEach-Object { Write-Host "  PASS  $_" -ForegroundColor Green }
