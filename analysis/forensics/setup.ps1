# Installs the PC-side forensics companions: MVT (Mobile Verification Toolkit) and ALEAPP
# (Android Logs Events And Protobuf Parser). Both are Python tools that analyze artifacts
# pulled FROM an Android device (backup/bugreport/intrusion logs) -- they don't run on the
# phone itself, so this is a one-time PC setup, separate from the HackCheck app.
#
# Uses separate virtual environments for each tool: MVT and ALEAPP pin conflicting versions
# of the "packaging" dependency, so installing both into the same environment leaves one of
# them on a version its own requirements.txt says is incompatible.
#
# Usage: powershell -ExecutionPolicy Bypass -File setup.ps1

$ErrorActionPreference = "Stop"
$ForensicsDir = $PSScriptRoot

Write-Host "=== Setting up MVT (Mobile Verification Toolkit) in its own venv ===" -ForegroundColor Cyan
$MvtVenv = Join-Path $ForensicsDir ".venv-mvt"
python -m venv $MvtVenv
& "$MvtVenv\Scripts\python.exe" -m pip install --upgrade pip --quiet
& "$MvtVenv\Scripts\python.exe" -m pip install --upgrade mvt
if ($LASTEXITCODE -ne 0) { throw "mvt install failed" }
Write-Host "Downloading current published indicators of compromise..." -ForegroundColor Cyan
& "$MvtVenv\Scripts\mvt-android.exe" download-iocs
Write-Host "mvt installed in $MvtVenv. Run it with: .\.venv-mvt\Scripts\mvt-android.exe ..." -ForegroundColor Green

Write-Host ""
Write-Host "=== Setting up ALEAPP (Android Logs Events And Protobuf Parser) in its own venv ===" -ForegroundColor Cyan
$AleappDir = Join-Path $ForensicsDir "ALEAPP"
if (Test-Path $AleappDir) {
    Write-Host "ALEAPP already cloned at $AleappDir -- pulling latest" -ForegroundColor Yellow
    git -C $AleappDir pull
} else {
    git clone --depth 1 https://github.com/abrignoni/ALEAPP.git $AleappDir
}
if ($LASTEXITCODE -ne 0) { throw "ALEAPP clone/pull failed" }

$AleappVenv = Join-Path $ForensicsDir ".venv-aleapp"
python -m venv $AleappVenv
& "$AleappVenv\Scripts\python.exe" -m pip install --upgrade pip --quiet
& "$AleappVenv\Scripts\python.exe" -m pip install -r (Join-Path $AleappDir "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "ALEAPP requirements install failed" }
Write-Host "ALEAPP installed in $AleappVenv. Run it with: .\.venv-aleapp\Scripts\python.exe ALEAPP\aleapp.py ..." -ForegroundColor Green

Write-Host ""
Write-Host "=== AndroidQF (acquisition tool) ===" -ForegroundColor Cyan
Write-Host "AndroidQF is a compiled binary, not a pip package -- download it manually:" -ForegroundColor Yellow
Write-Host "  https://github.com/mvt-project/androidqf/releases"
Write-Host "  Grab the Windows .exe, save it into: $ForensicsDir"
Write-Host ""
Write-Host "Setup done. See README.md in this folder for the acquisition + analysis workflow." -ForegroundColor Green
