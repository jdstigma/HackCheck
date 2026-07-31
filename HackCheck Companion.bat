@echo off
REM Double-click to open the HackCheck Companion.
cd /d "%~dp0"
python hackcheck_companion.py
if errorlevel 1 pause
