@echo off
setlocal enabledelayedexpansion
title Mushroom Offline DB - Quick UNIQUE Error Fixer

echo ========================================================
echo   Mushroom Offline DB - Quick UNIQUE Error Fixer
echo ========================================================
echo.

:: 1. Check Python
where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Python is not found in PATH!
    pause
    exit /b 1
)

echo [1/2] Launching Fast Duplicate Resolver & UNIQUE Fixer...
echo       - Auto-migrates database schema (removes inat_id UNIQUE)
echo       - Clones photos and descriptions from existing records instantly
echo       - Completes missing species
echo.

python scripts\build_offline_db_fix_UNIQUE_error.py %*

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Fixer finished with an error.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo   DONE! Missing species have been resolved and added.
echo ========================================================
echo.
pause
