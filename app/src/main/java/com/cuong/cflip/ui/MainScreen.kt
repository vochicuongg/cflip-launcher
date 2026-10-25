package com.cuong.cflip.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.cover.CoverPresentation
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.MyAccessibilityService
import com.cuong.cflip.service.NotificationController
import com.cuong.cflip.service.NotificationState
import com.cuong.cflip.display.DisplayRepository

/**
 * CFlip 2.2 main-screen hub (large-screen UI, redesigned from the structure
 * observed in the reference app's home screen: status cards first, then a
 * feature grid, then permission and service sections - but with CFlip's own
 * mint/glass design, its own wording, and no paywall or premium card.
 *
 * Every card here does something real: feature cards open the cover UI
 * (CoverApp) straight at the matching tab, permission rows open the exact
 * system settings page, and the quick settings below write to the same
 * SettingsStore the cover UI reads.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val displayRepo = remember { DisplayRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var showCover by remember { mutableStateOf(false) }
    var coverTab by remember { mutableStateOf(CoverTab.HOME) }
    var presentation by remember { mutableStateOf<CoverPresentation?>(null) }

    DisposableEffect(presentation) {
        onDispose { presentation?.dismiss() }
    }

    if (showCover) {
        BackHandler { showCover = false }
        Column(modifier = Modifier.fillMaxSize().background(CFlipColors.Bg)) {
            TextButton(onClick = { showCover = false }) { Text("← Quay lại", color = CFlipColors.Accent) }
            CoverApp(modifier = Modifier.weight(1f),
                targetDisplayId = displayRepo.secondaryDisplay()?.displayId,
                initialTab = coverTab)
        }
        return
    }

    // Statuses are plain system checks, not guesses. They only refresh when
    // the user taps "Làm mới" (returning from a system settings page does
    // not reliably recompose this screen), so the button is always visible.
    var statusVersion by remember { mutableStateOf(0) }
    val statuses = remember(statusVersion) {
        MainStatuses(
            notifications = NotificationController.isConnected(),
            accessibility = MyAccessibilityService.instance != null,
            overlay = Settings.canDrawOverlays(context),
            usage = RecentAppsRepository(context).hasUsageAccess(),
            writeSettings = QuickControls.canWriteSettings(context),
            serviceRunning = isServiceRunning(context, CoverForegroundService::class.java),
            secondarySeen = displayRepo.secondaryDisplay() != null,
        )
    }
    val notifCount by NotificationState.active.collectAsState()
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.2"
    }
    val sortLabel = when (settings.sortMode) { 1 -> "Mới mở"; 2 -> "Mới cài"; else -> "A-Z" }

    fun openCover(tab: CoverTab) { coverTab = tab; showCover = true }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(
        modifier = Modifier.fillMaxSize().background(CFlipColors.Bg)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                .background(CFlipColors.AccentBrush), contentAlignment = Alignment.Center) {
                Text("C", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CFlipColors.AccentDark)
            }
            Column {
                Text("CFlip Launcher", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = CFlipColors.TextPrimary)
                Text("Cover launcher của Cường · Galaxy Z Flip5 · $versionName",
                    fontSize = 13.sp, color = CFlipColors.TextSecondary)
            }
        }
        Text("Mọi tính năng đều miễn phí, không paywall.",
            fontSize = 13.sp, color = CFlipColors.TextSecondary)

        // Status strip
        SectionHeader("TRẠNG THÁI")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quyền: ${statuses.grantedCount}/5 · Dịch vụ cover: " +
                    if (statuses.serviceRunning) "đang chạy" else "chưa chạy" +
                    " · Màn phụ: " + if (statuses.secondarySeen) "đã thấy" else "chưa thấy (gập máy để thấy)",
                    fontSize = 13.sp, color = CFlipColors.TextPrimary)
                Text("Màn hình phát hiện: " +
                    displayRepo.listDisplays().joinToString { "#${it.id} ${it.name} ${it.widthPx}x${it.heightPx}" },
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
                OutlinedButton(onClick = { statusVersion++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Làm mới trạng thái (bấm sau khi cấp quyền)")
                }
            }
        }

        // Feature grid: each card opens the cover UI at that tab.
        SectionHeader("COVER CỦA BẠN")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureCard(Icons.Filled.Home, "Cover", "Đồng hồ, pin, yêu thích",
                Modifier.weight(1f)) { openCover(CoverTab.HOME) }
            FeatureCard(Icons.Filled.GridView, "Ứng dụng", "Sắp xếp: $sortLabel",
                Modifier.weight(1f)) { openCover(CoverTab.APPS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureCard(Icons.Filled.Notifications, "Thông báo",
                if (notifCount > 0) "$notifCount thông báo mới" else "Chưa có thông báo",
                Modifier.weight(1f)) { openCover(CoverTab.NOTIFS) }
            FeatureCard(Icons.Filled.MusicNote, "Nhạc", "Phát, tạm dừng, chuyển bài",
                Modifier.weight(1f)) { openCover(CoverTab.MEDIA) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureCard(Icons.Filled.Bolt, "Điều khiển nhanh", "Đèn, âm lượng, độ sáng",
                Modifier.weight(1f)) { openCover(CoverTab.QUICK) }
            FeatureCard(Icons.Filled.Palette, "Cá nhân hóa", "Lưới, màu, hình nền, ẩn app",
                Modifier.weight(1f)) { openCover(CoverTab.STYLE) }
        }

        // Quick cover settings, writing to the same store the cover reads.
        SectionHeader("SẮP XẾP & HIỂN THỊ NHANH")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sắp xếp ứng dụng", fontSize = 13.sp, color = CFlipColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((mode, label) in listOf(0 to "A-Z", 1 to "Mới mở", 2 to "Mới cài")) {
                        val selected = settings.sortMode == mode
                        Box(modifier = Modifier.weight(1f).clip(CircleShape)
                            .background(if (selected) CFlipColors.Accent else CFlipColors.CardFill)
                            .border(1.dp, CFlipColors.CardStroke, CircleShape)
                            .clickable {
                                settings = settings.copy(sortMode = mode)
                                settingsStore.save(settings)
                            }
                            .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = if (selected) CFlipColors.AccentDark else CFlipColors.TextPrimary)
                        }
                    }
                }
                HubSwitch("Yêu thích lên trước", settings.favoritesFirst) {
                    settings = settings.copy(favoritesFirst = it); settingsStore.save(settings)
                }
                HubSwitch("Đồng hồ 24 giờ", settings.clock24h) {
                    settings = settings.copy(clock24h = it); settingsStore.save(settings)
                }
                HubSwitch("Hiện nhãn ứng dụng", settings.showLabels) {
                    settings = settings.copy(showLabels = it); settingsStore.save(settings)
                }
            }
        }

        // Permissions, one row each with its real status and settings page.
        SectionHeader("QUYỀN")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionRow("Truy cập thông báo", "Thông báo + nhạc", statuses.notifications) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionRow("Accessibility", "Khoá máy, Gần đây, Cài đặt nhanh", statuses.accessibility) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermissionRow("Hiển thị trên ứng dụng khác", "Bong bóng menu", statuses.overlay) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)))
                }
                PermissionRow("Usage Access", "Danh sách ứng dụng gần đây thật", statuses.usage) {
                    QuickControls.openUsageAccessSettings(context)
                }
                PermissionRow("Sửa cài đặt hệ thống", "Chỉnh độ sáng", statuses.writeSettings) {
                    QuickControls.openWriteSettingsPermission(context)
                }
            }
        }

        // Cover service
        SectionHeader("DỊCH VỤ COVER")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ContextCompat.startForegroundService(context, Intent(context, CoverForegroundService::class.java))
                    statusVersion++
                    toast("Đã chạy dịch vụ CFlip — gập máy lại để thử")
                }, modifier = Modifier.fillMaxWidth()) { Text("Bắt đầu dịch vụ chạy nền") }
                OutlinedButton(onClick = {
                    context.stopService(Intent(context, CoverForegroundService::class.java))
                    statusVersion++
                }, modifier = Modifier.fillMaxWidth()) { Text("Dừng dịch vụ") }
                Button(onClick = {
                    val secondary = displayRepo.secondaryDisplay()
                    if (secondary == null) {
                        toast("Chưa thấy màn hình phụ. Trên Flip5 thật: gập máy lại. Trên emulator: thêm màn hình phụ.")
                    } else {
                        presentation?.dismiss()
                        presentation = CoverPresentation(context, secondary).also {
                            runCatching { it.show() }.onFailure { e ->
                                toast("Presentation bị chặn: ${e.message}")
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Hiện Presentation lên màn hình phụ") }
                Text("Đường chính thống bằng widget: Good Lock › MultiStar › Launcher Widget › thêm widget CFlip Launcher. Sau đó vuốt cover sang trang widget để dùng.",
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
            }
        }

        SectionHeader("PHẢN CHIẾU & BONG BÓNG")
        GlassCard(modifier = Modifier.fillMaxWidth()) { MirrorControls() }
        GlassCard(modifier = Modifier.fillMaxWidth()) { BubbleControls() }
        Spacer(Modifier.height(4.dp))
    }
}

private data class MainStatuses(
    val notifications: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val usage: Boolean,
    val writeSettings: Boolean,
    val serviceRunning: Boolean,
    val secondarySeen: Boolean,
) {
    val grantedCount: Int
        get() = listOf(notifications, accessibility, overlay, usage, writeSettings).count { it }
}

@Suppress("DEPRECATION")
private fun isServiceRunning(context: Context, cls: Class<*>): Boolean {
    val am = context.getSystemService(ActivityManager::class.java) ?: return false
    return runCatching { am.getRunningServices(Int.MAX_VALUE) }
        .getOrNull()?.any { it.service.className == cls.name } == true
}

@Composable
private fun FeatureCard(
    icon: ImageVector, title: String, subtitle: String,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(CFlipColors.Accent),
                contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, contentDescription = null,
                    tint = CFlipColors.AccentDark, modifier = Modifier.size(20.dp))
            }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = CFlipColors.TextSecondary)
        }
    }
}

@Composable
private fun PermissionRow(label: String, hint: String, granted: Boolean, onOpen: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "●" else "○", fontSize = 14.sp,
            color = if (granted) CFlipColors.Accent else CFlipColors.Danger,
            modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CFlipColors.TextPrimary)
            Text(if (granted) "Đã cấp · $hint" else "Chưa cấp · $hint",
                fontSize = 12.sp, color = CFlipColors.TextSecondary)
        }
        TextButton(onClick = onOpen) { Text("Mở", color = CFlipColors.Accent) }
    }
}

@Composable
private fun HubSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CFlipColors.AccentDark,
                checkedTrackColor = CFlipColors.Accent,
            ))
    }
}
