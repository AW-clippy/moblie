# Android APK Signing Setup

To build a signed release APK that can be installed on Android devices, you need to generate a keystore and configure signing.

## Quick Setup

1. **Generate a keystore:**
   ```powershell
   .\generate-keystore.ps1
   ```

   Follow the prompts to create your keystore. You'll need to provide:
   - Keystore password (keep this secure!)
   - Key password
   - Key alias (e.g., `awclippy-key`)
   - Your name or organization
   - Optional: organizational details

2. **Build the signed release APK:**
   ```powershell
   .\release.ps1
   ```

   The script will now build and sign the APK automatically.

## What Gets Created

- `keystore/awclippy-release.keystore` - Your signing certificate (keep secure!)
- `keystore.properties` - Configuration file with passwords (gitignored)

**⚠️ IMPORTANT:** Keep these files secure and backed up! If you lose your keystore, you cannot update your app in the future.

## Manual Signing (Alternative)

If you prefer to build without automatic signing:

1. Build an unsigned APK:
   ```powershell
   .\gradlew assembleRelease
   ```

2. Sign it manually:
   ```powershell
   jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 `
     -keystore keystore/awclippy-release.keystore `
     app/build/outputs/apk/release/app-release-unsigned.apk `
     your-key-alias
   ```

3. Align the APK:
   ```powershell
   zipalign -v 4 app/build/outputs/apk/release/app-release-unsigned.apk `
     app/build/outputs/apk/release/awclippy-release.apk
   ```

## Troubleshooting

### "INSTALL_PARSE_FAILED_NO_CERTIFICATES" Error

This means the APK is not signed. Run `.\generate-keystore.ps1` first, then rebuild.

### Lost Keystore Password

If you forget your keystore password, you'll need to generate a new keystore. This means:
- You cannot update existing installations
- Users must uninstall and reinstall
- You get a new package signature

**Always back up your keystore and passwords!**

## For Development/Testing

If you just want to test the app without release signing, build a debug APK instead:

```powershell
.\gradlew assembleDebug
```

Debug APKs are automatically signed with a debug certificate and can be installed directly.
