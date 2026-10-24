# AW Clippy - Android

Lightweight clipboard sync between Android and Windows. Copy text on your phone, paste on your PC instantly.

## Features

- 🔄 Automatic instantaneous clipboard sync with Windows
- ⚡ **Persistent WebSocket Connection** (Zero polling, ultra-low latency & battery friendly)
- 🔒 Encrypted transmission (AES-256-GCM with PBKDF2/SHA-256)
- 🌐 Zero-config auto-discovery via mDNS (Android NSD)
- 📱 Android 10+ support
- 🔋 Minimal battery and data usage (idle socket waits silently for events)
- 🎯 Uses Accessibility Service for seamless clipboard detection & paste

## Requirements

- Android 10+ (API 29+)
- Same WiFi network / local subnet as Windows PC
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
- `AWCLIPPY_SYNC_PORT` - WebSocket sync port (default: 48900)

To change these settings, you must rebuild both Windows and Android apps.

## How It Works

1. **Discovery (mDNS)**: On startup, Android's Network Service Discovery (NSD) automatically discovers the Windows PC advertising `_awclippy._tcp` on your local network.
2. **Persistent WebSocket Link**: Instead of continuously polling or repeatedly opening and closing TCP sockets, Android establishes a single, lightweight persistent WebSocket connection (`ws://<pc-ip>:48900`) using OkHttp.
3. **Android → Windows**: When you copy text on Android, the Accessibility Service detects the change, encrypts the payload using AES-256-GCM, and immediately pushes it over the active WebSocket frame.
4. **Windows → Android**: When you copy text on Windows, Windows pushes the encrypted frame through the same WebSocket stream to Android. Android decrypts the payload and updates its clipboard.
5. **Zero Polling & Efficient Idle**: While idle, the WebSocket connection consumes virtually zero bandwidth and CPU, with automatic reconnection if the network changes.

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

## Links

- **GitHub Repository**: https://github.com/AW-clippy/moblie
- **Windows Client**: https://github.com/AW-clippy/windows

## License

MIT
