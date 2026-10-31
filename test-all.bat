@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Test-All.ps1" %*
set "OLIVE_TEST_EXIT=%ERRORLEVEL%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Write-TestReport.ps1" -TestExitCode %OLIVE_TEST_EXIT%
echo.
echo Test report: %~dp0test-reports\olive-test-summary.txt
exit /b %OLIVE_TEST_EXIT%
