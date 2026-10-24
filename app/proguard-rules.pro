# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep only essential AWClippy classes (not all internal details)
-keep class com.awclippy.android.ClipboardSyncService { *; }
-keep class com.awclippy.android.ClipboardAccessibilityService { *; }
-keep class com.awclippy.android.ClipboardCrypto { *; }
-keep class com.awclippy.android.MainActivity { *; }
-keep class com.awclippy.android.ProcessTextActivity { *; }

# Keep BuildConfig
-keep class com.awclippy.android.BuildConfig { *; }

# Keep Accessibility Service (Android requires exact names)
-keep class * extends android.accessibilityservice.AccessibilityService {
    public <init>(...);
    public void onAccessibilityEvent(...);
    public void onInterrupt();
}

# Keep Services (Android requires exact names)
-keep class * extends android.app.Service {
    public <init>(...);
    public android.os.IBinder onBind(...);
}

# Keep Activities
-keep class * extends android.app.Activity {
    public <init>(...);
}

# Keep coroutines (minimal)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Keep NSD callbacks (used for network discovery)
-keep class android.net.nsd.NsdManager$* { *; }

# Allow R8 to optimize everything else
-dontwarn java.net.**
-dontwarn java.nio.**

# Don't optimize certain sensitive classes
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Remove logging in release (but keep error logs)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
