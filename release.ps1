#!/usr/bin/env pwsh
# AWClippy Android Release Build Script
# Build and install optimized RELEASE version

param(
    [switch]$AllDevices,
    [switch]$Sign,
    [switch]$Help
)

if ($Help) {
    Write-Host @"
[*] AWClippy Android RELEASE Build Script

USAGE:
  .\release.ps1           # Build release APK and install on connected device(s)
  .\release.ps1 -AllDevices # Explicitly install on all connected devices
  .\release.ps1 -Sign     # Sign APK with keystore (requires keystore setup)
  .\release.ps1 -Help     # Show this help

WORKFLOW:
  1. Builds the optimized release APK
  2. Optionally signs the APK for distribution
  3. Saves a release-specific APK copy
  4. Installs it on your connected device(s) if any
  5. Launches the app

MULTI-DEVICE:
  • Automatically installs on all devices when multiple are connected
  • Use -AllDevices flag to explicitly install on all devices
  • If no device is connected, APK is saved for distribution

SIGNING:
  • Use -Sign flag to sign the APK with your keystore
  • Requires keystore.properties file with signing configuration

"@
    exit 0
}

$ErrorActionPreference = "Stop"

# Setup Android SDK paths consistently
$androidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$platformTools = Join-Path $androidSdk "platform-tools"
$env:ANDROID_HOME = $androidSdk
$env:Path = "$platformTools;$env:Path"

Write-Host ""
Write-Host "========================================================" -ForegroundColor Magenta
Write-Host "   Building AWClippy Android RELEASE                    " -ForegroundColor Magenta
Write-Host "========================================================" -ForegroundColor Magenta
Write-Host ""

# Check Android SDK
Write-Host "[*] Checking Android SDK..." -ForegroundColor Yellow
if (-not (Test-Path $androidSdk)) {
    Write-Host "[X] Android SDK not found at $androidSdk" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Android SDK found" -ForegroundColor Green
Write-Host ""

Write-Host "[*] Building optimized release APK..." -ForegroundColor Yellow
Write-Host ""

$buildStart = Get-Date
.\gradlew assembleRelease

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[X] Release build failed!" -ForegroundColor Red
    exit 1
}

$buildTime = ((Get-Date) - $buildStart).TotalSeconds
Write-Host ""
Write-Host "[OK] Release build completed in $([math]::Round($buildTime, 1))s" -ForegroundColor Green
Write-Host ""

# Find the APK
$apkPath = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkPath)) {
    # Try unsigned APK
    $apkPath = "app\build\outputs\apk\release\app-release-unsigned.apk"
    if (-not (Test-Path $apkPath)) {
        Write-Host "[X] Release APK not found" -ForegroundColor Red
        exit 1
    }
}

# Get APK size
$apkSize = (Get-Item $apkPath).Length
$sizeMB = [math]::Round($apkSize / 1MB, 2)
Write-Host "[OK] APK size: $sizeMB MB" -ForegroundColor Green

# Copy to release location
$releaseApkPath = "app\build\outputs\apk\release\awclippy-release.apk"
Copy-Item $apkPath $releaseApkPath -Force
Write-Host "[OK] Release APK saved: $releaseApkPath" -ForegroundColor Green
Write-Host ""

# Signing (if requested)
if ($Sign) {
    Write-Host "[*] Signing APK..." -ForegroundColor Yellow
    
    $keystoreProps = "keystore.properties"
    if (-not (Test-Path $keystoreProps)) {
        Write-Host "[!] keystore.properties not found" -ForegroundColor Yellow
        Write-Host "[!] Skipping signing - APK is unsigned" -ForegroundColor Yellow
        Write-Host ""
    } else {
        # TODO: Implement signing with jarsigner or apksigner
        Write-Host "[!] Automatic signing not yet implemented" -ForegroundColor Yellow
        Write-Host "[!] Use Android Studio or manual signing" -ForegroundColor Yellow
        Write-Host ""
    }
}

# Check if device is connected
Write-Host "[*] Checking device connection..." -ForegroundColor Yellow
$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
$deviceIds = @()
foreach ($line in $deviceLines) {
    if ($line -match "^(\S+)\s+device") {
        $deviceIds += $matches[1]
    }
}

if ($deviceIds.Count -eq 0) {
    Write-Host "[!] No device found - APK ready for distribution" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "========================================================" -ForegroundColor Magenta
    Write-Host "  RELEASE APK READY FOR DISTRIBUTION                   " -ForegroundColor Magenta
    Write-Host "  Location: $releaseApkPath" -ForegroundColor Magenta
    Write-Host "  Size: $sizeMB MB                                      " -ForegroundColor Magenta
    Write-Host "========================================================" -ForegroundColor Magenta
    exit 0
}

if ($deviceIds.Count -eq 1) {
    Write-Host "[OK] Device connected: $($deviceIds[0])" -ForegroundColor Green
    $targetDevices = $deviceIds
} else {
    Write-Host "[OK] Multiple devices connected: $($deviceIds.Count) devices" -ForegroundColor Green
    foreach ($id in $deviceIds) {
        Write-Host "    - $id" -ForegroundColor Cyan
    }
    $targetDevices = $deviceIds
}
Write-Host ""

# Install the release APK
Write-Host "[*] Installing release APK on device(s)..." -ForegroundColor Yellow
$installSuccess = $true
foreach ($deviceId in $targetDevices) {
    Write-Host "  Uninstalling old version on $deviceId..." -ForegroundColor DarkYellow
    adb -s $deviceId uninstall com.awclippy.android 2>&1 | Out-Null
    
    Write-Host "  Installing on $deviceId..." -ForegroundColor DarkYellow
    adb -s $deviceId install $releaseApkPath 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Installed on $deviceId" -ForegroundColor Green
    } else {
        Write-Host "  [X] Failed to install on $deviceId" -ForegroundColor Red
        $installSuccess = $false
    }
}

if (-not $installSuccess) {
    Write-Host "[X] Failed to install APK on one or more devices" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] Release APK installed on all devices" -ForegroundColor Green
Write-Host ""

# Launch the app
Write-Host "[*] Launching app..." -ForegroundColor Yellow
foreach ($deviceId in $targetDevices) {
    adb -s $deviceId shell am start -n com.awclippy.android/.MainActivity 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] App launched on $deviceId" -ForegroundColor Green
    } else {
        Write-Host "  [X] Failed to launch app on $deviceId" -ForegroundColor Red
    }
}
Write-Host ""

Write-Host "========================================================" -ForegroundColor Green
Write-Host "             INSTALLATION SUCCESSFUL                    " -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host ""
Write-Host "Release Build Details:" -ForegroundColor White
Write-Host "  • APK: $releaseApkPath" -ForegroundColor Cyan
Write-Host "  • Size: $sizeMB MB (minified & optimized)" -ForegroundColor Cyan
Write-Host "  • ProGuard: Enabled" -ForegroundColor Cyan
Write-Host ""
Write-Host "The app is now running on your device." -ForegroundColor White
Write-Host "Use .\dev.ps1 for development builds with logs." -ForegroundColor DarkGray
Write-Host ""
