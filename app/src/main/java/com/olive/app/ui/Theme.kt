package com.olive.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OliveGreen = Color(0xFF45613C)
val Ink = Color(0xFF253322)
val Cream = Color(0xFFFAF9F5)
val Muted = Color(0xFF73776C)

// One cozy colour and type setup keeps the food cover and safe pages visually consistent.
@Composable fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = OliveGreen, onPrimary = Color.White,
        primaryContainer = Color(0xFFE7EDDD), onPrimaryContainer = Ink,
        secondary = OliveGreen, onSecondary = Color.White,
        secondaryContainer = Color(0xFFE7EDDD), onSecondaryContainer = Ink,
        background = Cream, onBackground = Ink,
        surface = Cream, onSurface = Ink,
        surfaceVariant = Color(0xFFF0F0E8), onSurfaceVariant = Muted,
        outline = Color(0xFFD6D9CD),
    ), content = content)
}
