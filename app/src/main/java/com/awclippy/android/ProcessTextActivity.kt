package com.awclippy.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Activity that appears in the text selection menu as "Send to PC" or "AWClippy"
 * This is the ONLY reliable way to capture clipboard on Android 10+ from background
 */
class ProcessTextActivity : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get the selected text
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        
        if (text != null && text.isNotEmpty()) {
            Log.d("AWClippy", "[ANDROID→WINDOWS] Text selected via selection menu: ${text.length} bytes")
            
            // Send to sync service
            ClipboardSyncService.instance?.sendClipboardFromAccessibility(text)
            
            // Show confirmation (optional)
            //Toast.makeText(this, "Sent to PC", Toast.LENGTH_SHORT).show()
        }
        
        // Close this activity immediately
        finish()
    }
}
