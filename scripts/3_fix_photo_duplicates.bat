@echo off
cd /d "%~dp0"
chcp 65001 > nul

python 3_fix_photo_duplicates.py
pause
