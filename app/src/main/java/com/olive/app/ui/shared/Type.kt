package com.olive.app.ui.shared

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

private val materialType = Typography()

// Keep metadata legible and allow the system font scale to apply to every style.
internal val OliveTypography = materialType.copy(
    displayLarge = materialType.displayLarge.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    displayMedium = materialType.displayMedium.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    displaySmall = materialType.displaySmall.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineLarge = materialType.headlineLarge.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineMedium = materialType.headlineMedium.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineSmall = materialType.headlineSmall.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    titleLarge = materialType.titleLarge.copy(lineBreak = LineBreak.Heading),
    labelLarge = materialType.labelLarge.copy(fontFeatureSettings = "tnum"),
    bodySmall = materialType.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = materialType.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    labelSmall = materialType.labelSmall.copy(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
    ),
)
