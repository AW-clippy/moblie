package com.awclippy.android

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Invisible (transparent) activity that reads the system clipboard
 * and sends it to the Windows PC via the sync service.
 * 
 * This activity is launched from the notification button "Send to PC".
 * It requires window focus to read the clipboard (Android 10+ requirement),
 * sends the content, shows a toast, and immediately closes.
 */
class SendClipboardActivity : Activity() {
    
    companion object {
        private const val TAG = "SendClipboard"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI - this activity is transparent
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        
        if (hasFocus) {
            handleClipboardSend()
        }
    }

    private fun handleClipboardSend() {
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            if (!clipboardManager.hasPrimaryClip()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            val clipData = clipboardManager.primaryClip
            val item = clipData?.getItemAt(0)
            val textToSend = item?.coerceToText(this)?.toString()
            
            if (textToSend.isNullOrEmpty()) {
                Toast.makeText(this, "No text in clipboard", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            Log.d(TAG, "Read ${textToSend.length} characters from clipboard")
            
            // Get the sync service instance
            val service = ClipboardSyncService.instance
            
            if (service == null || !ClipboardSyncService.isServiceRunning) {
                Toast.makeText(this, "Sync service not running", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            if (!service.hasWindowsDevice()) {
                Toast.makeText(this, "Windows PC not connected", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            // Send to Windows PC
            service.sendClipboardFromAccessibility(textToSend)
            
            Toast.makeText(this, "Sent to ${service.getPcName() ?: "Windows PC"}!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Successfully sent clipboard to Windows")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending clipboard: ${e.message}")
            Toast.makeText(this, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            // Close immediately so user stays in their current app
            finish()
        }
    }
}
