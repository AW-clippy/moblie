package com.awclippy.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.awclippy.android.R

@Composable
fun MainScreen(
    accessibilityEnabled: Boolean,
    serviceRunning: Boolean,
    networkName: String,
    synchronizedPcName: String?,
    isSynchronized: Boolean,
    lastSyncTime: Long = 0L,
    onRefresh: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "AWClippy Logo",
                modifier = Modifier.size(72.dp)
            )

            // Dynamic Synchronization Header Banner
            if (serviceRunning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSynchronized) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isSynchronized) R.drawable.ic_check_circle else R.drawable.ic_search
                            ),
                            contentDescription = null,
                            tint = if (isSynchronized) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSynchronized && synchronizedPcName != null) {
                                    "Synchronized with $synchronizedPcName"
                                } else {
                                    "Connected to $networkName"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSynchronized) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            Text(
                                text = if (isSynchronized) {
                                    "Clipboard synchronization is active"
                                } else {
                                    "Searching for Windows PC on network..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSynchronized) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                }
                            )
                        }
                    }
                }
            }
            
            // Detailed Status Card
            StatusCard(
                accessibilityEnabled = accessibilityEnabled,
                serviceRunning = serviceRunning,
                networkName = networkName,
                synchronizedPcName = synchronizedPcName,
                isSynchronized = isSynchronized,
                lastSyncTime = lastSyncTime
            )
            
            // Enable Accessibility Button (conditional)
            if (!accessibilityEnabled) {
                FilledTonalButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Clipboard Accessibility")
                }
            }
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Service Toggle Button
                Button(
                    onClick = onToggleService,
                    modifier = Modifier.weight(1f),
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

                // Re-sync / Search PC Button (only when running)
                if (serviceRunning) {
                    OutlinedButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_search),
                            contentDescription = "Search",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Search PC")
                    }
                }
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
                text = if (isSynchronized && synchronizedPcName != null) {
                    "Type or paste text to send directly to $synchronizedPcName:"
                } else {
                    "Type or paste text to send to Windows (queued until connected):"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Message Input Field
            MessageInputField(
                enabled = serviceRunning,
                onSendMessage = onSendMessage
            )
            
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
                text = "Text copied on Windows will automatically appear on your Android clipboard as soon as synchronization is established.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
