@echo off
cd /d "%~dp0"

python scripts\manage_db.py all
pause
