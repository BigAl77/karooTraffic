# PowerShell script to install/launch the Windows Android Emulator (480x800 Karoo AVD)
# and deploy karooTraffic-debug.apk for interactive visual testing.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$JdkRoot = "$env:LOCALAPPDATA\Programs\Temurin21"
$CmdlineToolsDir = "$SdkRoot\cmdline-tools\latest"
$AvdName = "karoo_avd"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\karooTraffic-debug.apk"

if (-not (Test-Path "$JdkRoot\bin\java.exe")) {
    Write-Host "[1/5] Downloading Portable Temurin JDK 21 for Windows (via fast curl.exe)..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $JdkRoot | Out-Null
    $JdkZip = "$env:TEMP\jdk21-win.zip"
    $JdkExtract = "$env:TEMP\jdk21-extract"
    if (Test-Path $JdkExtract) { Remove-Item $JdkExtract -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $JdkExtract | Out-Null
    & curl.exe -L "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -o $JdkZip
    Write-Host "      Extracting JDK 21..." -ForegroundColor Gray
    & tar.exe -xf $JdkZip -C $JdkExtract
    $Inner = Get-ChildItem $JdkExtract | Select-Object -First 1
    Copy-Item "$($Inner.FullName)\*" $JdkRoot -Recurse -Force
    Remove-Item $JdkZip -Force -ErrorAction SilentlyContinue
    Remove-Item $JdkExtract -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "[1/5] JDK 21 already installed." -ForegroundColor Green
}

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

if (-not (Test-Path "$CmdlineToolsDir\bin\sdkmanager.bat")) {
    Write-Host "[2/5] Downloading Android SDK Command-Line Tools for Windows (via fast curl.exe)..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path "$SdkRoot\cmdline-tools" | Out-Null
    $ToolsZip = "$env:TEMP\cmdline-tools-win.zip"
    & curl.exe -L "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -o $ToolsZip
    Write-Host "      Extracting Command-Line Tools..." -ForegroundColor Gray
    if (Test-Path "$SdkRoot\cmdline-tools\cmdline-tools") { Remove-Item "$SdkRoot\cmdline-tools\cmdline-tools" -Recurse -Force }
    & tar.exe -xf $ToolsZip -C "$SdkRoot\cmdline-tools"
    if (Test-Path $CmdlineToolsDir) { Remove-Item $CmdlineToolsDir -Recurse -Force }
    Rename-Item "$SdkRoot\cmdline-tools\cmdline-tools" "latest"
    Remove-Item $ToolsZip -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "[2/5] Android SDK Command-Line Tools already installed." -ForegroundColor Green
}

$env:PATH = "$JdkRoot\bin;$CmdlineToolsDir\bin;$SdkRoot\platform-tools;$SdkRoot\emulator;$env:PATH"

if (-not (Test-Path "$SdkRoot\emulator\emulator.exe") -or -not (Test-Path "$SdkRoot\system-images\android-34\google_apis\x86_64")) {
    Write-Host "[3/5] Installing Android Emulator and API 34 system image (~1.5 GB download, first run only)..." -ForegroundColor Cyan
    cmd.exe /c "(echo y&echo y&echo y&echo y&echo y&echo y&echo y) | `"$CmdlineToolsDir\bin\sdkmanager.bat`" --licenses" | Out-Null
    & "$CmdlineToolsDir\bin\sdkmanager.bat" "platform-tools" "emulator" "platforms;android-34" "system-images;android-34;google_apis;x86_64"
} else {
    Write-Host "[3/5] Android Emulator & system image already installed." -ForegroundColor Green
}

$ExistingAvds = & "$SdkRoot\emulator\emulator.exe" -list-avds
if ($ExistingAvds -notcontains $AvdName) {
    Write-Host "[4/5] Creating Karoo AVD ($AvdName, 480x800 xhdpi)..." -ForegroundColor Cyan
    cmd.exe /c "echo no | `"$CmdlineToolsDir\bin\avdmanager.bat`" create avd -n $AvdName -k `"system-images;android-34;google_apis;x86_64`" --force"
    $ConfigIni = "$env:USERPROFILE\.android\avd\$AvdName.avd\config.ini"
    if (Test-Path $ConfigIni) {
        Add-Content $ConfigIni "`nhw.lcd.width=480`nhw.lcd.height=800`nhw.lcd.density=320`nhw.keyboard=yes"
    }
} else {
    Write-Host "[4/5] Karoo AVD ($AvdName) already exists." -ForegroundColor Green
}

$ConnectedDevices = & "$SdkRoot\platform-tools\adb.exe" devices | Select-String "emulator-"
if (-not $ConnectedDevices) {
    Write-Host "[5/5] Starting Karoo Android Emulator ($AvdName)..." -ForegroundColor Cyan
    Start-Process -FilePath "$SdkRoot\emulator\emulator.exe" -ArgumentList "-avd $AvdName"
    Write-Host "      Waiting for emulator to boot..." -ForegroundColor Gray
    & "$SdkRoot\platform-tools\adb.exe" wait-for-device
    do {
        Start-Sleep -Seconds 2
        $BootCompleted = (& "$SdkRoot\platform-tools\adb.exe" shell getprop sys.boot_completed 2>$null)
        if ($BootCompleted) { $BootCompleted = $BootCompleted.Trim() }
    } while ($BootCompleted -ne "1")
}

Write-Host "Installing karooTraffic-debug.apk..." -ForegroundColor Cyan
& "$SdkRoot\platform-tools\adb.exe" install -r $ApkPath

Write-Host "Launching KarooTraffic (MainActivity)..." -ForegroundColor Green
& "$SdkRoot\platform-tools\adb.exe" shell am start -n "com.mybiketraffic.karootraffic/.ui.MainActivity"
Write-Host "Done! KarooTraffic is running on the emulator." -ForegroundColor Green
