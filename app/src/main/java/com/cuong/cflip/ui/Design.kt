package com.cuong.cflip.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * CFlip 2.0 design system.
 * One accent (mint), deep AMOLED blacks, glass cards, 8dp spacing rhythm,
 * big touch targets for a 720px-wide cover screen used one-handed.
 */
object CFlipColors {
    val Bg = Color(0xFF060807)
    val Accent = Color(0xFF7FE3C0)
    val AccentDeep = Color(0xFF2FBF8F)
    val AccentDark = Color(0xFF07271C)
    val TextPrimary = Color(0xFFF4F9F7)
    val TextSecondary = Color(0xFFA7BDB3)
    val TextFaint = Color(0xFF6B7F76)
    val CardFill = Color(0x17FFFFFF)       // white 9%
    val CardStroke = Color(0x1FFFFFFF)     // white 12%
    val Danger = Color(0xFFFF9E8A)
    val NavFill = Color(0xE6141B18)

    /** Signature mint gradient: selected pill, logo, primary disc. */
    val AccentBrush = Brush.linearGradient(
        listOf(Color(0xFFA9F4D6), Color(0xFF7FE3C0), Color(0xFF35C192))
    )

    /** Hairline gradient border that catches light on glass cards. */
    val BorderBrush = Brush.linearGradient(
        listOf(Color(0x3DFFFFFF), Color(0x14FFFFFF), Color(0x29FFFFFF))
    )

    /** Soft vertical wash laid over the chosen background colour / wallpaper. */
    val Scrim = Brush.verticalGradient(
        listOf(Color(0x59000000), Color(0x1A000000), Color(0xA6000000))
    )

    /** Mint glow bleeding from the top edge - the premium ambient light. */
    val TopGlow = Brush.verticalGradient(
        listOf(Color(0x267FE3C0), Color(0x0A7FE3C0), Color(0x00000000))
    )

    /** Near-white to mint gradient for the hero clock. */
    val ClockBrush = Brush.linearGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFD9F7EA), Color(0xFF9CE8CB))
    )
}

/** Translucent card with a gradient hairline + soft shadow - base surface of CFlip 2.1. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var m = modifier
        .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black, spotColor = Color.Black)
        .clip(RoundedCornerShape(24.dp))
        .background(CFlipColors.CardFill)
        .border(1.dp, CFlipColors.BorderBrush, RoundedCornerShape(24.dp))
    if (onClick != null) m = m.clickable { onClick() }
    Box(modifier = m.padding(14.dp)) { content() }
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
