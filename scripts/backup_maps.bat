@echo off
setlocal
cd /d "%~dp0"
python backup_maps.py %*
echo.
pause
