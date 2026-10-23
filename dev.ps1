#!/usr/bin/env pwsh
# AWClippy Android Developer Script
# One command to: build -> install -> launch -> watch logs

param(
    [switch]$SkipBuild,
    [switch]$AllDevices,
    [switch]$Help
)

if ($Help) {
    Write-Host @"
[*] AWClippy Android Developer Script

USAGE:
  .\dev.ps1           # Build, install, launch, and watch logs
  .\dev.ps1 -SkipBuild # Skip build, just launch and watch logs
  .\dev.ps1 -AllDevices # Install on all connected devices
  .\dev.ps1 -Help     # Show this help

WORKFLOW:
  1. Builds the debug APK (unless -SkipBuild)
  2. Installs it on your connected device(s)
  3. Launches the app
  4. Shows live logs (Ctrl+C to stop)

MULTI-DEVICE:
  • Automatically installs on all devices when multiple are connected
  • Use -AllDevices flag to explicitly install on all devices
  • Watches logs from the first device

TIPS:
  • Make sure your phone is connected via USB
  • Make sure USB debugging is enabled
  • Ensure Windows client is running for sync to work
  • Press Ctrl+C to stop watching logs
  • Run again to rebuild and restart

"@
    exit 0
}

$ErrorActionPreference = "Stop"

# Setup Android SDK paths consistently
$androidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$platformTools = Join-Path $androidSdk "platform-tools"

# Set ANDROID_HOME and PATH consistently to avoid Gradle daemon conflicts
$env:ANDROID_HOME = $androidSdk
$env:Path = "$platformTools;$env:Path"

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "     AWClippy Android Developer Mode           " -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Check Android SDK
Write-Host "[*] Checking Android SDK..." -ForegroundColor Yellow
if (-not (Test-Path $androidSdk)) {
    Write-Host "[X] Android SDK not found at $androidSdk" -ForegroundColor Red
    Write-Host "    Install Android Studio or SDK command-line tools" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] Android SDK found" -ForegroundColor Green
Write-Host ""

# Step 1: Build (unless skipped)
if (-not $SkipBuild) {
    Write-Host "[1/5] Building debug APK..." -ForegroundColor Yellow
    Write-Host ""
    
    $buildStart = Get-Date
    .\gradlew assembleDebug
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "[X] Build failed! Fix errors and try again." -ForegroundColor Red
        exit 1
    }
    
    $buildTime = ((Get-Date) - $buildStart).TotalSeconds
    Write-Host ""
    Write-Host "[OK] Build completed in $([math]::Round($buildTime, 1))s" -ForegroundColor Green
} else {
    Write-Host "[1/5] Skipping build (using existing APK)" -ForegroundColor Gray
}
Write-Host ""

# Step 2: Check device connection
Write-Host "[2/5] Checking device connection..." -ForegroundColor Yellow
$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
$deviceIds = @()
foreach ($line in $deviceLines) {
    if ($line -match "^(\S+)\s+device") {
        $deviceIds += $matches[1]
    }
}

if ($deviceIds.Count -eq 0) {
    Write-Host "[X] No Android device found! Connect your phone via USB." -ForegroundColor Red
    exit 1
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

# Step 3: Install APK
if (-not $SkipBuild) {
    Write-Host "[3/5] Installing APK to device(s)..." -ForegroundColor Yellow
    
    $installSuccess = $true
    foreach ($deviceId in $targetDevices) {
        Write-Host "  Installing on $deviceId..." -ForegroundColor DarkYellow
        adb -s $deviceId install -r app\build\outputs\apk\debug\app-debug.apk 2>&1 | Out-Null
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
    
    Write-Host "[OK] APK installed on all devices" -ForegroundColor Green
    Write-Host ""
} else {
    Write-Host "[3/5] Skipping installation (build was skipped)" -ForegroundColor Gray
    Write-Host ""
}

# Step 4: Launch the app
Write-Host "[4/5] Launching app..." -ForegroundColor Yellow
$primaryDevice = $targetDevices[0]
foreach ($deviceId in $targetDevices) {
    adb -s $deviceId shell am start -n com.awclippy.android/.MainActivity 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] App launched on $deviceId" -ForegroundColor Green
    } else {
        Write-Host "  [X] Failed to launch app on $deviceId" -ForegroundColor Red
    }
}
Write-Host ""

# Small delay to let app start
Start-Sleep -Milliseconds 500

# Step 5: Watch logs
Write-Host "[5/5] Watching live logs from $primaryDevice..." -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor DarkGray
Write-Host "                  LIVE LOGS                     " -ForegroundColor Cyan
Write-Host "         (Press Ctrl+C to stop watching)        " -ForegroundColor DarkGray
Write-Host "================================================" -ForegroundColor DarkGray
Write-Host ""

# Clear old logs first
adb -s $primaryDevice logcat -c 2>&1 | Out-Null

# Watch logs with highlighting
try {
    adb -s $primaryDevice logcat -s AWClippy:* AndroidRuntime:E | ForEach-Object {
        $line = $_
        
        # Colorize by content
        if ($line -match "connected|synced|Discovered|Responded") {
            Write-Host $line -ForegroundColor Green
        }
        elseif ($line -match "Error|Failed|Exception|FATAL") {
            Write-Host $line -ForegroundColor Red
        }
        elseif ($line -match "AWClippy") {
            Write-Host $line -ForegroundColor Magenta
        }
        elseif ($line -match "Received|Sent|Discovery") {
            Write-Host $line -ForegroundColor Cyan
        }
        elseif ($line -match "Warning|Attempting") {
            Write-Host $line -ForegroundColor Yellow
        }
        else {
            Write-Host $line -ForegroundColor White
        }
    }
}
catch {
    Write-Host ""
    Write-Host "[*] Logs stopped" -ForegroundColor Yellow
}
