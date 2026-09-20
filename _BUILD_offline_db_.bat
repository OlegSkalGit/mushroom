@echo off
setlocal enabledelayedexpansion
title Building Mushroom Offline Encyclopedia Database

echo ========================================================
echo   Mushroom - Offline SQLite Database Generator
echo ========================================================
echo.

:: 1. Check Python
where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Python is not found in PATH!
    echo Please install Python 3.8+ and ensure it is added to PATH.
    pause
    exit /b 1
)

:: 2. Ensure Required Python Libraries
echo [1/3] Verifying Python dependencies (requests, Pillow)...
python -c "import requests, PIL" >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Installing requests and Pillow...
    pip install requests Pillow tqdm
) else (
    echo [1/3] Dependencies are ready.
)

:: 3. Run Generator
echo [2/3] Starting offline database scraper...
echo       Input: classes.json + labels.txt
echo       Output: mushrooms_offline.db + mushrooms_offline.db.gz
echo       Note: You can stop anytime with Ctrl+C, progress is safely saved.
echo.

python scripts\build_offline_db.py --compress %*

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Generator finished with an error.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo   BUILD SUCCESSFUL - Database Created and Compressed
echo ========================================================
echo   Generated: mushrooms_offline.db
echo   Archive:   mushrooms_offline.db.gz
echo ========================================================
echo.
pause
