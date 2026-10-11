@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-friend-installation.ps1" %*
if errorlevel 1 echo Check failed. Read the message above.
pause
