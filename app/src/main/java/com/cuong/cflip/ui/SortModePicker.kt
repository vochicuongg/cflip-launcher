package com.cuong.cflip.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared sort control (hub island + drawer + STYLE tab): the same
 * three modes the cover reads. Segmented-row style, copied from the
 * Quick Panel size picker in MainScreen.kt so the two pickers look
 * like one component.
 * vc248: each pill is 48dp with press scale + haptic tick (before, the
 * pills were ~33dp with no press feedback at all).
 * vc254: moved here verbatim from the deleted app drawer — the drawer was
 * deleted (its island is gone) but SettingsScreen still uses this.
 */
@Composable
fun SortModePicker(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
        for ((m, label) in listOf(0 to "A-Z", 1 to "Mới mở", 2 to "Mới cài")) {
            SortPill(label = label, selected = mode == m, modifier = Modifier.weight(1f)) { onSelect(m) }
        }
    }
}

/**
 * vc257 THEME PICKER: the same segmented pills as SortModePicker
 * (48dp, press scale + haptic tick) — Dark / Light / Follow system.
 * "Theo ứng dụng" in the request is read as Follow system.
 */
@Composable
fun ThemeModePicker(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
        for (m in com.cuong.cflip.data.ThemeMode.all) {
            SortPill(label = com.cuong.cflip.data.ThemeMode.label(m), selected = mode == m,
                modifier = Modifier.weight(1f)) { onSelect(m) }
        }
    }
}

@Composable
private fun SortPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    val hapticView = LocalView.current
    Box(
        modifier = modifier.heightIn(min = 48.dp).clip(CircleShape)
            .background(if (selected) CFlipColors.Accent else Color.Transparent)
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) {
                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary,
        )
    }
}
