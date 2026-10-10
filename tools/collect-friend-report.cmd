@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect-friend-report.ps1"
if errorlevel 1 echo Collection failed. Read the message above.
pause
