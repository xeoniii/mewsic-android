# Mewsic Fast Test & Launch Script
# Usage: ./testapp or .\testapp.ps1

$ErrorActionPreference = "Stop"

# Ensure Java 21 is used (Android Gradle Plugin requires Java 17-21)
if (Test-Path "C:\Work\jdk-21") {
    $env:JAVA_HOME = "C:\Work\jdk-21"
    $env:Path = "C:\Work\jdk-21\bin;" + $env:Path
}

# 1. Resolve ADB path
$adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\platform-tools\adb.exe") {
        $adb = "C:\platform-tools\adb.exe"
    } else {
        Write-Host "[X] adb was not found in PATH or C:\platform-tools" -ForegroundColor Red
        exit 1
    }
}

# 2. Check for connected device
Write-Host "==> Checking for connected Android device..." -ForegroundColor Cyan
$devices = & $adb devices | Where-Object { $_ -match '\tdevice$' }

if (-not $devices) {
    Write-Host "[!] No authorized device found!" -ForegroundColor Red
    Write-Host "    - Plug your phone via USB" -ForegroundColor Yellow
    Write-Host "    - Ensure 'USB Debugging' is enabled in Developer Options" -ForegroundColor Yellow
    Write-Host "    - Accept the 'Allow USB debugging' prompt on your phone screen" -ForegroundColor Yellow
    exit 1
}

$deviceCount = ($devices | Measure-Object).Count
Write-Host "[+] Found $deviceCount connected device(s)." -ForegroundColor Green

# 3. Build APK
Write-Host "==> Compiling Mewsic (Debug)..." -ForegroundColor Cyan
& ".\gradlew.bat" assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] Build failed! Check the Gradle errors above." -ForegroundColor Red
    exit $LASTEXITCODE
}

# 4. Install APK onto phone
$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "[X] APK not found at $apkPath" -ForegroundColor Red
    exit 1
}

Write-Host "==> Installing APK onto device..." -ForegroundColor Cyan
& $adb install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] Installation failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 5. Launch App Instantly
Write-Host "==> Launching Mewsic instantly..." -ForegroundColor Green
& $adb shell am start -n com.mewsic.app/.MainActivity

Write-Host ""
Write-Host "[SUCCESS] Mewsic is running on your phone!" -ForegroundColor Green
