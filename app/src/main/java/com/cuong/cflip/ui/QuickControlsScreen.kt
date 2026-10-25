package com.cuong.cflip.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.service.MyAccessibilityService

/** Quick controls tab (2.0): big tappable tiles, sliders in glass cards. */
@Composable
fun QuickControlsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var torch by remember { mutableStateOf(QuickControls.torchOn) }
    val (volInit, volMax) = remember { QuickControls.volume(context) }
    var volume by remember { mutableStateOf(volInit.toFloat()) }
    var brightness by remember { mutableStateOf(QuickControls.brightness(context).toFloat()) }
    // Old bug: toast fired on EVERY drag step when the permission was missing,
    // spamming the screen while the user dragged. Now: remember the failure
    // and toast once when the drag finishes.
    var brightnessFailed by remember { mutableStateOf(false) }
    val a11y = MyAccessibilityService.instance

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Điều khiển nhanh", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextPrimary)

        // Flashlight: full-width state tile
        GlassCard(modifier = Modifier.fillMaxWidth(), onClick = {
            val next = !torch
            if (QuickControls.setTorch(context, next)) torch = next else toast("Không tìm thấy đèn flash")
        }) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = null,
                    tint = if (torch) CFlipColors.Accent else CFlipColors.TextSecondary,
                    modifier = Modifier.size(26.dp))
                Column {
                    Text("Đèn flash", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = CFlipColors.TextPrimary)
                    Text(if (torch) "Đang bật · Chạm để tắt" else "Đang tắt · Chạm để bật",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null,
                        tint = CFlipColors.TextSecondary, modifier = Modifier.size(20.dp))
                    Text("Âm lượng ${volume.toInt()}/$volMax", fontSize = 13.sp,
                        color = CFlipColors.TextPrimary)
                }
                Slider(value = volume,
                    onValueChange = { volume = it; QuickControls.setVolume(context, it.toInt()) },
                    valueRange = 0f..volMax.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = CFlipColors.Accent,
                        activeTrackColor = CFlipColors.Accent,
                        inactiveTrackColor = CFlipColors.CardStroke),
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.BrightnessMedium, contentDescription = null,
                        tint = CFlipColors.TextSecondary, modifier = Modifier.size(20.dp))
                    Text("Độ sáng ${brightness.toInt()}/255", fontSize = 13.sp,
                        color = CFlipColors.TextPrimary)
                }
                Slider(value = brightness, onValueChange = { brightness = it
                    if (!QuickControls.setBrightness(context, it.toInt())) brightnessFailed = true
                },
                    onValueChangeFinished = {
                        if (brightnessFailed) { brightnessFailed = false
                            toast("Cần cấp quyền sửa cài đặt hệ thống") }
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(thumbColor = CFlipColors.Accent,
                        activeTrackColor = CFlipColors.Accent,
                        inactiveTrackColor = CFlipColors.CardStroke),
                    modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { QuickControls.openWriteSettingsPermission(context) }) {
                    Text("Cấp quyền chỉnh độ sáng hệ thống")
                }
            }
        }

        SectionHeader("MỞ PANEL")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ControlTile(Icons.Filled.Wifi, "Wi-Fi", Modifier.weight(1f)) { QuickControls.openWifiPanel(context) }
            ControlTile(Icons.Filled.Language, "Internet", Modifier.weight(1f)) { QuickControls.openInternetPanel(context) }
            ControlTile(Icons.Filled.Bluetooth, "Bluetooth", Modifier.weight(1f)) { QuickControls.openBluetoothSettings(context) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ControlTile(Icons.Filled.Nfc, "NFC", Modifier.weight(1f)) { QuickControls.openNfcPanel(context) }
            ControlTile(Icons.Filled.Settings, "Cài đặt", Modifier.weight(1f)) { QuickControls.openDeviceSettings(context) }
            Spacer(Modifier.weight(1f))
        }

        SectionHeader("HỆ THỐNG · CẦN ACCESSIBILITY")
        if (a11y == null) {
            Text("Accessibility chưa bật — các nút dưới sẽ không hoạt động.",
                fontSize = 12.sp, color = CFlipColors.Danger)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ControlTile(Icons.Filled.Lock, "Khoá máy", Modifier.weight(1f)) {
                if (a11y?.lockScreen() != true) toast("Cần bật Accessibility") }
            ControlTile(Icons.Filled.RecentActors, "Gần đây", Modifier.weight(1f)) {
                if (a11y?.openRecents() != true) toast("Cần bật Accessibility") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ControlTile(Icons.Filled.Notifications, "Thông báo", Modifier.weight(1f)) {
                if (a11y?.openNotifications() != true) toast("Cần bật Accessibility") }
            ControlTile(Icons.Filled.Tune, "Cài đặt nhanh", Modifier.weight(1f)) {
                if (a11y?.openQuickSettings() != true) toast("Cần bật Accessibility") }
        }

        SectionHeader("MỞ RỘNG")
        MirrorControls()
        // 25/10/2026: the old main-display bubble was removed per Cường's rule
        // (main display = custom/settings only, no bubble). Its replacement is
        // AssistiveTouch on the cover display, toggled from the main screen
        // under "Cài đặt cover" — not from here, and never on the main display.
        Text(
            "AssistiveTouch chỉ hiện trên màn phụ, bật/tắt ở màn hình chính → Cài đặt cover.",
            fontSize = 12.sp, color = CFlipColors.TextFaint,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Square tappable tile: icon over label, glass background. */
@Composable
private fun ControlTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = label, tint = CFlipColors.TextPrimary,
                modifier = Modifier.size(24.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = CFlipColors.TextPrimary)
        }
    }
}
