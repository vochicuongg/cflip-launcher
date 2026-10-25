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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.cuong.cflip.display.DisplayRepository
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.MyAccessibilityService
import com.cuong.cflip.service.NotificationController
import com.cuong.cflip.service.NotificationState

/**
 * CFlip 2.3 main screen, redesigned after Cường called the 2.2 hub ugly.
 *
 * What was wrong with 2.2: every section shouted at once - five 20sp
 * headings, full-width buttons stacked on each other, long explanations,
 * Mirror/Bubble controls embedded in the middle, and raw display text.
 * The redesign follows one rule: one hero, one primary action, then a
 * quiet grid, then compact lists. Nothing here is decorative text.
 *
 * - Hero: a miniature cover preview (clock, battery, now-playing hint,
 *   favourite dots) instead of a paragraph describing the app.
 * - Primary action: one mint "Mở Cover" button. Service control is a
 *   single toggle in its own card, not three stacked buttons.
 * - Status: three small stat cards, refreshed by one small "Làm mới"
 *   text button, not a full-width button.
 * - Mirror & Bubble are NOT on this screen any more - they live in the
 *   cover Quick tab where they are actually used. Removing them is
 *   what unclutters the hub; no feature was deleted.
 * - Every card still does something real: feature cards open the cover
 *   at that tab, permission rows open the exact system page, and the
 *   sort/toggle rows write to the same SettingsStore the cover reads.
 */
