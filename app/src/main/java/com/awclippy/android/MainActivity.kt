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
                var serviceRunning by remember { mutableStateOf(ClipboardSyncService.isServiceRunning && ClipboardSyncService.instance != null) }
                var networkName by remember {
                    mutableStateOf(
                        if (serviceRunning) ClipboardSyncService.instance?.getNetworkName() ?: "Local Network"
                        else "Disconnected"
                    )
                }
                var synchronizedPcName by remember { mutableStateOf(if (serviceRunning) ClipboardSyncService.instance?.getPcName() else null) }
                var isSynchronized by remember { mutableStateOf(serviceRunning && ClipboardSyncService.instance?.isDeviceSynchronized() == true) }
                var lastSyncTime by remember { mutableLongStateOf(if (serviceRunning) ClipboardSyncService.instance?.getLastSyncTime() ?: 0L else 0L) }
                
                // Update status every 500ms
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(500)
                        val service = ClipboardSyncService.instance
                        val running = ClipboardSyncService.isServiceRunning && service != null
                        accessibilityEnabled = isAccessibilityServiceEnabled()
                        serviceRunning = running
                        networkName = if (running) service?.getNetworkName() ?: "Local Network" else "Disconnected"
                        synchronizedPcName = if (running) service?.getPcName() else null
                        isSynchronized = running && service?.isDeviceSynchronized() == true
                        lastSyncTime = if (running) service?.getLastSyncTime() ?: 0L else 0L
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
                        if (service != null && ClipboardSyncService.isServiceRunning) {
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
                        val running = ClipboardSyncService.isServiceRunning && ClipboardSyncService.instance != null
                        serviceRunning = running
                        networkName = if (running) ClipboardSyncService.instance?.getNetworkName() ?: "Local Network" else "Disconnected"
                        synchronizedPcName = if (running) ClipboardSyncService.instance?.getPcName() else null
                        isSynchronized = running && ClipboardSyncService.instance?.isDeviceSynchronized() == true
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
        val serviceRunning = ClipboardSyncService.isServiceRunning && ClipboardSyncService.instance != null
        
        if (serviceRunning) {
            // User requested to stop service
            ClipboardSyncService.setServiceRequested(this, false)
            
            // 1. Explicitly stop active instance
            ClipboardSyncService.instance?.stopSyncService()
            
            // 2. Also stop via intent with ACTION_STOP_SERVICE as a backup
            val stopIntent = Intent(this, ClipboardSyncService::class.java).apply {
                action = ClipboardSyncService.ACTION_STOP_SERVICE
            }
            try {
                startService(stopIntent)
            } catch (_: Exception) {}
            stopService(stopIntent)
            
            // 3. Immediately clear notifications from Activity context
            try {
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager?.cancel(1)
                notificationManager?.cancelAll()
            } catch (_: Exception) {}
            
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
            
            // User requested to start service
            ClipboardSyncService.setServiceRequested(this, true)
            
            val intent = Intent(this, ClipboardSyncService::class.java).apply {
                action = ClipboardSyncService.ACTION_START_SERVICE
            }
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
