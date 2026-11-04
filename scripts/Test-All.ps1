param(
    [switch]$WithDeviceTests,
    [switch]$SkipFirebaseRules,
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
# Node warns when both color controls are inherited from the launching terminal. Keep the explicit
# FORCE_COLOR preference and remove the contradictory opt-out for all child npm/Playwright runs.
if ($env:FORCE_COLOR -and $env:NO_COLOR) { Remove-Item Env:NO_COLOR }
$projectRoot = Split-Path -Parent $PSScriptRoot
$completedChecks = [System.Collections.Generic.List[string]]::new()
$testReportDirectory = Join-Path $projectRoot "build\reports"
New-Item -ItemType Directory -Force -Path $testReportDirectory | Out-Null
Remove-Item -LiteralPath (Join-Path $projectRoot "app\build\test-results\testDebugUnitTest") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $projectRoot "wear\build\test-results\testDebugUnitTest") -Recurse -Force -ErrorAction SilentlyContinue
@(
    "current-test-check.txt",
    "failed-test-check.txt",
    "failed-test-output.txt",
    "completed-test-checks.txt",
    "web-unit-tests.xml",
    "web-browser-tests.xml",
    "functions-tests.xml",
    "firebase-rules-tests.xml"
) | ForEach-Object { Remove-Item -LiteralPath (Join-Path $testReportDirectory $_) -Force -ErrorAction SilentlyContinue }
Remove-Item -LiteralPath (Join-Path $testReportDirectory "device-ui-tests") -Recurse -Force -ErrorAction SilentlyContinue
$androidJava = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path -LiteralPath $androidJava) {
    $env:JAVA_HOME = $androidJava
    $env:Path = "$androidJava\bin;$env:Path"
}

$nodePath = "C:\Program Files\nodejs"
if (Test-Path -LiteralPath (Join-Path $nodePath "npm.cmd")) {
    # Firebase 13 requires Node 24.12 or newer. Prefer the supported system install even when
    # an older Node/npm pair appears earlier in the caller's PATH.
    $env:Path = "$nodePath;$env:Path"
} elseif (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    $nodeDirs = @(
        "C:\Program Files\nodejs",
        "${env:ProgramFiles(x86)}\nodejs",
        "$env:APPDATA\npm",
        "$env:LOCALAPPDATA\npm"
    )
    foreach ($dir in $nodeDirs) {
        if ($dir -and (Test-Path -LiteralPath $dir)) {
            $env:Path = "$dir;$env:Path"
        }
    }
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
    Set-Content -LiteralPath (Join-Path $testReportDirectory "current-test-check.txt") -Value $Label
    & $Command
    if ($LASTEXITCODE -ne 0) {
        Set-Content -LiteralPath (Join-Path $testReportDirectory "failed-test-check.txt") -Value "$Label|$LASTEXITCODE"
        throw "$Label failed with exit code $LASTEXITCODE."
    }
    $completedChecks.Add($Label)
}

