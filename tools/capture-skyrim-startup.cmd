@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0capture-skyrim-startup.ps1" %*
pause
