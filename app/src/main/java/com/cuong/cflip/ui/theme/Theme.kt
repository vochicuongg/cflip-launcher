package com.cuong.cflip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cuong.cflip.ui.CFlipColors
import com.cuong.cflip.ui.LocalCFlipScheme

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
    // vc257: the Material scheme follows the same theme scheme as
    // CFlipColors (LocalCFlipScheme, provided by MainScreen from
    // settings.themeMode; the cover re-provides Dark). The scheme
    // is read here, inside the composable — it used to be a
    // top-level val, which cannot read a CompositionLocal.
    val scheme = LocalCFlipScheme.current
    val colorScheme = if (scheme.isDark) {
        darkColorScheme(
            primary = CFlipColors.Accent,
            onPrimary = CFlipColors.AccentDark,
            background = CFlipColors.Bg,
            onBackground = CFlipColors.TextPrimary,
            surface = CFlipColors.Surface,
            onSurface = CFlipColors.TextPrimary,
            surfaceVariant = CFlipColors.Surface,
            onSurfaceVariant = CFlipColors.TextSecondary,
            outline = CFlipColors.CardStroke,
            error = CFlipColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = CFlipColors.Accent,
            onPrimary = CFlipColors.AccentDark,
            background = CFlipColors.Bg,
            onBackground = CFlipColors.TextPrimary,
            surface = CFlipColors.Surface,
            onSurface = CFlipColors.TextPrimary,
            surfaceVariant = CFlipColors.Surface,
            onSurfaceVariant = CFlipColors.TextSecondary,
            outline = CFlipColors.CardStroke,
            error = CFlipColors.Danger,
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = CFlipTypography, content = content)
}
