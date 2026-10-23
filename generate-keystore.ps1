#!/usr/bin/env pwsh
# Generate Android Keystore for signing release APKs

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "========================================================"
Write-Host "   Generate Android Keystore for AWClippy              "
Write-Host "========================================================"
Write-Host ""

$keystorePath = "keystore\awclippy-release.keystore"
$keystoreDir = Split-Path $keystorePath -Parent

# Create keystore directory if it doesn't exist
if (-not (Test-Path $keystoreDir)) {
    New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null
    Write-Host "[*] Created keystore directory: $keystoreDir"
}

# Check if keystore already exists
if (Test-Path $keystorePath) {
    Write-Host "[!] Keystore already exists at: $keystorePath"
    $overwrite = Read-Host "Do you want to overwrite it? (yes/no)"
    if ($overwrite -ne "yes") {
        Write-Host "[*] Keeping existing keystore. Exiting..."
        exit 0
    }
    Remove-Item $keystorePath -Force
}

Write-Host ""
Write-Host "Please provide the following information for your keystore:"
Write-Host ""

# Prompt for keystore details
$storePassword = Read-Host "Keystore password (min 6 characters)" -AsSecureString
$storePasswordPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePassword))

$keyPassword = Read-Host "Key password (min 6 characters)" -AsSecureString  
$keyPasswordPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPassword))

$alias = Read-Host "Key alias (e.g., awclippy-key)"
$commonName = Read-Host "Your name or organization"
$orgUnit = Read-Host "Organizational unit (optional, press Enter to skip)"
$org = Read-Host "Organization name (optional, press Enter to skip)"
$city = Read-Host "City (optional, press Enter to skip)"
$state = Read-Host "State/Province (optional, press Enter to skip)"
$country = Read-Host "Country code (e.g., US, press Enter to skip)"

# Build DN string
$dnParts = @()
$dnParts += "CN=$commonName"
if ($orgUnit) { $dnParts += "OU=$orgUnit" }
if ($org) { $dnParts += "O=$org" }
if ($city) { $dnParts += "L=$city" }
if ($state) { $dnParts += "ST=$state" }
if ($country) { $dnParts += "C=$country" }
$dname = $dnParts -join ", "

Write-Host ""
Write-Host "[*] Generating keystore..."

# Find keytool from JAVA_HOME or common JDK locations
$keytoolPath = $null

if ($env:JAVA_HOME) {
    $keytoolPath = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (-not (Test-Path $keytoolPath)) {
        $keytoolPath = $null
    }
}

if (-not $keytoolPath) {
    # Try to find keytool in common locations
    $commonPaths = @(
        "C:\Program Files\Java\*\bin\keytool.exe",
        "C:\Program Files (x86)\Java\*\bin\keytool.exe",
        "$env:LOCALAPPDATA\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe"
    )
    
    foreach ($pattern in $commonPaths) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $keytoolPath = $found.FullName
            break
        }
    }
}

if (-not $keytoolPath) {
    Write-Host "[X] keytool not found!"
    Write-Host ""
    Write-Host "keytool is part of the Java JDK. Please install JDK or set JAVA_HOME."
    Write-Host ""
    Write-Host "Options:"
    Write-Host "1. Install JDK from: https://adoptium.net/"
    Write-Host "2. Or use Android Studio's JDK by setting JAVA_HOME to Android Studio's jbr folder"
    Write-Host ""
    Write-Host "Example (Android Studio):"
    Write-Host '  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"'
    Write-Host ""
    exit 1
}

Write-Host "[*] Using keytool from: $keytoolPath"

# Generate keystore using keytool
try {
    & $keytoolPath -genkeypair -v `
        -keystore $keystorePath `
        -alias $alias `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass $storePasswordPlain `
        -keypass $keyPasswordPlain `
        -dname $dname
    
    if (-not (Test-Path $keystorePath)) {
        throw "Keystore file was not created"
    }
    Write-Host "[OK] Keystore generated successfully!"
    Write-Host ""
    Write-Host "Keystore location: $keystorePath"
    Write-Host "Key alias: $alias"
    Write-Host ""
    
    # Create or update keystore.properties file
    $propertiesPath = "keystore.properties"
    $propertiesContent = @"
storePassword=$storePasswordPlain
keyPassword=$keyPasswordPlain
keyAlias=$alias
storeFile=keystore/awclippy-release.keystore
"@
    
    Set-Content -Path $propertiesPath -Value $propertiesContent
    Write-Host "[OK] Created keystore.properties file"
    Write-Host ""
    Write-Host "IMPORTANT: Keep keystore.properties and the keystore file secure!"
    Write-Host "    These files are gitignored to prevent accidental commits."
    Write-Host ""
    Write-Host "Setup complete! You can now build signed release APKs."
    
} catch {
    $errorMsg = $_.Exception.Message
    Write-Host "[X] Failed to generate keystore"
    Write-Host "Error: $errorMsg"
    exit 1
}
