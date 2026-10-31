# Pulls recorded .fit files from a USB-connected Hammerhead Karoo 2 / Karoo 3 to Desktop\KarooFitFiles

$ErrorActionPreference = "Stop"
$AdbExe = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$DestDir = "$env:USERPROFILE\Desktop\KarooFitFiles"

if (-not (Test-Path $AdbExe)) {
    $AdbFromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($AdbFromPath) {
        $AdbExe = $AdbFromPath.Source
    } else {
        Write-Error "adb.exe not found at $AdbExe. Run .\run-emulator.ps1 first or install Android platform-tools."
    }
}

New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

Write-Host "Checking for connected Karoo..." -ForegroundColor Cyan
& $AdbExe wait-for-device

Write-Host "Pulling .fit files from /sdcard/FitFiles/ to $DestDir ..." -ForegroundColor Cyan
& $AdbExe pull "/sdcard/FitFiles/." $DestDir

Write-Host "`nDone! Your .fit files have been saved to: $DestDir" -ForegroundColor Green
Get-ChildItem -Path $DestDir -Filter *.fit | Sort-Object LastWriteTime -Descending | Select-Object -First 10 Name, Length, LastWriteTime
