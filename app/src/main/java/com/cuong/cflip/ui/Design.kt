package com.cuong.cflip.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * CFlip design system, refined in 2.3 (main-screen redesign):
 * one mint accent used sparingly (primary action + active state only),
 * near-black background, quiet surfaces, and a strict hierarchy -
 * one hero, one primary action, then grid, then lists. Secondary text
 * is dimmer than before so the eye lands on titles and actions first.
 * Cover screens (CoverApp) reuse the same tokens, so they stay consistent.
 *
 * vc257 THEME SCHEMES — one colour scheme per UI mode, read through
 * LocalCFlipScheme (provided by MainScreen from settings.themeMode;
 * the cover re-provides Dark). CFlipColors below is a thin @Composable
 * accessor, so every existing call site keeps working unchanged.
 * Dark keeps the established identity verbatim (only the card fill
 * warms slightly and the rim brightens, for the glass look). Light
 * is the same mint brand on a warm off-white: the mint accent is
 * darkened to #0E7A5C so mint TEXT still passes 4.5:1 on light
 * (the light #7FE3C0 only scores 1.6:1 as text — measured, see
 * MASTER.md). All text/background pairs in both schemes were
 * contrast-checked (4.5:1+, script in the vc257 report).
 * Glass tokens differ per mode because frosted glass on a light
 * background needs a white fill + dark rim, on dark a white tint +
 * bright rim. TopGlow/Scrim/ClockBrush are cover-only (the cover
 * always uses the Dark scheme); they keep their dark values in
 * both schemes. The in-app background is ONE FLAT colour per
 * theme (user, 15/11) — no gradient, no glow, no blobs.
 */
data class CFlipScheme(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentDark: Color,
    val accentBrush: Brush,
    val borderBrush: Brush,
    val specular: Brush,
    val danger: Color,
    val cardFill: Color,
    val cardStroke: Color,
    val navFill: Color,
    val scrim: Brush,
    val topGlow: Brush,
    val clockBrush: Brush,
    val shadow: Color,
)

val DarkCFlipScheme = CFlipScheme(
    isDark = true,
    bg = Color(0xFF08090A),
    surface = Color(0xFF151715),
    textPrimary = Color(0xFFF5F7F6),
    textSecondary = Color(0xFF9AA8A1),
    textFaint = Color(0xFF66766E),
    accent = Color(0xFF7FE3C0),
    accentDeep = Color(0xFF2FBF8F),
    accentDark = Color(0xFF07271C),
    accentBrush = Brush.linearGradient(
        listOf(Color(0xFFA9F4D6), Color(0xFF7FE3C0), Color(0xFF35C192))
    ),
    borderBrush = Brush.linearGradient(
        listOf(Color(0x4DFFFFFF), Color(0x14FFFFFF), Color(0x33FFFFFF))
    ),
    specular = Brush.verticalGradient(
        0.0f to Color(0x29FFFFFF), 0.45f to Color(0x00000000)
    ),
    danger = Color(0xFFFF9E8A),
    cardFill = Color(0x17FFF6E9),
    cardStroke = Color(0x14FFFFFF),
    navFill = Color(0xE6141B18),
    scrim = Brush.verticalGradient(
        listOf(Color(0x59000000), Color(0x1A000000), Color(0xA6000000))
    ),
    topGlow = Brush.verticalGradient(
        listOf(Color(0x267FE3C0), Color(0x0A7FE3C0), Color(0x00000000))
    ),
    clockBrush = Brush.linearGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFD9F7EA), Color(0xFF9CE8CB))
    ),
    shadow = Color.Black,
)

val LightCFlipScheme = CFlipScheme(
    isDark = false,
    bg = Color(0xFFF1F4EF),
    surface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF132019),
    textSecondary = Color(0xFF54655C),
    textFaint = Color(0xFF5A6C61),
    accent = Color(0xFF0E7A5C),
    accentDeep = Color(0xFFCDEEDF),
    accentDark = Color(0xFFFFFFFF),
    accentBrush = Brush.linearGradient(
        listOf(Color(0xFF12A37E), Color(0xFF0E7A5C), Color(0xFF0B6B4F))
    ),
    borderBrush = Brush.linearGradient(
        listOf(Color(0x8CFFFFFF), Color(0x14000000), Color(0x40FFFFFF))
    ),
    specular = Brush.verticalGradient(
        0.0f to Color(0x73FFFFFF), 0.45f to Color(0x00000000)
    ),
    danger = Color(0xFFC93A26),
    cardFill = Color(0x99FFFFFF),
    cardStroke = Color(0x1A000000),
    navFill = Color(0xE6FFFFFF),
    scrim = Brush.verticalGradient(
        listOf(Color(0x59000000), Color(0x1A000000), Color(0xA6000000))
    ),
    topGlow = Brush.verticalGradient(
        listOf(Color(0x0F7FE3C0), Color(0x057FE3C0), Color(0x00000000))
    ),
    clockBrush = Brush.linearGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFD9F7EA), Color(0xFF9CE8CB))
    ),
    shadow = Color(0x26000000),
)

/** Current scheme for this composition — Dark unless MainScreen
 * provides Light (settings.themeMode). The cover re-provides Dark. */
val LocalCFlipScheme = staticCompositionLocalOf { DarkCFlipScheme }

