package com.cuong.cflip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cuong.cflip.ui.CFlipColors

private val CFlipScheme = darkColorScheme(
    primary = CFlipColors.Accent,
    onPrimary = CFlipColors.AccentDark,
    background = CFlipColors.Bg,
    onBackground = CFlipColors.TextPrimary,
    surface = Color(0xFF101514),
    onSurface = CFlipColors.TextPrimary,
    surfaceVariant = Color(0xFF1A211E),
    onSurfaceVariant = CFlipColors.TextSecondary,
    outline = CFlipColors.CardStroke,
    error = CFlipColors.Danger,
)

private val CFlipTypography = Typography(
    headlineLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CFlipTheme(content: @Composable () -> Unit) {
    // Cover screens are AMOLED: always the dark scheme above.
    MaterialTheme(colorScheme = CFlipScheme, typography = CFlipTypography, content = content)
}
