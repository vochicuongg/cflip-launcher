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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.display.DisplayRepository
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.MyAccessibilityService
import com.cuong.cflip.service.NotificationController

/**
 * CFlip 2.3 main screen, redesigned after Cường called the 2.2 hub ugly.
 *
 * What was wrong with 2.2: every section shouted at once - five 20sp
 * headings, full-width buttons stacked on each other, long explanations,
 * Mirror/Bubble controls embedded in the middle, and raw display text.
 * The redesign follows one rule: a quiet grid, then compact lists.
 * Nothing here is decorative text.
 *
 * - vc125 (user, 29/10): the hero miniature-cover preview + the mint
 *   "Mở Cover" primary action were REMOVED (display only). The cover
 *   still opens via AssistiveTouch / Swipe indicator and via
 *   MainActivity's cover intent. Service control is a single toggle
 *   in its own card, not three stacked buttons.
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
    // vc115 PERMISSIONS LIVE UPDATE (user, 27/10):
    // permission rows used to refresh only when
    // the user tapped "Lam moi trang thai" - after
    // granting a permission in system settings and
    // returning to CFlip, the rows still showed the
    // old state. Now ON_RESUME reloads settings
    // and bumps statusVersion, so every return
    // from system settings refreshes the rows
    // immediately, with no manual refresh tap.
    // (DisposableEffect is already imported.)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = settingsStore.load()
                statusVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.3"
    }

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

        // vc122 PERMISSIONS FIRST (user, 28/10):
        // the Permissions island moves to the top
        // of the app (right after the top bar),
        // so granting comes before everything else.
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

        // vc122 GOODLOCK WARNING (user, 28/10):
        // CFlip runs standalone on the cover and
        // never routes through GoodLock/MultiStar.
        // The warning sits with Permissions at the
        // top so a new user sees it before trying
        // to add CFlip to GoodLock.
        Island("Cảnh báo") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Không thêm CFlip vào GoodLock.",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
                Text("CFlip chạy độc lập trên màn phụ, không cần GoodLock hay MultiStar. Thêm vào GoodLock không làm CFlip chạy tốt hơn.",
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
                Text("Mở panel bằng AssistiveTouch hoặc Thanh Vuốt trong mục Quick Panel, không qua GoodLock.",
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
            }
        }


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

        // Widgets, 3.1.0 — USER-ADDED ONLY, corrected after the user's
        // complaint. Decompile truth (9Flip v149, static only):
        // NineCoverAppGridWidgetProvider + RemoteViewsService and the
        // Cassette widget family are standard Android AppWidgetProviders.
        // No AppWidgetHost code and no forced overlay widget were found
        // in the native scope — the user places widgets, the app does not
        // force them. CFlip now follows that exactly:
        //  - Nothing shows on the cover until the user turns a widget on
        //    below (stored in SettingsStore.coverWidgets, default empty).
        //  - 3.4.0: NO requestPinAppWidget() anymore. Pin is the main-home
        //    launcher's dialog ("Mở điện thoại để tiếp tục") and can never
        //    place a widget on the cover. 9Flip never pins either: it opens
        //    Samsung's cover widget settings, and its widgets are keyguard
        //    (not home_screen) + Samsung sub_screen, so Samsung's cover
        //    picker on the folded phone lists them. CFlip now does the same.
        //  - The cover widgets below live inside CFlip's own AssistiveTouch
        //    panel, and only after the user adds them there.
        SectionLabel("Widget — chỉ thêm khi bạn tự thêm")
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Widget màn phụ (hiện trong panel AssistiveTouch, chỉ khi bạn bật):",
                    fontSize = 13.sp, color = CFlipColors.TextPrimary)
                run {
                    val chosen = settings.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    fun toggleWidget(key: String, on: Boolean) {
                        val next = if (on) chosen + key else chosen - key
                        settings = settings.copy(coverWidgets = next.sorted().joinToString(","))
                        settingsStore.save(settings)
                    }
                    HubSwitch("Widget Đồng hồ & Pin", "clock" in chosen) { toggleWidget("clock", it) }
                    HubSwitch("Widget Lưới ứng dụng yêu thích", "appgrid" in chosen) { toggleWidget("appgrid", it) }
                }
                Text("Chưa bật Widget nào thì panel AssistiveTouch sẽ ghi rõ là chưa thêm Widget — không tự hiện gì cả.",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
                Text("Widget màn phụ Samsung (không dùng Ghim nữa):",
                    fontSize = 13.sp, color = CFlipColors.TextPrimary)
                Text("Nút Ghim cũ đã gỡ, vì Ghim là hộp thoại của màn hình chính: nó luôn bắt “Mở điện thoại để tiếp tục” và gắn Widget lên màn hình chính, không bao giờ lên màn phụ. 9Flip cũng không dùng Ghim cho Widget màn phụ.",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
                Text("Cách thêm đúng, theo decompile 9Flip:",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
                Text("1) Trực tiếp trên màn phụ, không cần mở máy: gập máy lại, vuốt Widget màn phụ tới cuối, bấm +, chọn Widget CFlip trong danh sách Widget Samsung.",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
                Text("2) Hoặc mở Cài đặt Widget màn phụ Samsung 1 lần (trang này của Samsung chỉ mở được trên màn chính), chọn Widget CFlip ở đó, rồi gập máy lại là thấy trên màn phụ.",
                    fontSize = 12.sp, color = CFlipColors.TextFaint)
                TextButton(onClick = {
                    com.cuong.cflip.widget.SamsungCoverWidgetHelper.openCoverWidgetSettings(context)
                }, modifier = Modifier.align(Alignment.Start)) {
                    Text("Mở Cài đặt Widget màn phụ Samsung", color = CFlipColors.Accent, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    toast(com.cuong.cflip.widget.SamsungCoverWidgetHelper.statusText(context))
                }, modifier = Modifier.align(Alignment.Start)) {
                    Text("Kiểm tra Widget màn phụ đã thêm chưa", color = CFlipColors.Accent, fontSize = 13.sp)
                }
            }
        }

        // vc121 ISLANDS (user, 28/10: group the
        // main settings into separate feature
        // islands, neat like 9Flip). The old
        // "Cai dat cover" box crammed unrelated
        // features - display, AssistiveTouch,
        // Quick Panel, touch actions - into one
        // card. Each group is now its own island
        // (9Flip pattern: grouped sections, one
        // feature per rounded card, same card
        // style as the existing Widget /
        // Permissions / Service islands). Nothing
        // is added or removed - same rows, same
        // order, same save logic, only grouped.
        // One move: the AssistiveTouch position
        // reset button moves from the touch-action
        // rows into the AssistiveTouch island,
        // where it belongs. Island labels sit
        // 8dp above their card (before, a label
        // sat 24dp above its card, the outer
        // section spacing, which read as detached).
        Island("AssistiveTouch") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 25/10/2026: this toggle is cover-only. The main display
                // itself never shows a bubble — it is custom/settings only.
                HubSwitch("AssistiveTouch trên màn phụ", settings.assistiveTouchEnabled) {
                    settings = settings.copy(assistiveTouchEnabled = it); settingsStore.save(settings)
                    if (!it) com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                }
                // 2.7.0 redesign: size (small 0.8 / default 1.0 / large 1.2),
                // auto-dim, and position reset. Size/auto-dim only take
                // effect the next time the button is shown (service re-show
                // or panel close), because the overlay button is built once.
                if (settings.assistiveTouchEnabled) {
                    Row(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
                        for ((size, label) in listOf(0 to "Nhỏ", 1 to "Vừa", 2 to "Lớn")) {
                            val selected = settings.assistiveTouchSize == size
                            Box(modifier = Modifier.weight(1f).clip(CircleShape)
                                .background(if (selected) CFlipColors.Accent else Color.Transparent)
                                .clickable {
                                    settings = settings.copy(assistiveTouchSize = size)
                                    settingsStore.save(settings)
                                }
                                .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary)
                            }
                        }
                    }
                    HubSwitch("Tự mờ sau 2.5s không chạm", settings.assistiveTouchAutoDim) {
                        settings = settings.copy(assistiveTouchAutoDim = it); settingsStore.save(settings)
                    }
                }
                TextButton(onClick = {
                    context.getSharedPreferences("assistive_touch", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                    toast("Đã đặt lại vị trí AssistiveTouch về cạnh phải, giữa màn phụ.")
                }, modifier = Modifier.align(Alignment.Start)) {
                    Text("Đặt lại vị trí AssistiveTouch", color = CFlipColors.Accent, fontSize = 13.sp)
                }
            }
        }

        Island("Quick Panel") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // vc120 QUICK PANEL OPEN MODE (user,
                // 28/10): Quick Panel is NOT a bubble
                // action anymore - it is a feature
                // with two open modes (9Flip gb):
                // AssistiveTouch bubble or Swipe
                // indicator bar.
                var openModeExpanded by remember { mutableStateOf(false) }
                OpenModeRow("Mở panel bằng", settings.panelOpenMode,
                    expanded = openModeExpanded,
                    onExpand = { openModeExpanded = true },
                    onDismiss = { openModeExpanded = false },
                    onSelect = { m -> settings = settings.copy(panelOpenMode = m); settingsStore.save(settings); openModeExpanded = false })
                Text("Thanh Vuốt là thanh nhỏ ở đáy màn cover: vuốt lên (hoặc chạm) để mở panel đầy đủ. Chọn Thanh Vuốt thì bong bóng sẽ ẩn đi, chọn AssistiveTouch thì thanh sẽ ẩn đi.",
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
            }
        }

        Island("Thao tác chạm") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // vc117 DROPDOWN (user, 27/10):
                // tap-to-cycle rows were easy to
                // overshoot (e.g. cycling past to
                // NONE, then long-press looked dead).
                // Each row now opens a dropdown with
                // all 5 actions, so the choice is
                // visible before it is made. Defaults:
                // tap = Home, double-tap = Full panel,
                // long-press = Recents.
                Text("Chạm vào hàng để chọn:",
                    fontSize = 13.sp, color = CFlipColors.TextPrimary)
                var bubbleMenuExpanded by remember { mutableStateOf<String?>(null) }
                BubbleActionRow("Chạm 1 lần", settings.bubbleTapAction,
                    expanded = bubbleMenuExpanded == "tap",
                    onExpand = { bubbleMenuExpanded = "tap" },
                    onDismiss = { if (bubbleMenuExpanded == "tap") bubbleMenuExpanded = null },
                    onSelect = { a -> settings = settings.copy(bubbleTapAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
                BubbleActionRow("Chạm 2 lần", settings.bubbleDoubleTapAction,
                    expanded = bubbleMenuExpanded == "double",
                    onExpand = { bubbleMenuExpanded = "double" },
                    onDismiss = { if (bubbleMenuExpanded == "double") bubbleMenuExpanded = null },
                    onSelect = { a -> settings = settings.copy(bubbleDoubleTapAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
                BubbleActionRow("Giữ lâu", settings.bubbleLongPressAction,
                    expanded = bubbleMenuExpanded == "long",
                    onExpand = { bubbleMenuExpanded = "long" },
                    onDismiss = { if (bubbleMenuExpanded == "long") bubbleMenuExpanded = null },
                    onSelect = { a -> settings = settings.copy(bubbleLongPressAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
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
                    // 3.0.0: Presentation deleted. This test button now
                    // shows ONLY the AssistiveTouch button on the cover
                    // display, then opens its 4-tab Widget panel there.
                    // No Presentation, no full-screen overlay launcher.
                    if (!Settings.canDrawOverlays(context)) {
                        toast("Chưa cấp quyền Hiển thị trên app khác — cấp ở mục Quyền rồi thử lại.")
                        return@TextButton
                    }
                    val secondary = displayRepo.secondaryDisplay()
                    if (secondary == null) {
                        toast("Chưa thấy màn hình phụ. Gập máy lại để thấy.")
                    } else {
                        val atResult = com.cuong.cflip.cover.CoverAssistiveTouchManager
                            .show(context.applicationContext, secondary)
                        if (atResult.isSuccess) {
                            com.cuong.cflip.cover.CoverControlCenterManager
                                .show(context.applicationContext, secondary)
                            toast("Đã mở Widget AssistiveTouch (4 tab) lên màn phụ.")
                        } else {
                            toast("AssistiveTouch bị chặn: ${atResult.exceptionOrNull()?.message ?: "không rõ"}")
                        }
                    }
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Mở Widget AssistiveTouch lên màn phụ →", color = CFlipColors.Accent, fontSize = 13.sp)
                }
                Text("Widget màn phụ: chính là panel 4 tab của AssistiveTouch (Điều khiển / Thông báo / Ứng dụng / CFlip) — đúp nút C trên màn phụ để mở, không còn giao diện cover toàn màn hình nữa.",
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

// vc121 ISLAND: one feature group = one
// island - a small caps label 8dp above a
// rounded card, same card style as the
// existing Widget / Permissions / Service
// islands (20dp corners, Surface, CardStroke,
// 16dp padding). Callers pass their own inner
// Column so row spacing stays as before.
@Composable
private fun Island(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = CFlipColors.TextFaint, letterSpacing = 1.4.sp)
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

// vc117: one bubble-action row with a dropdown
// menu (top-level @Composable, like HubSwitch).
@Composable
private fun BubbleActionRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Text(com.cuong.cflip.data.BubbleActions.label(cur),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = CFlipColors.Accent)
            Text("  \u25BE", fontSize = 13.sp, color = CFlipColors.TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            com.cuong.cflip.data.BubbleActions.all.forEach { action ->
                DropdownMenuItem(
                    text = { Text(com.cuong.cflip.data.BubbleActions.label(action)) },
                    onClick = { onSelect(action); onDismiss() }
                )
            }
        }
    }
}

// vc120: open-mode row, same dropdown pattern
// as BubbleActionRow, for PanelOpenMode.
@Composable
private fun OpenModeRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Text(com.cuong.cflip.data.PanelOpenMode.label(cur),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = CFlipColors.Accent)
            Text("  \u25BE", fontSize = 13.sp, color = CFlipColors.TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            com.cuong.cflip.data.PanelOpenMode.all.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(com.cuong.cflip.data.PanelOpenMode.label(mode)) },
                    onClick = { onSelect(mode); onDismiss() }
                )
            }
        }
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