object CFlipColors {
    val Bg: Color
        @Composable get() = LocalCFlipScheme.current.bg
    val Accent: Color
        @Composable get() = LocalCFlipScheme.current.accent
    val AccentDeep: Color
        @Composable get() = LocalCFlipScheme.current.accentDeep
    val AccentDark: Color
        @Composable get() = LocalCFlipScheme.current.accentDark
    val TextPrimary: Color
        @Composable get() = LocalCFlipScheme.current.textPrimary
    val TextSecondary: Color
        @Composable get() = LocalCFlipScheme.current.textSecondary
    val TextFaint: Color
        @Composable get() = LocalCFlipScheme.current.textFaint
    val CardFill: Color
        @Composable get() = LocalCFlipScheme.current.cardFill
    val CardStroke: Color
        @Composable get() = LocalCFlipScheme.current.cardStroke
    val Surface: Color
        @Composable get() = LocalCFlipScheme.current.surface
    val Danger: Color
        @Composable get() = LocalCFlipScheme.current.danger
    val NavFill: Color
        @Composable get() = LocalCFlipScheme.current.navFill

    /** Signature mint gradient: selected pill, logo, primary disc. */
    val AccentBrush: Brush
        @Composable get() = LocalCFlipScheme.current.accentBrush

    /** Hairline gradient border that catches light on glass cards. */
    val BorderBrush: Brush
        @Composable get() = LocalCFlipScheme.current.borderBrush

    /** Specular wash over the top of a glass card — the bright rim
     * that sells "liquid glass" on a flat background (a blur alone
     * is invisible on flat colour, so the rim + highlight + soft
     * shadow carry the effect). */
    val Specular: Brush
        @Composable get() = LocalCFlipScheme.current.specular

    /** Soft vertical wash laid over the chosen background colour / wallpaper. */
    val Scrim: Brush
        @Composable get() = LocalCFlipScheme.current.scrim

    /** Mint glow bleeding from the top edge - the premium ambient light. */
    val TopGlow: Brush
        @Composable get() = LocalCFlipScheme.current.topGlow

    /** Near-white to mint gradient for the hero clock. */
    val ClockBrush: Brush
        @Composable get() = LocalCFlipScheme.current.clockBrush
}

/** Liquid-glass card — the base surface of CFlip 2.1, vc257 refresh.
 * Frosted translucent fill + gradient hairline + a specular wash over
 * the top edge + soft shadow. On the flat one-colour background (user,
 * 15/11) there is nothing behind the card to blur, so the rim +
 * highlight + shadow carry the glass read — a blur alone would be
 * invisible on flat colour and is deliberately not faked. Corners
 * 28dp (friendlier, was 24dp). The shadow colour follows the scheme
 * (black on dark, soft grey on light).
 * vc242 MOTION: a tappable card now answers the finger — it shrinks to 95%
 * (spring, via the same interaction source as the ripple) while pressed.
 * The scale is a graphicsLayer transform, so layout bounds never move. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // vc245 DENSITY: compact cards pad 10dp instead of 14dp (used by the
    // cover notification rows so more of them fit the 720x748 cover).
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(28.dp)
    val shadowColor = LocalCFlipScheme.current.shadow
    var m = modifier
        .shadow(12.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .background(CFlipColors.CardFill)
        .border(1.dp, CFlipColors.BorderBrush, shape)
    if (onClick != null) {
        m = m.pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onClick() }
    }
    Box(modifier = m) {
        // vc257 SPECULAR: bright wash over the top edge, clipped to
        // the card shape — the liquid-glass rim. Drawn under the
        // content so text never sits on the brightest part.
        Box(Modifier.matchParentSize().background(CFlipColors.Specular))
        Box(Modifier.padding(if (compact) 10.dp else 14.dp)) { content() }
    }
}

/** Section title with an optional trailing action. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = CFlipColors.TextSecondary,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** Small rounded pill: icon + label, used for status facts (battery, counts). */
@Composable
fun StatusPill(icon: ImageVector?, text: String, tint: Color = CFlipColors.Accent) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CFlipColors.TextPrimary)
    }
}

/** Centred empty state: soft icon disc + title + hint. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(CFlipColors.CardFill),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = CFlipColors.TextSecondary, modifier = Modifier.size(30.dp)) }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
        Text(hint, fontSize = 12.sp, color = CFlipColors.TextSecondary)
    }
}

/**
 * vc248 MENU DIALOG — one shared long-press menu style for CoverApp and
 * the app drawer (they were two bare M3 AlertDialogs with stacked
 * TextButtons). Same dark surface + 24dp corners as the cards, styled
 * title, and a "Đóng" button at 48dp. Actions go in [content] as
 * [MenuActionRow]s, so every menu row has the same 48dp hit area,
 * ripple and press scale. Strings stay the callers'.
 */
@Composable
fun MenuDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = CFlipColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Đóng", color = CFlipColors.TextSecondary) }
        },
        containerColor = CFlipColors.Surface,
        shape = RoundedCornerShape(24.dp),
    )
}

/** One menu action row: full-width, 48dp min, ripple + press scale. */
@Composable
fun MenuActionRow(
    label: String,
    color: Color = CFlipColors.TextPrimary,
    onClick: () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** Current battery percent (0-100, -1 if unknown), refreshed every 30 s. */
@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var pct by remember { mutableStateOf(readBatteryPercent(context)) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); pct = readBatteryPercent(context) }
    }
    return pct
}

fun readBatteryPercent(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
}

/** True while the battery is charging or full. */
fun readBatteryCharging(context: Context): Boolean {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}