function Wait-ForAndroidPackageManager {
    param(
        [string]$Adb,
        [string]$Serial,
        [int]$TimeoutSeconds = 60
    )
    & $Adb -s $Serial wait-for-device | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $booted = ((& $Adb -s $Serial shell getprop sys.boot_completed 2>&1) -join "`n").Trim()
        $packageManager = ((& $Adb -s $Serial shell pm path android 2>&1) -join "`n").Trim()
        if ($booted -eq "1" -and $packageManager -match '^package:') { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Android's package manager did not become ready on $Serial within $TimeoutSeconds seconds."
}

function Install-AndroidTestApk {
    param(
        [string]$Label,
        [string]$Adb,
        [string]$Serial,
        [string]$Apk,
        [switch]$TestOnly
    )
    $attemptDetails = [System.Collections.Generic.List[string]]::new()
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Wait-ForAndroidPackageManager -Adb $Adb -Serial $Serial
        $arguments = @('-s', $Serial, 'install', '--no-streaming', '-r')
        if ($TestOnly) { $arguments += '-t' }
        $arguments += $Apk
        # ADB writes normal push progress to stderr even when installation succeeds. PowerShell 5
        # turns that text into NativeCommandError when the script-wide error mode is Stop, so this
        # call temporarily collects native stderr without treating the progress line as an exception.
        $previousErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $rawOutput = & $Adb @arguments 2>&1
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorPreference
        }
        $output = ($rawOutput | ForEach-Object { $_.ToString() }) -join "`n"
        Write-Host $output
        if ($exitCode -eq 0 -and $output -match '(?m)^Success\s*$') { return }
        $attemptDetails.Add("Attempt $attempt (exit code $exitCode):`n$output")
        if ($attempt -lt 3) {
            Write-Warning "$Label failed on attempt $attempt; waiting for the emulator package manager and retrying."
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
    Set-Content -LiteralPath (Join-Path $testReportDirectory "failed-test-output.txt") -Value $attemptDetails -Encoding utf8
    throw "$Label failed after 3 attempts."
}

Set-Location -LiteralPath $projectRoot
Invoke-Checked "Android and Wear unit tests, APKs, UI-test APK, and lint" {
    & ".\gradlew.bat" :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :wear:lintDebug checkArchitecture
}
@(
    "$projectRoot\app\build\outputs\apk\debug\app-debug.apk",
    "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    "$projectRoot\app\build\reports\lint-results-debug.html",
    "$projectRoot\wear\build\outputs\apk\debug\wear-debug.apk",
    "$projectRoot\wear\build\reports\lint-results-debug.html"
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
    $previousNoGceCheck = $env:NO_GCE_CHECK
    $previousNodeNoWarnings = $env:NODE_NO_WARNINGS
    try {
        if ($firebaseJava) {
            $env:JAVA_HOME = $firebaseJava
            $env:Path = "$firebaseJava\bin;$previousPath"
            Write-Host "Using Java 21 for Firebase emulator tests: $firebaseJava" -ForegroundColor Cyan
        }
        # Emulator tests are always local and must not probe cloud VM metadata endpoints.
        $env:NO_GCE_CHECK = "true"
        # The Admin SDK probes VM metadata as a fallback during intentionally offline emulator
        # tests. Keep that expected failed probe from surfacing as a Node process warning.
        $env:NODE_NO_WARNINGS = "1"
        if (-not (Test-Path -LiteralPath "node_modules")) { Invoke-Checked "Install Firebase test dependencies" { & npm.cmd ci } }
        Invoke-Checked "Firestore and Storage rules tests" { & npm.cmd test }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
        $env:Path = $previousPath
        $env:NO_GCE_CHECK = $previousNoGceCheck
        $env:NODE_NO_WARNINGS = $previousNodeNoWarnings
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
    Wait-ForAndroidPackageManager -Adb $adb -Serial $selectedDevice
    # Remove saved Android tasks as well as app data. Otherwise a previous isolated Compose
    # test can be restored as the starting activity for the next instrumentation run.
    & $adb -s $selectedDevice uninstall com.olive.app.test 2>&1 | Out-Null
    & $adb -s $selectedDevice uninstall com.olive.app 2>&1 | Out-Null
    Invoke-Checked "Install Olive test build" {
        Install-AndroidTestApk -Label "Install Olive test build" -Adb $adb -Serial $selectedDevice -Apk "$projectRoot\app\build\outputs\apk\debug\app-debug.apk"
    }
    Invoke-Checked "Install Olive UI tests" {
        Install-AndroidTestApk -Label "Install Olive UI tests" -Adb $adb -Serial $selectedDevice -Apk "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" -TestOnly
    }
    & $adb -s $selectedDevice shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
    $userState = (& $adb -s $selectedDevice shell dumpsys user 2>&1) -join "`n"
    if ($userState -notmatch 'State: RUNNING_UNLOCKED') {
        throw "The Android test user is locked. Unlock the emulator once with PIN 246810, then rerun the command."
    }
    $pinVerification = (& $adb -s $selectedDevice shell locksettings verify --old 246810 2>&1) -join "`n"
    if ($pinVerification -match 'user has no password') {
        $setPinResult = (& $adb -s $selectedDevice shell locksettings set-pin 246810 2>&1) -join "`n"
        if ($setPinResult -notmatch "Pin set to '246810'") {
            throw "Could not configure test PIN 246810 on disposable emulator ${selectedDevice}: $setPinResult"
        }
        $devicePinReady = $true
    } else {
        $devicePinReady = $pinVerification -match 'Lock credential verified successfully'
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
            if ($devicePinReady) {
                & $adb -s $selectedDevice shell input text 246810 | Out-Null
                & $adb -s $selectedDevice shell input keyevent KEYCODE_ENTER | Out-Null
            }
            & $adb -s $selectedDevice shell wm dismiss-keyguard | Out-Null
        }
        $power = (& $adb -s $selectedDevice shell dumpsys power 2>&1) -join "`n"
        $policy = (& $adb -s $selectedDevice shell dumpsys window policy 2>&1) -join "`n"
        if ($power -notmatch 'mWakefulness=Awake' -or $policy -match 'showing=true') {
            $preparation = if ($devicePinReady) { "with test PIN 246810" } else { "without changing its lock settings" }
            throw "The Android test device could not be woken and unlocked $preparation."
        }
    }
    try {
    Write-Host "`n== Android device UI tests ==" -ForegroundColor Green
    $deviceReportDirectory = Join-Path $testReportDirectory "device-ui-tests"
    New-Item -ItemType Directory -Force -Path $deviceReportDirectory | Out-Null

    function Save-DeviceTestResult {
        param(
            [string]$TestCase,
            [ValidateSet("PASSED", "FAILED", "SKIPPED")][string]$Status,
            [string]$Output = "",
            [double]$DurationSeconds = 0
        )
        $separator = $TestCase.LastIndexOf('#')
        $className = if ($separator -ge 0) { $TestCase.Substring(0, $separator) } else { $TestCase }
        $testName = if ($separator -ge 0) { $TestCase.Substring($separator + 1) } else { $TestCase }
        $document = [System.Xml.XmlDocument]::new()
        $suite = $document.CreateElement("testsuite")
        $suite.SetAttribute("name", "Android device UI")
        $suite.SetAttribute("tests", "1")
        $suite.SetAttribute("failures", $(if ($Status -eq "FAILED") { "1" } else { "0" }))
        $suite.SetAttribute("skipped", $(if ($Status -eq "SKIPPED") { "1" } else { "0" }))
        [void]$document.AppendChild($suite)
        $case = $document.CreateElement("testcase")
        $case.SetAttribute("classname", $className)
        $case.SetAttribute("name", $testName)
        $case.SetAttribute("time", $DurationSeconds.ToString("0.000", [Globalization.CultureInfo]::InvariantCulture))
        [void]$suite.AppendChild($case)
        if ($Status -eq "FAILED") {
            $failure = $document.CreateElement("failure")
            $failure.SetAttribute("message", "Android device UI test failed or its instrumentation process crashed.")
            $failure.InnerText = $Output -replace '[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD]', ''
            [void]$case.AppendChild($failure)
        } elseif ($Status -eq "SKIPPED") {
            $skipped = $document.CreateElement("skipped")
            $skipped.SetAttribute("message", $Output)
            [void]$case.AppendChild($skipped)
        }
        $fileName = ($TestCase -replace '[^A-Za-z0-9_.-]', '_') + ".xml"
        $document.Save((Join-Path $deviceReportDirectory $fileName))
    }

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
    $deviceLockTestNames = @(
        'SafeEntrySettingsUiTest#switchingAndBackupSetupRequireProofAndNeverExposePinDuringDeviceLock',
        'SafeEntryUiTest#deviceLockCancellationReturnsActualTalkBackFocusAndAllowsRetry',
        'SafeEntryUiTest#deviceLockEnrollmentAndEveryReentryRequireAndroidAuthentication'
    )
    $deviceTestCount = 0
    $skippedDeviceLockTests = 0
    foreach ($testCase in $deviceTestCases) {
        $requiresPreparedPin = $deviceLockTestNames | Where-Object { $testCase.EndsWith($_) }
        if ($requiresPreparedPin -and -not $devicePinReady) {
            Write-Warning "SKIP  $testCase (requires a disposable emulator already secured with test PIN 246810)"
            Save-DeviceTestResult -TestCase $testCase -Status "SKIPPED" -Output "Requires a disposable emulator secured with test PIN 246810."
            $skippedDeviceLockTests++
            continue
        }
        Write-Host "  RUN   $testCase" -ForegroundColor Cyan
        Set-Content -LiteralPath (Join-Path $testReportDirectory "current-test-check.txt") -Value "Android device UI test $testCase"
        $testStarted = Get-Date
        $attempt = 0
        do {
            $attempt++
            & $adb -s $selectedDevice shell am force-stop com.olive.app | Out-Null
            & $adb -s $selectedDevice shell am force-stop com.olive.app.test | Out-Null
            Ensure-DeviceAwake
            $instrumentationOutput = & $adb -s $selectedDevice shell am instrument -w -r -e clearPackageData true -e class $testCase com.olive.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
            $instrumentationOutput | ForEach-Object { Write-Host $_ }
            $deviceResult = $instrumentationOutput -join "`n"
            $countMatch = [regex]::Match($deviceResult, 'OK \(([1-9][0-9]*) tests?\)')
            $passed = $LASTEXITCODE -eq 0 -and $countMatch.Success -and $deviceResult -notmatch 'FAILURES!!!'
            $frameworkCrash = $deviceResult -match 'shortMsg=Process crashed|Activity client record must not be null|INSTRUMENTATION_ABORTED: System has crashed'
            if (-not $passed -and $attempt -eq 1 -and $frameworkCrash) {
                Write-Warning "Android framework crashed during $testCase; retrying once in a clean process."
            }
        } while (-not $passed -and $attempt -lt 2 -and $frameworkCrash)
        $testDuration = ((Get-Date) - $testStarted).TotalSeconds
        if (-not $passed) {
            Save-DeviceTestResult -TestCase $testCase -Status "FAILED" -Output $deviceResult -DurationSeconds $testDuration
            Set-Content -LiteralPath (Join-Path $testReportDirectory "failed-test-check.txt") -Value "Android device UI test $testCase|1"
            throw "Android device UI test $testCase failed on $selectedDevice."
        }
        Save-DeviceTestResult -TestCase $testCase -Status "PASSED" -Output $deviceResult -DurationSeconds $testDuration
        $deviceTestCount += [int]$countMatch.Groups[1].Value
    }
    $completedChecks.Add("Android device UI tests on $selectedDevice ($deviceTestCount passed, $skippedDeviceLockTests device-lock tests skipped)")
    } finally {
        # Device-lock tests use a known PIN only on a disposable emulator. Always remove it when
        # the suite ends, including after a failed test, so the emulator is not left secured.
        $previousErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & $adb -s $selectedDevice shell am force-stop com.olive.app 2>&1 | Out-Null
            & $adb -s $selectedDevice shell am force-stop com.olive.app.test 2>&1 | Out-Null
            $clearPinResult = ((& $adb -s $selectedDevice shell locksettings clear --old 246810 2>&1) -join "`n").Trim()
            $clearedVerification = ((& $adb -s $selectedDevice shell locksettings verify --old 246810 2>&1) -join "`n").Trim()
            if ($clearedVerification -notmatch 'user has no password') {
                Write-Warning "Could not remove the disposable emulator PIN from ${selectedDevice}: $clearPinResult $clearedVerification"
            } else {
                Write-Host "Removed the test device lock from $selectedDevice." -ForegroundColor Cyan
            }
            & $adb -s $selectedDevice shell svc power stayon false 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $previousErrorPreference
        }
    }
}

Write-Host "`nAll requested Olive tests passed ($($completedChecks.Count) checks):" -ForegroundColor Green
$completedChecks | ForEach-Object { Write-Host "  PASS  $_" -ForegroundColor Green }
Set-Content -LiteralPath (Join-Path $testReportDirectory "completed-test-checks.txt") -Value $completedChecks
Remove-Item -LiteralPath (Join-Path $testReportDirectory "current-test-check.txt") -Force -ErrorAction SilentlyContinue
