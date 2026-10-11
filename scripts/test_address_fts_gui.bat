@echo off
cd /d "%~dp0"
chcp 65001 > nul

python test_address_fts_gui.py
