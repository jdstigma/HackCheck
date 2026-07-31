@echo off
REM Build HackCheck Companion.exe from hackcheck_companion.py using PyInstaller.
REM Double-click this file. First build downloads tools and can take a few minutes.
cd /d "%~dp0"

echo Installing build tools (pyinstaller, pandas, matplotlib)...
python -m pip install --upgrade pyinstaller pandas matplotlib || goto :err

echo.
echo Building HackCheck Companion.exe ...
pyinstaller --onefile --windowed --name "HackCheck Companion" ^
  --paths analysis ^
  --hidden-import analyze_scan --hidden-import analyze_capture ^
  --collect-all pandas --collect-all matplotlib ^
  hackcheck_companion.py || goto :err

echo.
echo ============================================================
echo  Done. Your app is:  dist\HackCheck Companion.exe
echo  Keep it inside this HackCheck folder so it can find the
echo  analysis subfolder (scripts + forensics tools).
echo ============================================================
pause
exit /b 0

:err
echo.
echo Build failed. Make sure Python is installed and on PATH.
pause
exit /b 1
