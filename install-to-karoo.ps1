# Installs karooTraffic-debug.apk onto a USB-connected Hammerhead Karoo 2 / Karoo 3

$ErrorActionPreference = "Stop"
$AdbExe = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\karooTraffic-debug.apk"

if (-not (Test-Path $AdbExe)) {
    $AdbFromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($AdbFromPath) {
        $AdbExe = $AdbFromPath.Source
    } else {
        Write-Error "adb.exe not found at $AdbExe. Run .\run-emulator.ps1 first or install Android platform-tools."
    }
}

Write-Host "Checking for connected Karoo device via USB..." -ForegroundColor Cyan
& $AdbExe devices -l

Write-Host "`nInstalling KarooTraffic onto connected device..." -ForegroundColor Cyan
& $AdbExe install -r $ApkPath

Write-Host "Launching KarooTraffic settings screen..." -ForegroundColor Cyan
& $AdbExe shell am start -n "com.mybiketraffic.karootraffic/.ui.MainActivity"

Write-Host "`nSuccess! KarooTraffic (MyBikeTraffic) is now installed on your Karoo." -ForegroundColor Green
