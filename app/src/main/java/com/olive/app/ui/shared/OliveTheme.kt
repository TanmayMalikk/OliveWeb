// All app roles: centralizes every color and the shared Material theme in one file.
package com.olive.app.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Core Olive brand and surface palette used across covers and the private Safe App.
internal val OliveGreen = Color(0xFF45613C)
internal val Ink = Color(0xFF253322)
internal val Cream = Color(0xFFFAF9F5)
internal val Muted = Color(0xFF677A3B)
internal val Paper = Color.White
internal val PaleOlive = Color(0xFFE7EDDD)
internal val WarmWhite = Color(0xFFF0F0E8)
internal val LightSage = Color(0xFFE1E8D7)
internal val SageGreen = Color(0xFFD2DDC6)
internal val SoftGray = Color(0xFFD6D9CD)
internal val DarkOlive = Color(0xFF314B28)
internal val MossGreen = Color(0xFF6C7A3D)
internal val OliveBrown = Color(0xFF7A5326)

// Scrollbar track and thumb colors for scrollable lists and grids.
internal val ScrollTrack = Color(0x2445613C)
internal val ScrollThumb = Color(0xB345613C)

// Fitness Tracker cover and nutrition/activity chart palette.
internal val FitnessBackground = Cream
internal val FitnessSurface = Paper
internal val FitnessAccent = OliveGreen
internal val FitnessText = Ink
internal val FitnessSubtle = Muted
internal val FitnessHeroEnd = Color(0xFF6F8459)
internal val FitnessWarmCard = Color(0xFFF1F3E9)
internal val FitnessDarkCard = Color(0xFF263722)
internal val FitnessStopRed = Color(0xFF8A453E)
internal val DistanceGreen = Color(0xFF78935C)
internal val BurnedOrange = Color(0xFFB56D44)
internal val EatenBrown = Color(0xFF765B3D)
internal val ProteinGreen = Color(0xFF45613C)
internal val ProteinLime = Color(0xFFA7CA65)
internal val CarbohydrateGold = Color(0xFFC1963E)
internal val CarbohydrateBlue = Color(0xFF53BBD8)
internal val FatPlum = Color(0xFF875E78)

// Messaging, friend list, and voice call screen palette.
internal val ChatPageBackground = Color(0xFFF8F7F1)
internal val ChatIncomingBubble = Color(0xFFF0F2E8)
internal val ChatSentBubble = Color(0xFF49683B)
internal val ChatBorder = Color(0xFFD8DDCE)
internal val ChatDangerRed = Color(0xFF9D3E37)
internal val OnlineIndicatorGreen = Color(0xFF38B56C)
internal val SoftCardSurface = Color(0xFFF3F6F0)
internal val WarmBannerYellow = Color(0xFFFFF7E1)
internal val CallBackground = Color(0xFF172219)
internal val CallControlBackground = Color(0xFF2B382D)
internal val CallGreen = Color(0xFF789C68)
internal val CallRed = Color(0xFFD4423C)

// Dashboard cards, badges, and private note surfaces.
internal val CardTintEnabled = Color(0xFFF4F6EF)
internal val CardTintDisabled = Color(0xFFE9EDE5)
internal val CardSoftMint = Color(0xFFF0F4EC)
internal val IconBadgeEnabled = Color(0xFFDDE7D6)
internal val IconBadgeDisabled = Color(0xFFDDE2D9)
internal val PinnedStarGold = Color(0xFFFFC107)
internal val NotePaperCream = Color(0xFFFFFDF4)
internal val NoteTagMint = Color(0xFFE7EDE2)
internal val NoteMutedSurface = Color(0xFFF5F3EC)

// Olive branch splash and unlock canvas illustration palette.
internal val BranchBarkDark = Color(0xFF5D4037)
internal val BranchBarkMid = Color(0xFF6B583E)
internal val BranchBarkLight = Color(0xFF7A6B48)
internal val LeafRippleOlive = Color(0xFF6B8E23)
internal val LeafBodyDark = Color(0xFF556B2F)
internal val LeafBodyMid = Color(0xFF6E8B3D)
internal val LeafBodyLight = Color(0xFF8F9E6D)
internal val LeafShadowTint = Color(0x66334411)
internal val FruitHighlight = Color(0xFF7A933B)
internal val FruitMid = Color(0xFF4A6223)
internal val FruitShadow = Color(0xFF2C3E14)

// Applies the shared Olive color scheme across every cover and private screen.
@Composable
internal fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = OliveGreen, onPrimary = Paper,
            primaryContainer = PaleOlive, onPrimaryContainer = Ink,
            secondary = OliveGreen, onSecondary = Paper,
            secondaryContainer = PaleOlive, onSecondaryContainer = Ink,
            background = Cream, onBackground = Ink,
            surface = Cream, onSurface = Ink,
            surfaceVariant = WarmWhite, onSurfaceVariant = Muted,
            outline = SoftGray,
        ),
        content = content,
    )
}

