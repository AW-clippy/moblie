package com.awclippy.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.awclippy.android.R

@Composable
fun StatusCard(
    accessibilityEnabled: Boolean,
    serviceRunning: Boolean,
    windowsConnected: Boolean,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusRow(
                label = "Accessibility",
                enabled = accessibilityEnabled
            )
            
            StatusRow(
                label = "Service",
                enabled = serviceRunning
            )
            
            if (serviceRunning) {
                StatusRow(
                    label = "Windows PC",
                    enabled = windowsConnected,
                    isSearching = !windowsConnected
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    enabled: Boolean,
    isSearching: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
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
                modifier = Modifier.size(20.dp)
            )
            
            // Status text
            Text(
                text = when {
                    isSearching -> "Searching..."
                    enabled -> if (label == "Windows PC") "Connected" else "Enabled"
                    else -> if (label == "Service") "Stopped" else "Disabled"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isSearching -> MaterialTheme.colorScheme.secondary
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
