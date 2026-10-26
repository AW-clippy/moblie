# AW Clippy - Android

Lightweight clipboard sync between Android and Windows. **Copy anywhere → Tap notification button → Done!** ⚡

## Features

- 🚀 **One-Tap Send from Notification** - No app switching required!
- 🔄 Automatic instantaneous clipboard sync with Windows
- ⚡ **Persistent WebSocket Connection** (Zero polling, ultra-low latency & battery friendly)
- 🔒 Encrypted transmission (AES-256-GCM with PBKDF2/SHA-256)
- 🌐 Zero-config auto-discovery via mDNS (Android NSD)
- 📱 Android 10+ support
- 🔋 Minimal battery and data usage (idle socket waits silently for events)
- 🎯 Uses Accessibility Service for seamless clipboard detection & paste
- 🎨 Clean Material Design notification interface

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
   - **You're done!** The app now runs in the background

## 🎯 Usage

### Android → Windows (The Easy Way)

**No more opening the app and pasting!** Now you can send from anywhere:

1. **Copy** text anywhere on Android (WhatsApp, Chrome, Instagram, SMS, etc.)
2. **Swipe down** your notification shade
3. **Tap "Send to PC"** button on the AWClippy notification
4. ✅ **Done!** Text appears on Windows immediately

The entire process takes ~50ms and you never leave your current app.

### Alternative Methods

**Text Selection Menu (Process Text):**
- Highlight text in any app
- Tap **"Send to PC"** in the selection menu
- Instant delivery to Windows

**In-App Send:**
- Open AWClippy app
- Paste text in the input field
- Tap Send button

### Windows → Android (Fully Automatic)

Copy anything on Windows and it appears instantly on your Android clipboard—no interaction needed!

## Configuration

**Important:** Configuration settings are compiled into the application at build time.

Edit `.env` file **before building**:

- `AWCLIPPY_SHARED_SECRET` - 32-byte encryption key (must match Windows)
- `AWCLIPPY_DISCOVERY_PORT` - mDNS discovery port (default: 48899)
- `AWCLIPPY_SYNC_PORT` - WebSocket sync port (default: 48900)

To change these settings, you must rebuild both Windows and Android apps.

## How It Works

### Discovery & Connection
1. **Discovery (mDNS)**: On startup, Android's Network Service Discovery (NSD) automatically discovers the Windows PC advertising `_awclippy._tcp` on your local network.
2. **Persistent WebSocket Link**: Instead of continuously polling or repeatedly opening and closing TCP sockets, Android establishes a single, lightweight persistent WebSocket connection (`ws://<pc-ip>:48900`) using OkHttp.

### Sending from Android (The Magic)
When you tap **"Send to PC"** in the notification:
1. An invisible transparent activity launches (takes ~50ms)
2. Activity gains window focus (required by Android 10+ to read clipboard)
3. Activity reads clipboard content programmatically
4. Content is encrypted with AES-256-GCM and pushed over WebSocket
5. Activity closes immediately
6. You remain in your original app—completely seamless!

### Receiving on Android (Fully Automatic)
1. **Windows → Android**: When you copy text on Windows, Windows pushes the encrypted frame through the WebSocket stream to Android
2. Android decrypts the payload and updates its clipboard automatically via Accessibility Service
3. **Zero Polling & Efficient Idle**: While idle, the WebSocket connection consumes virtually zero bandwidth and CPU, with automatic reconnection if the network changes

## Notification Features

The persistent notification provides:
- **Quick status** showing connection state and synchronized PC name
- **"Send to PC" button** - Instantly sends current clipboard to Windows
- **"Stop" button** - Stops the sync service
- **Material Design** clean, modern interface
- **Always visible buttons** in collapsed notification (no expanding needed!)

## Troubleshooting

- **Not syncing?** Ensure Accessibility is enabled and sync service is running
- **Not connecting?** Check both devices are on same WiFi network
- **Battery drain?** AW Clippy uses minimal resources; check other apps
- **"Send to PC" not working?** Ensure something is copied to clipboard first

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
