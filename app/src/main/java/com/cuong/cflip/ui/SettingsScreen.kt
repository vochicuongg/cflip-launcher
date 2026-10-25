package com.cuong.cflip.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cá nhân hóa", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextPrimary)

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

        SectionHeader("SẮP XẾP ỨNG DỤNG")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label) in listOf(0 to "A-Z", 1 to "Mới mở", 2 to "Mới cài")) {
                    val selected = settings.sortMode == mode
                    Box(modifier = Modifier.weight(1f).clip(CircleShape)
                        .background(if (selected) CFlipColors.Accent else CFlipColors.CardFill)
                        .border(1.dp, CFlipColors.CardStroke, CircleShape)
                        .clickable { update(settings.copy(sortMode = mode)) }
                        .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) CFlipColors.AccentDark else CFlipColors.TextPrimary)
                    }
                }
            }
        }

        SectionHeader("HIỂN THỊ")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchRow("Hiện nhãn ứng dụng", settings.showLabels) { update(settings.copy(showLabels = it)) }
                SwitchRow("Yêu thích lên trước", settings.favoritesFirst) { update(settings.copy(favoritesFirst = it)) }
                SwitchRow("Đồng hồ 24 giờ", settings.clock24h) { update(settings.copy(clock24h = it)) }
            }
        }

        SectionHeader("HÌNH NỀN")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackgroundStyles.colors.forEachIndexed { index, argb ->
                        Box(modifier = Modifier.size(38.dp).clip(CircleShape)
                            .background(Color(argb))
                            .border(if (settings.backgroundStyle == index && settings.wallpaperUri == null) 3.dp else 1.dp,
                                if (settings.backgroundStyle == index && settings.wallpaperUri == null)
                                    CFlipColors.Accent else CFlipColors.CardStroke, CircleShape)
                            .clickable { update(settings.copy(backgroundStyle = index, wallpaperUri = null)) })
                    }
                }
                Text(BackgroundStyles.names[settings.backgroundStyle.coerceIn(0, BackgroundStyles.names.lastIndex)],
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { wallpaperPicker.launch("image/*") }) { Text("Chọn ảnh nền") }
                    if (settings.wallpaperUri != null) {
                        OutlinedButton(onClick = {
                            runCatching {
                                val old = settings.wallpaperUri
                                if (old != null && old.startsWith("file:")) {
                                    android.net.Uri.parse(old).path?.let { java.io.File(it).delete() }
                                }
                            }
                            update(settings.copy(wallpaperUri = null))
                        }) { Text("Bỏ ảnh nền") }
                    }
                }
            }
        }

        SectionHeader("QUYỀN")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { QuickControls.openUsageAccessSettings(context) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Cấp Usage Access (cho mục Gần đây thật)")
            }
        }

        // Hidden apps management (v1.3): unhide brings the app back everywhere.
        SectionHeader("ỨNG DỤNG ĐÃ ẨN (${hiddenApps.size})")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hiddenApps.isEmpty()) {
                    Text("Chưa ẩn ứng dụng nào. Giữ một ứng dụng trong tab Ứng dụng để mở menu Ẩn.",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                }
                hiddenApps.forEach { app ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, fontSize = 13.sp, color = CFlipColors.TextPrimary,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { onUnhideApp(app) }) { Text("Hiện lại") }
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CFlipColors.AccentDark,
                checkedTrackColor = CFlipColors.Accent,
            ))
    }
}
