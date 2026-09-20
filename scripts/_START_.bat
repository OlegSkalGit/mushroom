@echo off
cd /d "%~dp0"
chcp 1251 > nul

"python.exe" build_offline_db.py
