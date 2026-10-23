package com.awclippy.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.awclippy.android.ui.MainScreen
import com.awclippy.android.ui.theme.AWClippyTheme

class MainActivity : ComponentActivity() {

    private var updateTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            AWClippyTheme {
                var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
                var serviceRunning by remember { mutableStateOf(ClipboardSyncService.instance != null) }
                var windowsConnected by remember { mutableStateOf(false) }
                
                // Update status every second
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        accessibilityEnabled = isAccessibilityServiceEnabled()
                        serviceRunning = ClipboardSyncService.instance != null
                        windowsConnected = ClipboardSyncService.instance?.hasWindowsDevice() == true
                    }
                }
                
                MainScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    serviceRunning = serviceRunning,
                    windowsConnected = windowsConnected,
                    onOpenAccessibilitySettings = {
                        openAccessibilitySettings()
                    },
                    onToggleService = {
                        toggleService()
                        // Trigger recomposition
                        serviceRunning = ClipboardSyncService.instance != null
                    },
                    onSendMessage = { message ->
                        sendToWindows(message)
                    }
                )
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Please enable 'AW Clippy' in Accessibility Settings",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleService() {
        val serviceRunning = ClipboardSyncService.instance != null
        
        if (serviceRunning) {
            // Stop service
            ClipboardSyncService.instance?.stopSelf()
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Start service
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(
                    this,
                    "Please enable Accessibility Service first",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            
            val intent = Intent(this, ClipboardSyncService::class.java)
            startForegroundService(intent)
            
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendToWindows(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Please enter some text first", Toast.LENGTH_SHORT).show()
            return
        }
        
        val syncService = ClipboardSyncService.instance
        if (syncService == null) {
            Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!syncService.hasWindowsDevice()) {
            Toast.makeText(this, "Searching for Windows PC...", Toast.LENGTH_SHORT).show()
        }
        
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Manual send from MainActivity: ${text.length} bytes")
        syncService.sendClipboardFromAccessibility(text)
        
        Toast.makeText(this, "✅ Sent to Windows!", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }
}

