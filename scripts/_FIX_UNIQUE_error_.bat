@echo off
cd /d "%~dp0"
chcp 65001 > nul

python build_offline_db_fix_UNIQUE_error.py %*
pause
