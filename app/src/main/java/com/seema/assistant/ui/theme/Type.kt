package com.seema.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

val SeemaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        letterSpacing = 16.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 6.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 4.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 1.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Mono,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontSize = 10.sp,
        letterSpacing = 2.sp
    )
)
