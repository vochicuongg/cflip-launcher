package com.cuong.cflip.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.BackgroundStyles
import com.cuong.cflip.data.CoverSettings
import com.cuong.cflip.data.SettingsStore

/** Style tab (2.0): personalisation grouped into cards. All free, all instant. */
@Composable
fun SettingsScreen(
    settings: CoverSettings,
    onSettingsChange: (CoverSettings) -> Unit,
    modifier: Modifier = Modifier,
    hiddenApps: List<AppInfo> = emptyList(),
    onUnhideApp: (AppInfo) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    // Old bug: the slider offered 0..6, but CoverLauncherScreen only honours
    // 0 (auto) and 3..6 - picking 1 or 2 showed "Số cột: 1" while silently
    // falling back to auto. Map the slider to the valid values only.
    val columnOptions = listOf(0, 3, 4, 5, 6)
    var columnsIdx by remember {
        mutableStateOf(columnOptions.indexOf(settings.columns).coerceAtLeast(0).toFloat())
    }
    val columns = columnOptions[columnsIdx.toInt()]

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // Old bug: GetContent only grants a temporary URI grant, so the
            // wallpaper vanished after a restart/reboot. Copy the image into
            // CFlip's private storage once, and keep that file instead.
            val saved = runCatching {
                val file = java.io.File(context.filesDir, "cover_wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
                android.net.Uri.fromFile(file).toString()
            }.getOrNull()
            if (saved != null) {
                val next = settings.copy(wallpaperUri = saved)
                store.save(next); onSettingsChange(next)
            }
        }
    }

    fun update(next: CoverSettings) { store.save(next); onSettingsChange(next) }

    // vc248 ENTRANCE: each section fades in 30 ms after the previous one
    // (shared EnterItem); reduced motion renders the end state at once.
    val reduced = rememberReducedMotion()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cá nhân hóa", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextPrimary)

        EnterItem(index = 0, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("LƯỚI ỨNG DỤNG")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("Số cột: ${if (columns == 0) "Tự động" else columns.toString()}",
                            fontSize = 13.sp, color = CFlipColors.TextPrimary)
                        Slider(value = columnsIdx, onValueChange = { columnsIdx = it }, valueRange = 0f..4f, steps = 3,
                            onValueChangeFinished = { update(settings.copy(columns = columns)) },
                            colors = mintSlider(), modifier = Modifier.fillMaxWidth())
                        Text("Cỡ biểu tượng: ${settings.iconSizeDp} dp", fontSize = 13.sp, color = CFlipColors.TextPrimary)
                        Slider(value = settings.iconSizeDp.toFloat(),
                            onValueChange = { update(settings.copy(iconSizeDp = it.toInt())) },
                            valueRange = 40f..84f, colors = mintSlider(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        EnterItem(index = 1, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("SẮP XẾP ỨNG DỤNG")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    // vc248: the same shared SortModePicker the hub island
                    // and the drawer use — the old inline copy had its own
                    // 33dp pills and its own look, so the three pickers
                    // drifted apart. No behaviour change.
                    SortModePicker(mode = settings.sortMode, onSelect = { update(settings.copy(sortMode = it)) })
                }
            }
        }

        EnterItem(index = 2, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("HIỂN THỊ")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SwitchRow("Hiện nhãn ứng dụng", settings.showLabels) { update(settings.copy(showLabels = it)) }
                        SwitchRow("Yêu thích lên trước", settings.favoritesFirst) { update(settings.copy(favoritesFirst = it)) }
                        SwitchRow("Đồng hồ 24 giờ", settings.clock24h) { update(settings.copy(clock24h = it)) }
                    }
                }
            }
        }

        EnterItem(index = 3, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("HÌNH NỀN")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BackgroundStyles.colors.forEachIndexed { index, argb ->
                                ColorDot(
                                    argb = argb,
                                    selected = settings.backgroundStyle == index && settings.wallpaperUri == null,
                                ) { update(settings.copy(backgroundStyle = index, wallpaperUri = null)) }
                            }
                        }
                        Text(BackgroundStyles.names[settings.backgroundStyle.coerceIn(0, BackgroundStyles.names.lastIndex)],
                            fontSize = 12.sp, color = CFlipColors.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { wallpaperPicker.launch("image/*") },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Chọn ảnh nền") }
                            if (settings.wallpaperUri != null) {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        val old = settings.wallpaperUri
                                        if (old != null && old.startsWith("file:")) {
                                            android.net.Uri.parse(old).path?.let { java.io.File(it).delete() }
                                        }
                                    }
                                    update(settings.copy(wallpaperUri = null))
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bỏ ảnh nền") }
                            }
                        }
                    }
                }
            }
        }

        EnterItem(index = 4, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("QUYỀN")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { QuickControls.openUsageAccessSettings(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Cấp Usage Access (cho mục Gần đây thật)")
                    }
                }
            }
        }

        // Hidden apps management (v1.3): unhide brings the app back everywhere.
        EnterItem(index = 5, entered = entered, reduced = reduced) {
            Column {
                SectionHeader("ỨNG DỤNG ĐÃ ẨN (${hiddenApps.size})")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hiddenApps.isEmpty()) {
                            Text("Chưa ẩn ứng dụng nào. Giữ một ứng dụng trong tab Ứng dụng để mở menu Ẩn.",
                                fontSize = 12.sp, color = CFlipColors.TextSecondary)
                        }
                        hiddenApps.forEach { app ->
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(app.label, fontSize = 13.sp, color = CFlipColors.TextPrimary,
                                    modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { onUnhideApp(app) },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Hiện lại") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun mintSlider() = SliderDefaults.colors(
    thumbColor = CFlipColors.Accent,
    activeTrackColor = CFlipColors.Accent,
    inactiveTrackColor = CFlipColors.CardStroke,
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // vc248: the whole row toggles (not just the switch itself) and the
    // row is 48dp — before, tapping the label did nothing and the row
    // was only as tall as the switch. The switch still handles its own
    // taps, so there is no double toggle.
    val pressSource = remember { MutableInteractionSource() }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .pressScale(pressSource)
        .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onChange(!checked) }
        .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CFlipColors.AccentDark,
                checkedTrackColor = CFlipColors.Accent,
            ))
    }
}

/**
 * vc248: wallpaper colour dot — the visible dot stays 38dp, but the tap
 * area is a 48dp box with press scale (before, the 38dp dot itself was
 * the whole target). The mint ring still marks the selected colour.
 */
@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.size(48.dp)
        .pressScale(pressSource)
        .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(38.dp).clip(CircleShape)
            .background(Color(argb))
            .border(if (selected) 3.dp else 1.dp,
                if (selected) CFlipColors.Accent else CFlipColors.CardStroke, CircleShape))
    }
}
