# Push-button forensics run: AndroidQF acquisition -> MVT analysis, in one script.
# Requires setup.ps1 to have been run first (venvs + ALEAPP present) and androidqf.exe
# to be in this folder (see README.md for the manual download step).
#
# AndroidQF's acquisition step may still show its own interactive prompts (backup scope,
# etc.) -- this script drives it with -fast to keep that minimal, but can't force full
# non-interactivity since that's not something the tool exposes as a flag.
#
# Usage: powershell -ExecutionPolicy Bypass -File run_forensics.ps1

$ErrorActionPreference = "Stop"
$ForensicsDir = $PSScriptRoot
$Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$AcquisitionDir = Join-Path $ForensicsDir "androidqf_output_$Stamp"
$MvtResultsDir = Join-Path $ForensicsDir "mvt_results_$Stamp"

$AndroidQF = Join-Path $ForensicsDir "androidqf.exe"
$MvtExe = Join-Path $ForensicsDir ".venv-mvt\Scripts\mvt-android.exe"

if (-not (Test-Path $AndroidQF)) { throw "androidqf.exe not found in $ForensicsDir -- see README.md" }
if (-not (Test-Path $MvtExe)) { throw "MVT venv not found -- run setup.ps1 first" }

# AndroidQF shells out to adb, but doesn't search common SDK install locations --
# make sure platform-tools is on PATH for this session.
$PlatformTools = "$env:LOCALAPPDATA\Android\Sdk\platform-tools"
if ((Get-Command adb -ErrorAction SilentlyContinue) -eq $null -and (Test-Path "$PlatformTools\adb.exe")) {
    $env:Path = "$PlatformTools;$env:Path"
    Write-Host "Added $PlatformTools to PATH for this run" -ForegroundColor Yellow
}

New-Item -ItemType Directory -Path $AcquisitionDir -Force | Out-Null

Write-Host "=== Step 1/2: Acquisition (AndroidQF) ===" -ForegroundColor Cyan
Write-Host "Output: $AcquisitionDir"
& $AndroidQF -output $AcquisitionDir -fast
if ($LASTEXITCODE -ne 0) { throw "androidqf acquisition failed (exit $LASTEXITCODE)" }

# The files (bugreport.zip, getprop.txt, files.json, etc.) land directly in -output --
# NOT in a nested subfolder. (An earlier version of this script wrongly assumed a nested
# "tmp" subfolder and pointed MVT at it, which is empty -- that caused every MVT module
# to report "file not found" even though the real acquisition succeeded.)
Write-Host "Acquisition data: $AcquisitionDir" -ForegroundColor Green

Write-Host ""
Write-Host "=== Step 2/2: Analysis (MVT) ===" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $MvtResultsDir -Force | Out-Null
& $MvtExe check-androidqf $AcquisitionDir -o $MvtResultsDir
Write-Host ""
Write-Host "Done. Results: $MvtResultsDir" -ForegroundColor Green
Write-Host "Raw acquisition kept at: $AcquisitionDir" -ForegroundColor Green