@Composable
fun MainScreen(
    initialTab: CoverTab = CoverTab.HOME,
    showCoverInitially: Boolean = false,
) {
    val context = LocalContext.current
    val displayRepo = remember { DisplayRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var showCover by remember { mutableStateOf(showCoverInitially) }
    var coverTab by remember { mutableStateOf(initialTab) }
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
    val notifications by NotificationState.active.collectAsState()
    val notifCount = notifications.size
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.3"
    }
    val sortLabel = when (settings.sortMode) { 1 -> "Mới mở"; 2 -> "Mới cài"; else -> "A-Z" }

    fun openCover(tab: CoverTab) { coverTab = tab; showCover = true }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(
        modifier = Modifier.fillMaxSize().background(CFlipColors.Bg)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Top bar: wordmark left, version pill right. No tagline paragraph.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                    .background(CFlipColors.AccentBrush), contentAlignment = Alignment.Center) {
                    Text("C", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.AccentDark)
                }
                Text("CFlip", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary)
            }
            Box(modifier = Modifier.clip(CircleShape).background(CFlipColors.Surface)
                .border(1.dp, CFlipColors.CardStroke, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(versionName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CFlipColors.TextSecondary)
            }
        }

        // Hero: miniature cover preview + one primary action.
        HeroCoverCard(
            notifCount = notifCount,
            onOpen = { openCover(CoverTab.HOME) },
        )

        // Status: three quiet stats, one small refresh action.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Quyền", "${statuses.grantedCount}/5", statuses.grantedCount == 5, Modifier.weight(1f))
            StatCard("Dịch vụ", if (statuses.serviceRunning) "Đang chạy" else "Tắt",
                statuses.serviceRunning, Modifier.weight(1f))
            StatCard("Màn phụ", if (statuses.secondarySeen) "Đã thấy" else "Chưa thấy",
                statuses.secondarySeen, Modifier.weight(1f))
        }
        TextButton(onClick = { statusVersion++ }, modifier = Modifier.align(Alignment.End)) {
            Text("Làm mới trạng thái", color = CFlipColors.TextSecondary, fontSize = 12.sp)
        }

        // Feature grid: muted icon tints, one line of subtitle each.
        SectionLabel("Cover của bạn")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard(Icons.Filled.Home, "Cover", "Đồng hồ · Pin",
                    Color(0xFF7FE3C0), Modifier.weight(1f)) { openCover(CoverTab.HOME) }
                FeatureCard(Icons.Filled.GridView, "Ứng dụng", "Sắp xếp: $sortLabel",
                    Color(0xFFA8C7FA), Modifier.weight(1f)) { openCover(CoverTab.APPS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard(Icons.Filled.Notifications, "Thông báo",
                    if (notifCount > 0) "$notifCount mới" else "Trống",
                    Color(0xFFF2C94C), Modifier.weight(1f)) { openCover(CoverTab.NOTIFS) }
                FeatureCard(Icons.Filled.MusicNote, "Nhạc", "Phát · Chuyển bài",
                    Color(0xFFC4B5FD), Modifier.weight(1f)) { openCover(CoverTab.MEDIA) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard(Icons.Filled.Bolt, "Điều khiển", "Đèn · Âm · Sáng",
                    Color(0xFFFDE68A), Modifier.weight(1f)) { openCover(CoverTab.QUICK) }
                FeatureCard(Icons.Filled.Palette, "Cá nhân hóa", "Lưới · Màu · Ẩn app",
                    Color(0xFFF9A8D4), Modifier.weight(1f)) { openCover(CoverTab.STYLE) }
            }
        }

        // Cover settings: segmented sort + three clean toggle rows.
        SectionLabel("Cài đặt cover")
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
                    for ((mode, label) in listOf(0 to "A-Z", 1 to "Mới mở", 2 to "Mới cài")) {
                        val selected = settings.sortMode == mode
                        Box(modifier = Modifier.weight(1f).clip(CircleShape)
                            .background(if (selected) CFlipColors.Accent else Color.Transparent)
                            .clickable {
                                settings = settings.copy(sortMode = mode)
                                settingsStore.save(settings)
                            }
                            .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary)
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
                // 25/10/2026: this toggle is cover-only. The main display
                // itself never shows a bubble — it is custom/settings only.
                HubSwitch("AssistiveTouch trên màn phụ", settings.assistiveTouchEnabled) {
                    settings = settings.copy(assistiveTouchEnabled = it); settingsStore.save(settings)
                    if (!it) com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                }
            }
        }

        // Permissions: compact rows, chevron only, no per-row buttons.
        SectionLabel("Quyền")
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                PermissionRow("Thông báo", statuses.notifications) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionRow("Accessibility", statuses.accessibility) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermissionRow("Hiển thị trên app khác", statuses.overlay) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)))
                }
                PermissionRow("Usage Access", statuses.usage) {
                    QuickControls.openUsageAccessSettings(context)
                }
                PermissionRow("Sửa cài đặt hệ thống", statuses.writeSettings) {
                    QuickControls.openWriteSettingsPermission(context)
                }
            }
        }

        // Service: one toggle button + one quiet text action.
        SectionLabel("Dịch vụ cover")
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (statuses.serviceRunning) CFlipColors.Accent else CFlipColors.TextFaint))
                    Text(if (statuses.serviceRunning) "Đang chạy — gập máy để thử cover"
                        else "Chưa chạy", fontSize = 14.sp, color = CFlipColors.TextPrimary,
                        modifier = Modifier.weight(1f))
                }
                Box(modifier = Modifier.fillMaxWidth().clip(CircleShape)
                    .background(if (statuses.serviceRunning) CFlipColors.Surface else CFlipColors.Accent)
                    .border(1.dp, if (statuses.serviceRunning) CFlipColors.CardStroke else Color.Transparent, CircleShape)
                    .clickable {
                        if (statuses.serviceRunning) {
                            context.stopService(Intent(context, CoverForegroundService::class.java))
                        } else {
                            ContextCompat.startForegroundService(context, Intent(context, CoverForegroundService::class.java))
                            toast("Đã chạy dịch vụ — gập máy lại để thử")
                        }
                        statusVersion++
                    }
                    .padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text(if (statuses.serviceRunning) "Dừng dịch vụ" else "Bắt đầu dịch vụ",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (statuses.serviceRunning) CFlipColors.TextPrimary else CFlipColors.AccentDark)
                }
                TextButton(onClick = {
                    // Fix 25/10/2026: use the application context (not the
                    // activity context, whose token dies on fold) and check
                    // the overlay permission first, so a failure shows its
                    // real reason instead of a truncated window dump.
                    if (!Settings.canDrawOverlays(context)) {
                        toast("Chưa cấp quyền Hiển thị trên app khác — cấp ở mục Quyền rồi thử lại.")
                        return@TextButton
                    }
                    val secondary = displayRepo.secondaryDisplay()
                    if (secondary == null) {
                        toast("Chưa thấy màn hình phụ. Gập máy lại để thấy.")
                    } else {
                        // 2.3.2: Presentation first, overlay fallback. If
                        // Presentation is refused on the Flex Window, the
                        // overlay route (CoverOverlayManager) is tried in
                        // the same tap, so one tap tells us which path
                        // actually works on this phone instead of failing
                        // outright with a truncated window error.
                        presentation?.dismiss()
                        com.cuong.cflip.cover.CoverOverlayManager.hide()
                        val presResult = runCatching {
                            CoverPresentation(context.applicationContext, secondary).also {
                                presentation = it; it.show()
                            }
                        }
                        if (presResult.isSuccess) {
                            toast("Presentation đã hiện lên màn phụ.")
                        } else {
                            presentation = null
                            val overlayResult = com.cuong.cflip.cover.CoverOverlayManager
                                .show(context.applicationContext, secondary)
                            if (overlayResult.isSuccess) {
                                toast("Presentation bị chặn, đã hiện bằng Overlay thay thế.")
                            } else {
                                val reason = presResult.exceptionOrNull()?.message
                                    ?.substringAfter("--")?.trim()
                                    ?: "không rõ"
                                toast("Cả 2 đường đều bị chặn: $reason")
                            }
                        }
                        // Cover-only AssistiveTouch with the cover UI.
                        if (settings.assistiveTouchEnabled) {
                            com.cuong.cflip.cover.CoverAssistiveTouchManager
                                .show(context.applicationContext, secondary)
                        }
                    }
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Hiện Presentation lên màn phụ →", color = CFlipColors.Accent, fontSize = 13.sp)
                }
                Text("Widget chính thống: Good Lock › MultiStar › Launcher Widget › thêm widget CFlip.",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
            }
        }
        Spacer(Modifier.height(8.dp))
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
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = CFlipColors.TextFaint, letterSpacing = 1.4.sp)
}

