package com.olive.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// UI colors: change these named tokens to recolor every screen that uses the shared theme instead of editing individual composables.
val OliveGreen = Color(0xFF45613C)
val Ink = Color(0xFF253322)
val Cream = Color(0xFFFAF9F5)
val Muted = Color(0xFF73776C)
val Paper = Color.White
val PaleOlive = Color(0xFFE7EDDD)
val WarmWhite = Color(0xFFF0F0E8)
val LightSage = Color(0xFFE1E8D7)
val SageGreen = Color(0xFFD2DDC6)
val SoftGray = Color(0xFFD6D9CD)
val DarkOlive = Color(0xFF314B28)
val MossGreen = Color(0xFF6C7A3D)
val OliveBrown = Color(0xFF7A5C35)

// Every cover and the private app use this one shared color system.
// Text sizing: Material's default typography is intentionally used for normal copy; explicit sp values are documented beside special display text.
// Language sizing note: use MaterialTheme typography for ordinary translated copy; keep explicit sp values only for brand, keypad, or editorial display text.
// If the whole app needs larger default text later, define typography here instead of changing every screen separately.
@Composable fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = OliveGreen, onPrimary = Paper,
        primaryContainer = PaleOlive, onPrimaryContainer = Ink,
        secondary = OliveGreen, onSecondary = Paper,
        secondaryContainer = PaleOlive, onSecondaryContainer = Ink,
        background = Cream, onBackground = Ink,
        surface = Cream, onSurface = Ink,
        surfaceVariant = WarmWhite, onSurfaceVariant = Muted,
        outline = SoftGray,
    ), content = content)
}
