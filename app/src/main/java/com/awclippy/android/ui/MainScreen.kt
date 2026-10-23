package com.awclippy.android.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.awclippy.android.ClipboardAccessibilityService
import com.awclippy.android.ClipboardSyncService
import com.awclippy.android.R

@Composable
fun MainScreen(
    accessibilityEnabled: Boolean,
    serviceRunning: Boolean,
    windowsConnected: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleService: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "AWClippy Logo",
                modifier = Modifier.size(80.dp)
            )
            
            // Status Card
            StatusCard(
                accessibilityEnabled = accessibilityEnabled,
                serviceRunning = serviceRunning,
                windowsConnected = windowsConnected
            )
            
            // Enable Accessibility Button (conditional)
            if (!accessibilityEnabled) {
                FilledTonalButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Accessibility")
                }
            }
            
            // Service Toggle Button
            Button(
                onClick = onToggleService,
                modifier = Modifier.fillMaxWidth(),
                enabled = accessibilityEnabled,
                colors = if (serviceRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (serviceRunning) "Stop Service" else "Start Service")
            }
            
            // Divider
            HorizontalDivider()
            
            // Send to Windows Section
            Text(
                text = "Send to Windows",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                text = "Type or paste text to send to Windows:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Message Input Field
            MessageInputField(
                enabled = serviceRunning,
                onSendMessage = onSendMessage
            )
            
            // Spacer
            Spacer(modifier = Modifier.height(8.dp))
            
            // Divider
            HorizontalDivider()
            
            // Received Section
            Text(
                text = "Received from Windows",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                text = "Text copied on Windows will automatically appear on your Android clipboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
