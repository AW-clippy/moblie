# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep all AWClippy classes (they contain network and service logic)
-keep class com.awclippy.android.** { *; }

# Keep Accessibility Service
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep Services
-keep class * extends android.app.Service { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Keep networking classes
-keep class java.net.** { *; }
-keep class java.nio.** { *; }

# Keep NSD (Network Service Discovery) classes
-keep class android.net.nsd.** { *; }

# Keep Compose UI (if using)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep BuildConfig
-keep class com.awclippy.android.BuildConfig { *; }

# Don't optimize certain sensitive classes
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Remove logging in release (but keep error logs)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
