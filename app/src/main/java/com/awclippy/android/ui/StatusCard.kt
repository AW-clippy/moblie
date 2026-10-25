package com.awclippy.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.awclippy.android.R

@Composable
fun StatusCard(
    accessibilityEnabled: Boolean,
    serviceRunning: Boolean,
    networkName: String,
    synchronizedPcName: String?,
    isSynchronized: Boolean,
    lastSyncTime: Long = 0L,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Network Status
            StatusRow(
                label = "Network",
                value = if (serviceRunning) "Connected ($networkName)" else "Disconnected",
                enabled = serviceRunning,
                isSearching = false
            )

            // Synchronization Status
            if (serviceRunning) {
                StatusRow(
                    label = "Synchronization",
                    value = if (isSynchronized && synchronizedPcName != null) {
                        "Synchronized with $synchronizedPcName"
                    } else {
                        "Not synchronized (Searching...)"
                    },
                    enabled = isSynchronized,
                    isSearching = !isSynchronized,
                    isBold = isSynchronized
                )
            }
            
            // Clipboard Accessibility
            StatusRow(
                label = "Clipboard Access",
                value = if (accessibilityEnabled) "Enabled" else "Disabled",
                enabled = accessibilityEnabled
            )
            
            // Background Sync Service
            StatusRow(
                label = "Sync Service",
                value = if (serviceRunning) "Active" else "Stopped",
                enabled = serviceRunning
            )

            // Last sync timestamp if available
            if (serviceRunning && isSynchronized && lastSyncTime > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val elapsedSeconds = (System.currentTimeMillis() - lastSyncTime) / 1000
                val timeText = when {
                    elapsedSeconds < 5 -> "Just now"
                    elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
                    else -> "${elapsedSeconds / 60}m ago"
                }
                Text(
                    text = "Last sync activity: $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    enabled: Boolean,
    isSearching: Boolean = false,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = when {
                    isSearching -> R.drawable.ic_search
                    enabled -> R.drawable.ic_check_circle
                    else -> R.drawable.ic_cancel
                }),
                contentDescription = null,
                tint = when {
                    isSearching -> MaterialTheme.colorScheme.secondary
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(18.dp)
            )
            
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSearching -> MaterialTheme.colorScheme.secondary
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
