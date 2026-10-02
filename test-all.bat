@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Test-All.ps1" %*
exit /b %ERRORLEVEL%
