@echo off
cd /d "%~dp0"

python manage_db.py all
pause
