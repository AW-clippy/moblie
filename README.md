# AW Clippy - Android

Lightweight clipboard sync between Android and Windows. Copy text on your phone, paste on your PC instantly.

## Features

- 🔄 Automatic clipboard sync with Windows
- 🔒 Encrypted transmission (AES-256-GCM)
- 🌐 Auto-discovery via mDNS
- 📱 Android 10+ support
- ⚡ Minimal battery and data usage
- 🎯 Uses Accessibility Service for automatic copy detection

## Requirements

- Android 10+ (API 29+)
- Same WiFi network as Windows PC
- Accessibility permission (for automatic clipboard detection)

## Quick Start

1. **Configure**
   ```bash
   cp .env.example .env
   ```
   Edit `.env` and set your shared secret (must match Windows app)

2. **Build**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Install** the APK from `app/build/outputs/apk/release/`

4. **Enable Accessibility**
   - Open AW Clippy
   - Tap "Enable Accessibility"
   - Find and enable AW Clippy in system settings

5. **Start Service**
   - Return to app
   - Tap "Start Sync Service"

## Configuration

**Important:** Configuration settings are compiled into the application at build time.

Edit `.env` file **before building**:

- `AWCLIPPY_SHARED_SECRET` - 32-byte encryption key (must match Windows)
- `AWCLIPPY_DISCOVERY_PORT` - mDNS discovery port (default: 48899)
- `AWCLIPPY_SYNC_PORT` - Clipboard sync port (default: 48900)

To change these settings, you must rebuild both Windows and Android apps.

## How It Works

1. Accessibility Service detects when you copy text
2. Text is encrypted and sent to Windows PC
3. Windows receives and decrypts to its clipboard
4. Works both ways automatically

## Troubleshooting

- **Not syncing?** Ensure Accessibility is enabled and sync service is running
- **Not connecting?** Check both devices are on same WiFi network
- **Battery drain?** AW Clippy uses minimal resources; check other apps

## Building from Source

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Security

All clipboard data is encrypted using AES-256-GCM. Set a strong 32-character secret in your `.env` file before building.

## Permissions

- **Accessibility** - Detects clipboard changes automatically
- **Internet** - Communicates with Windows PC over local network
- **Foreground Service** - Keeps sync running in background
- **Network State** - Discovers Windows PC on WiFi
