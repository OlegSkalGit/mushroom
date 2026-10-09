@echo off
setlocal
cd /d "%~dp0scripts"
python backup_maps.py %*
echo.
pause
