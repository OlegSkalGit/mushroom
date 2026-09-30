@echo off
cd /d "%~dp0"
chcp 65001 > nul

python 2_repair_offline_db.py
pause
