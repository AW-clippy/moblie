package com.awclippy.android

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class ClipboardAccessibilityService : AccessibilityService() {
    
    companion object {
        @Volatile
        var instance: ClipboardAccessibilityService? = null
            private set
    }

    private var clipboardManager: ClipboardManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastClipboardHash: Long? = null
    private var pendingClipboardText: String? = null
    private var serviceCheckJob: Job? = null
    
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        android.util.Log.d("AWClippy", "!!! CLIPBOARD LISTENER TRIGGERED !!!")
        handleClipboardChange()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // Listen for clipboard changes
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        
        android.util.Log.d("AWClippy", "========================================")
        android.util.Log.d("AWClippy", "Accessibility service connected")
        android.util.Log.d("AWClippy", "Clipboard listener installed")
        android.util.Log.d("AWClippy", "========================================")
        
        // Only start the sync service if it's not already running AND user requested it
        if (ClipboardSyncService.isServiceRequested(this)) {
            if (ClipboardSyncService.instance == null || !ClipboardSyncService.isServiceRunning) {
                val intent = Intent(this, ClipboardSyncService::class.java).apply {
                    action = ClipboardSyncService.ACTION_START_SERVICE
                }
                startForegroundService(intent)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window state changes can indicate app switches where clipboard might be used
        // This supplements the clipboard listener
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Window changed - clipboard listener will handle actual changes
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    private fun handleClipboardChange() {
        // If the service is stopped by user, ignore clipboard changes
        if (!ClipboardSyncService.isServiceRequested(this)) {
            android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Service is stopped by user, ignoring clipboard change")
            return
        }

        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) return
        
        val text = clip.getItemAt(0)?.text?.toString() ?: return
        if (text.isEmpty()) return
        
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Clipboard changed (${text.length} bytes)")
        
        // Calculate hash to prevent echo
        val config = ClipboardConfig()
        val crypto = ClipboardCrypto(config.sharedSecret)
        val textHash = crypto.hashText(text)
        
        // Check if this is an echo from network
        if (lastClipboardHash != null && lastClipboardHash == textHash) {
            android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] ❌ IGNORING - This is an echo from Windows")
            android.util.Log.d("AWClippy", "========================================")
            return // This came from network, don't send back
        }
        
        // Update hash
        lastClipboardHash = textHash
        
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] ✅ NEW clipboard, sending to Windows...")
        
        // Check if sync service is available
        val syncService = ClipboardSyncService.instance
        if (syncService == null || !ClipboardSyncService.isServiceRunning) {
            android.util.Log.w("AWClippy", "[ANDROID→WINDOWS] ⏳ Sync service not ready yet, queueing clipboard...")
            pendingClipboardText = text
            
            // Start checking for service availability if not already checking
            if (serviceCheckJob == null || !serviceCheckJob!!.isActive) {
                serviceCheckJob = serviceScope.launch {
                    waitForSyncServiceAndSend()
                }
            }
            
            android.util.Log.d("AWClippy", "========================================")
            return
        }
        
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Sync service found, calling sendClipboardFromAccessibility...")
        
        // Send directly to sync service
        syncService.sendClipboardFromAccessibility(text)
        
        android.util.Log.d("AWClippy", "========================================")
    }
    
    private suspend fun waitForSyncServiceAndSend() {
        android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Waiting for sync service to become available...")
        
        var attempts = 0
        val maxAttempts = 50 // 5 seconds max
        
        while (attempts < maxAttempts) {
            if (!ClipboardSyncService.isServiceRequested(this)) {
                android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Service was stopped by user, cancelling pending clipboard")
                pendingClipboardText = null
                return
            }
            delay(100) // Check every 100ms
            attempts++
            
            val syncService = ClipboardSyncService.instance
            if (syncService != null && ClipboardSyncService.isServiceRunning) {
                android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] ✅ Sync service available after ${attempts * 100}ms!")
                
                val textToSend = pendingClipboardText
                pendingClipboardText = null
                
                if (textToSend != null) {
                    android.util.Log.d("AWClippy", "[ANDROID→WINDOWS] Sending queued clipboard (${textToSend.length} bytes)...")
                    syncService.sendClipboardFromAccessibility(textToSend)
                }
                return
            }
        }
        
        android.util.Log.e("AWClippy", "[ANDROID→WINDOWS] ❌ Sync service did not become available after ${maxAttempts * 100}ms")
        pendingClipboardText = null
    }
    
    fun setClipboardText(text: String) {
        // Calculate hash before setting to prevent echo
        val config = ClipboardConfig()
        val crypto = ClipboardCrypto(config.sharedSecret)
        lastClipboardHash = crypto.hashText(text)
        
        // Set clipboard
        val clip = android.content.ClipData.newPlainText("awclippy", text)
        clipboardManager?.setPrimaryClip(clip)
    }

    override fun onDestroy() {
        instance = null
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        serviceCheckJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