/** Miniature cover preview: the visual anchor of the whole screen. */
@Composable
private fun HeroCoverCard(notifCount: Int, onOpen: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF122B23), Color(0xFF0B0E0D))))
        .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(28.dp))
        .padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("09:41", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                        color = CFlipColors.TextPrimary, letterSpacing = (-1).sp)
                    Text("Thứ Bảy, 25/10", fontSize = 13.sp, color = CFlipColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.clip(CircleShape).background(Color(0x1FFFFFFF))
                            .padding(horizontal = 9.dp, vertical = 4.dp)) {
                            Text("Pin 78%", fontSize = 12.sp, color = CFlipColors.TextPrimary)
                        }
                        Box(modifier = Modifier.clip(CircleShape).background(Color(0x1FFFFFFF))
                            .padding(horizontal = 9.dp, vertical = 4.dp)) {
                            Text(if (notifCount > 0) "$notifCount thông báo" else "Không có thông báo",
                                fontSize = 12.sp, color = CFlipColors.TextPrimary)
                        }
                    }
                }
                // Favourite dots: 2x2 mini grid, the cover at a glance.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniAppDot("Z", Color(0xFF3B82F6))
                        MiniAppDot("M", Color(0xFF8B5CF6))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniAppDot("Y", Color(0xFFEF4444))
                        MiniAppDot("C", Color(0xFF7FE3C0))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Accent)
                .clickable { onOpen() }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center) {
                Text("Mở Cover", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.AccentDark)
            }
        }
    }
}

@Composable
private fun MiniAppDot(letter: String, color: Color) {
    Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.22f))
        .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center) {
        Text(letter, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun StatCard(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(18.dp)).background(CFlipColors.Surface)
        .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(18.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                .background(if (ok) CFlipColors.Accent else CFlipColors.TextFaint))
            Text(label, fontSize = 12.sp, color = CFlipColors.TextSecondary)
        }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector, title: String, subtitle: String, tint: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = CFlipColors.TextSecondary)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(22.dp).clip(CircleShape)
            .background(if (granted) CFlipColors.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (granted) CFlipColors.Accent else CFlipColors.CardStroke, CircleShape),
            contentAlignment = Alignment.Center) {
            if (granted) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CFlipColors.Accent)
        }
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(if (granted) "Đã cấp" else "Chưa cấp", fontSize = 12.sp,
            color = if (granted) CFlipColors.TextSecondary else CFlipColors.Danger)
        Text("  ›", fontSize = 16.sp, color = CFlipColors.TextFaint)
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
