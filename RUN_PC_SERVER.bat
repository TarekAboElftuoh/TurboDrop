@echo off
title TurboDrop PC Server
cd /d "%~dp0"

:: Clean up any previous background instances holding ports 5050 or 8080
for /f "tokens=5" %%a in ('netstat -aon 2^>nul ^| findstr :5050 ^| findstr LISTENING') do (
    if not "%%a"=="0" taskkill /F /PID %%a >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -aon 2^>nul ^| findstr :8080 ^| findstr LISTENING') do (
    if not "%%a"=="0" taskkill /F /PID %%a >nul 2>&1
)

echo ========================================================
echo   TurboDrop PC - Ultra-Fast Continuous Stream Server
echo ========================================================

if exist "TurboDrop.exe" (
    start "" "TurboDrop.exe"
) else (
    cd pc_app
    python turbo_pc.py
    if %ERRORLEVEL% NEQ 0 (
        echo.
        echo Server ended.
        pause
    )
)
