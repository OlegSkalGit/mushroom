@echo off
setlocal
cd /d "%~dp0scripts"
python backup_maps.py --all
echo.
pause
