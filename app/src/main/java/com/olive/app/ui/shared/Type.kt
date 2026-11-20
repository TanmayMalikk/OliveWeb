package com.olive.app.ui.shared

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val materialType = Typography()

// Keep metadata legible and allow the system font scale to apply to every style.
internal val OliveTypography = materialType.copy(
    bodySmall = materialType.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = materialType.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = materialType.labelSmall.copy(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    ),
)
