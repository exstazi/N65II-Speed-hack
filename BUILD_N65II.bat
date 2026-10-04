@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-n65ii.ps1"
set RC=%ERRORLEVEL%
if not "%RC%"=="0" pause
exit /b %RC%
