param(
    [int]$TestExitCode = 0
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $projectRoot "build\reports"
$summaryDirectory = Join-Path $projectRoot "test-reports"
$reportPath = Join-Path $summaryDirectory "olive-test-summary.txt"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $summaryDirectory | Out-Null

$results = [System.Collections.Generic.List[object]]::new()
$testSourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "app\src\test") -Recurse -File -ErrorAction SilentlyContinue) +
    @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "app\src\androidTest") -Recurse -File -ErrorAction SilentlyContinue) +
    @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "web") -Recurse -File -Include "*.test.ts", "*.test.mjs", "*.spec.ts" -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch "node_modules" }) +
    @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "functions") -File -Filter "*.test.js" -ErrorAction SilentlyContinue) +
    @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "firebase") -File -Include "*.test.mjs", "*-plans.test.mjs" -ErrorAction SilentlyContinue)

function Find-TestDescription {
    param([object]$Test)
    foreach ($file in $testSourceFiles) {
        $lines = [IO.File]::ReadAllLines($file.FullName)
        for ($index = 0; $index -lt $lines.Length; $index++) {
            $line = $lines[$index]
            $matchesName = $line -match "fun\s+``?$([regex]::Escape($Test.Name))``?\s*\(" -or
                $line.Contains("test(`"$($Test.Name)`"") -or $line.Contains("it(`"$($Test.Name)`"")
            if (-not $matchesName) { continue }
            for ($previous = $index - 1; $previous -ge [Math]::Max(0, $index - 4); $previous--) {
                $candidate = $lines[$previous].Trim()
                if ($candidate.StartsWith("//")) { return $candidate.TrimStart('/').Trim() }
            }
        }
    }
    return ""
}

function Add-JUnitResults {
    param(
        [string]$Area,
        [string[]]$Paths
    )
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) { continue }
        try {
            [xml]$document = Get-Content -LiteralPath $path -Raw
            foreach ($case in $document.SelectNodes("//testcase")) {
                $failure = $case.SelectSingleNode("failure|error")
                $skipped = $case.SelectSingleNode("skipped")
                $status = if ($failure) { "FAILED" } elseif ($skipped) { "SKIPPED" } else { "PASSED" }
                $details = ""
                if ($failure) {
                    $detailParts = @($failure.GetAttribute("message"), $failure.InnerText) |
                        Where-Object { $_ } | Select-Object -Unique
                    $details = [string]::Join("`n", $detailParts)
                }
                $results.Add([pscustomobject]@{
                    Area = $Area
                    Name = [string]$case.name
                    ClassName = [string]$case.classname
                    Status = $status
                    Time = [string]$case.time
                    Details = $details.Trim()
                })
            }
        } catch {
            $results.Add([pscustomobject]@{
                Area = $Area
                Name = "Could not parse $path"
                ClassName = "reporting"
                Status = "FAILED"
                Time = ""
                Details = $_.Exception.Message
            })
        }
    }
}

$androidFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "app\build\test-results\testDebugUnitTest") -Filter "TEST-*.xml" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
Add-JUnitResults "Android JVM" $androidFiles
Add-JUnitResults "Web unit" @((Join-Path $reportDirectory "web-unit-tests.xml"))
Add-JUnitResults "Web browser/accessibility" @((Join-Path $reportDirectory "web-browser-tests.xml"))
Add-JUnitResults "Cloud Functions" @((Join-Path $reportDirectory "functions-tests.xml"))
Add-JUnitResults "Firebase rules" @((Join-Path $reportDirectory "firebase-rules-tests.xml"))
$deviceUiFiles = @(Get-ChildItem -LiteralPath (Join-Path $reportDirectory "device-ui-tests") -Filter "*.xml" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
Add-JUnitResults "Android device UI" $deviceUiFiles

$failedCheckPath = Join-Path $reportDirectory "failed-test-check.txt"
$currentCheckPath = Join-Path $reportDirectory "current-test-check.txt"
$failedCheckOutputPath = Join-Path $reportDirectory "failed-test-output.txt"
$failedCheck = if (Test-Path -LiteralPath $failedCheckPath) { Get-Content -LiteralPath $failedCheckPath -Raw } elseif ($TestExitCode -ne 0 -and (Test-Path -LiteralPath $currentCheckPath)) { Get-Content -LiteralPath $currentCheckPath -Raw } else { "" }
$failedCheckParts = $failedCheck.Trim() -split '\|', 2
$failedCheckLabel = if ($failedCheckParts.Count) { $failedCheckParts[0].Trim() } else { "" }
$failedCheckExitCode = if ($failedCheckParts.Count -gt 1) { $failedCheckParts[1].Trim() } else { "" }
$passed = @($results | Where-Object Status -eq "PASSED")
$failed = @($results | Where-Object Status -eq "FAILED")
$skipped = @($results | Where-Object Status -eq "SKIPPED")
$failedDeviceUiTests = @($failed | Where-Object { $_.Area -eq "Android device UI" })
$failedCheckIsRecordedUiTest = $failedCheckLabel.StartsWith("Android device UI test ") -and
    $failedDeviceUiTests.Count -gt 0
$failedCheckCount = if ($TestExitCode -ne 0 -and $failedCheckLabel -and -not $failedCheckIsRecordedUiTest) { 1 } else { 0 }

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("OLIVE BRANCH TEST REPORT")
$lines.Add("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')")
$lines.Add("Command exit code: $TestExitCode")
$lines.Add("Result: $(if ($TestExitCode -eq 0 -and $failed.Count -eq 0) { 'PASSED' } else { 'FAILED' })")
if ($failedCheckLabel -and -not $failedCheckIsRecordedUiTest) {
    $failedCheckText = if ($failedCheckExitCode) { "$failedCheckLabel (exit code $failedCheckExitCode)" } else { $failedCheckLabel }
    $lines.Add("Failed check: $failedCheckText")
}
$lines.Add("")
$lines.Add("SUMMARY")
$lines.Add("Passed tests: $($passed.Count)")
$lines.Add("Failed tests: $($failed.Count)")
$lines.Add("Failed setup/build checks: $failedCheckCount")
$lines.Add("Skipped tests: $($skipped.Count)")
$lines.Add("")

foreach ($group in ($results | Group-Object Area)) {
    $groupPassed = @($group.Group | Where-Object Status -eq "PASSED").Count
    $groupFailed = @($group.Group | Where-Object Status -eq "FAILED").Count
    $groupSkipped = @($group.Group | Where-Object Status -eq "SKIPPED").Count
    $lines.Add("$($group.Name): $groupPassed passed, $groupFailed failed, $groupSkipped skipped")
}

$lines.Add("")
$lines.Add("FAILED TEST DETAILS")
if ($failed.Count -eq 0 -and $failedCheckCount -eq 0) {
    $lines.Add("None.")
} else {
    if ($failedCheckCount) {
        $lines.Add("FAIL [Test setup/build] $failedCheckLabel")
        if ($failedCheckExitCode) { $lines.Add("Process exit code: $failedCheckExitCode") }
        if (Test-Path -LiteralPath $failedCheckOutputPath) {
            $lines.Add("Command output:")
            $lines.Add((Get-Content -LiteralPath $failedCheckOutputPath -Raw).Trim())
        }
        $lines.Add("This check stopped the run before later tests could execute.")
        $lines.Add("")
    }
    foreach ($test in $failed) {
        $lines.Add("FAIL [$($test.Area)] $($test.ClassName) :: $($test.Name)")
        $scenario = Find-TestDescription $test
        if ($scenario) { $lines.Add("Scenario: $scenario") }
        if ($test.Details) {
            $lines.Add("Framework comparison and stack trace:")
            $lines.Add($test.Details)
        } else {
            $lines.Add("No expected/actual comparison was supplied by the test framework.")
        }
        $lines.Add("")
    }
}

$lines.Add("")
$lines.Add("PASSED TESTS")
foreach ($test in $passed) {
    $lines.Add("PASS [$($test.Area)] $($test.ClassName) :: $($test.Name)")
}
if ($skipped.Count) {
    $lines.Add("")
    $lines.Add("SKIPPED TESTS")
    foreach ($test in $skipped) {
        $lines.Add("SKIP [$($test.Area)] $($test.ClassName) :: $($test.Name)")
    }
}

Set-Content -LiteralPath $reportPath -Value $lines -Encoding utf8
Write-Host "`nDetailed test report written to $reportPath" -ForegroundColor Cyan
