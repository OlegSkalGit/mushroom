@echo off
cd /d "%~dp0"
chcp 65001 > nul

python 4_count_mushrooms.py
pause
