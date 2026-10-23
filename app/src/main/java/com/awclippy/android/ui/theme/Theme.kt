package com.awclippy.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006494),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCAE6FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondary = Color(0xFF50606E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E5F5),
    onSecondaryContainer = Color(0xFF0C1D29),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF191C1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DCDFF),
    onPrimary = Color(0xFF003450),
    primaryContainer = Color(0xFF004B71),
    onPrimaryContainer = Color(0xFFCAE6FF),
    secondary = Color(0xFFB7C9D9),
    onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD3E5F5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFE1E2E5),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    background = Color(0xFF191C1E),
    onBackground = Color(0xFFE1E2E5),
)

@Composable
fun AWClippyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
