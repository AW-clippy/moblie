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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            AWClippyTheme {
                var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
                var serviceRunning by remember { mutableStateOf(ClipboardSyncService.instance != null) }
                var networkName by remember { mutableStateOf(ClipboardSyncService.instance?.getNetworkName() ?: "Local Network") }
                var synchronizedPcName by remember { mutableStateOf(ClipboardSyncService.instance?.getPcName()) }
                var isSynchronized by remember { mutableStateOf(ClipboardSyncService.instance?.isDeviceSynchronized() == true) }
                var lastSyncTime by remember { mutableLongStateOf(ClipboardSyncService.instance?.getLastSyncTime() ?: 0L) }
                
                // Update status every 800ms
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(800)
                        val service = ClipboardSyncService.instance
                        accessibilityEnabled = isAccessibilityServiceEnabled()
                        serviceRunning = service != null
                        networkName = service?.getNetworkName() ?: "Local Network"
                        synchronizedPcName = service?.getPcName()
                        isSynchronized = service?.isDeviceSynchronized() == true
                        lastSyncTime = service?.getLastSyncTime() ?: 0L
                    }
                }
                
                MainScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    serviceRunning = serviceRunning,
                    networkName = networkName,
                    synchronizedPcName = synchronizedPcName,
                    isSynchronized = isSynchronized,
                    lastSyncTime = lastSyncTime,
                    onRefresh = {
                        val service = ClipboardSyncService.instance
                        if (service != null) {
                            service.triggerRediscovery()
                            Toast.makeText(this@MainActivity, "Searching for Windows PC...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Start service first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenAccessibilitySettings = {
                        openAccessibilitySettings()
                    },
                    onToggleService = {
                        toggleService()
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
            ClipboardSyncService.instance?.stopSelf()
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
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
        
        val pcName = syncService.getPcName()
        if (syncService.isDeviceSynchronized() && pcName != null) {
            Toast.makeText(this, "✅ Sent to $pcName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⏳ Queued: searching for PC on network...", Toast.LENGTH_SHORT).show()
        }
        
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Manual send from MainActivity: ${text.length} chars")
        syncService.sendClipboardFromAccessibility(text)
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
